#!/usr/bin/env python3
"""What should a lane do next? The autopilot's decision engine (AGENTS.md §13).

Usage:
  python tools/agents/next_action.py --lane B              # next action, human-readable
  python tools/agents/next_action.py --lane A --json       # machine-readable (supervisor, hooks)
  python tools/agents/next_action.py --lane B --wait 600   # block until there is work (≤ 600 s)
  python tools/agents/next_action.py --user                # what is waiting on the user

State comes from GitHub (open PRs, CI, signed review comments) and from task files on
origin/main — never from a working tree, which may be on any branch. `decide()` is pure so it
can be unit-tested without git or GitHub.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import (AGENT, CHECKS, CLONES, LOCKFILES, OTHER, PROJECT, SLUG, autopilot_on,  # noqa: E402
                    gh_json, git, marker)

import board  # noqa: E402  (tools/board.py; common.py put tools/ on sys.path)

MAX_OPEN_PRS_BEFORE_WAIT = 3
MARKER = re.compile(rf"<!--\s*{re.escape(SLUG)}:agent=(claude|gemini)\s+verdict=(lgtm|changes|comment)\s*-->", re.I)


# --------------------------------------------------------------------------------------------
# State
# --------------------------------------------------------------------------------------------

@dataclass
class PR:
    number: int
    title: str
    head: str
    lane: str | None
    contract: bool
    draft: bool
    mergeable: str          # MERGEABLE | CONFLICTING | UNKNOWN
    ci: str                 # success | failure | pending | none
    last_commit_at: str     # ISO-8601 UTC
    verdicts: dict = field(default_factory=dict)  # agent -> {"verdict", "at"} (latest by that agent)


def pr_lane(head: str) -> tuple[str | None, bool]:
    for prefix, lane, contract in (("claude/", "A", False), ("gemini/", "B", False),
                                   ("contract/claude-", "A", True), ("contract/gemini-", "B", True)):
        if head.startswith(prefix):
            return lane, contract
    return None, False


def ci_state(rollup: list) -> str:
    if not rollup:
        return "none"
    states = []
    for c in rollup:
        status = (c.get("status") or c.get("state") or "").upper()
        conclusion = (c.get("conclusion") or "").upper()
        if status and status != "COMPLETED" and not conclusion:
            states.append("pending")
        elif conclusion in ("SUCCESS", "NEUTRAL", "SKIPPED") or status == "SUCCESS":
            states.append("success")
        elif conclusion == "" and status in ("PENDING", "QUEUED", "IN_PROGRESS", "EXPECTED"):
            states.append("pending")
        else:
            states.append("failure")
    if "failure" in states:
        return "failure"
    if "pending" in states:
        return "pending"
    return "success"


def classify_comment(body: str) -> tuple[str | None, str | None]:
    """(agent, verdict) of a PR comment. Only signed comments count (AGENTS.md §5.4); this
    project started with markers, so there is no unsigned history to recognise."""
    m = MARKER.search(body)
    if m:
        return m.group(1).lower(), m.group(2).lower()
    return None, None


def parse_prs(raw: list) -> list[PR]:
    prs = []
    for p in raw:
        lane, contract = pr_lane(p["headRefName"])
        commits = p.get("commits") or []
        last = p.get("lastCommitAt") or (commits[-1]["committedDate"] if commits else "")
        verdicts: dict = {}
        for c in sorted(p.get("comments") or [], key=lambda c: c["createdAt"]):
            agent, verdict = classify_comment(c.get("body", ""))
            if agent and verdict in ("lgtm", "changes"):
                verdicts[agent] = {"verdict": verdict, "at": c["createdAt"]}
        prs.append(PR(p["number"], p["title"], p["headRefName"], lane, contract, bool(p.get("isDraft")),
                      p.get("mergeable") or "UNKNOWN", ci_state(p.get("statusCheckRollup") or []),
                      last, verdicts))
    return prs


def load_main_tasks(clone: Path) -> dict[str, dict]:
    """Task files as they are on origin/main, parsed with board.py's front-matter parser."""
    names = [n for n in git(clone, "ls-tree", "-r", "--name-only", "origin/main", "tasks/").splitlines()
             if n.endswith(".md") and not Path(n).name.startswith("_") and Path(n).name != "README.md"]
    if not names:
        return {}
    # One `git cat-file --batch` call instead of one `git show` per file.
    raw = subprocess.run(["git", "-C", str(clone), "cat-file", "--batch"], capture_output=True, timeout=120,
                         input="".join(f"origin/main:{n}\n" for n in names).encode("utf-8")).stdout
    texts, pos = [], 0
    for _ in names:
        nl = raw.index(b"\n", pos)
        header = raw[pos:nl].split()
        if len(header) < 3 or header[1] != b"blob":
            pos = nl + 1
            continue
        size = int(header[2])
        texts.append(raw[nl + 1:nl + 1 + size].decode("utf-8", errors="replace"))
        pos = nl + 1 + size + 1
    tasks = {}
    for text in texts:
        try:
            t = board.parse_front_matter(text)
        except ValueError:
            continue
        if "id" in t:
            t.setdefault("needs", [])
            tasks[t["id"]] = t
    return tasks


def collect(lane: str) -> dict:
    clone = CLONES[lane]
    git(clone, "fetch", "--prune", "--quiet", "origin")
    raw = gh_json(clone, "pr", "list", "--state", "open", "--limit", "50", "--json",
                  "number,title,headRefName,headRefOid,isDraft,mergeable,comments,statusCheckRollup") or []
    # Last-commit time of each PR head, read locally (asking GitHub for commit lists is too heavy).
    oids = [p["headRefOid"] for p in raw if p.get("headRefOid")]
    times = {}
    if oids:
        out = subprocess.run(["git", "-C", str(clone), "log", "--no-walk=unsorted", "--format=%H %ct", *oids],
                             capture_output=True, text=True, timeout=60).stdout
        for line in out.splitlines():
            sha, _, ts = line.partition(" ")
            times[sha] = datetime.fromtimestamp(int(ts), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    for p in raw:
        p["lastCommitAt"] = times.get(p.get("headRefOid", ""), "")
    branches = [b.strip() for b in git(clone, "branch", "-r", "--format=%(refname:short)").splitlines()]
    return {"prs": parse_prs(raw), "tasks": load_main_tasks(clone), "branches": branches}


# --------------------------------------------------------------------------------------------
# Decision
# --------------------------------------------------------------------------------------------

def _after(a: str, b: str) -> bool:
    return bool(a) and (not b or a > b)  # ISO-8601 UTC strings compare chronologically


def decide(state: dict, lane: str, work_ahead: bool = True) -> dict:
    me, other = AGENT[lane], AGENT[OTHER[lane]]
    prs: list[PR] = state["prs"]
    tasks: dict = state["tasks"]
    mine = [p for p in prs if p.lane == lane and not p.draft]
    theirs = [p for p in prs if p.lane == OTHER[lane] and not p.draft]

    def act(kind: str, summary: str, **extra) -> dict:
        return {"kind": kind, "lane": lane, "summary": summary, **extra}

    for p in mine:
        if p.ci == "failure":
            return act("FIX_CI", f"CI is failing on your PR #{p.number} ({p.title})", pr=p.number, branch=p.head)
    for p in mine:
        if p.mergeable == "CONFLICTING":
            return act("REBASE", f"Your PR #{p.number} ({p.title}) conflicts with main", pr=p.number, branch=p.head)
    for p in mine:
        v = p.verdicts.get(other)
        if v and v["verdict"] == "changes" and _after(v["at"], p.last_commit_at):
            return act("ADDRESS_REVIEW", f"{other} requested changes on your PR #{p.number} ({p.title})",
                       pr=p.number, branch=p.head)
    for p in mine:
        v = p.verdicts.get(other)
        if (v and v["verdict"] == "lgtm" and _after(v["at"], p.last_commit_at)
                and p.ci == "success" and p.mergeable == "MERGEABLE"):
            return act("MERGE", f"Your PR #{p.number} ({p.title}) is approved by {other} with green CI",
                       pr=p.number, branch=p.head, contract=p.contract)
    for p in theirs:
        v = p.verdicts.get(me)
        if not v or not _after(v["at"], p.last_commit_at):
            return act("REVIEW", f"{other}'s PR #{p.number} ({p.title}) needs your review",
                       pr=p.number, branch=p.head, contract=p.contract)

    rfcs = [t for t in tasks.values() if "RFC" in t["id"] and t.get("to") == lane and t.get("status") == "todo"]
    if rfcs:
        t = sorted(rfcs, key=lambda t: t["id"])[0]
        return act("RFC", f"RFC {t['id']} from {other} is addressed to you: {t['title']}", task=t["id"])

    open_heads = {p.head for p in prs}
    prefix = f"origin/{me}/"
    for b in state["branches"]:
        if b.startswith(prefix) and b[len("origin/"):] not in open_heads:
            m = re.match(rf"origin/{me}/([AB]-\d{{3}})", b)
            return act("CONTINUE_BRANCH", f"Your branch {b[len('origin/'):]} has unfinished work and no PR",
                       branch=b[len("origin/"):], task=m.group(1) if m else None)

    lane_tasks = sorted((t for t in tasks.values() if t.get("lane") == lane and "RFC" not in t["id"]),
                        key=lambda t: (t["milestone"], t["id"]))
    for t in lane_tasks:
        if t.get("status") == "in-progress":
            return act("CONTINUE_TASK", f"Task {t['id']} is in progress: {t['title']}", task=t["id"])

    if len(mine) >= MAX_OPEN_PRS_BEFORE_WAIT:
        return act("WAIT", f"{len(mine)} of your PRs are waiting for review; not starting more work",
                   waiting=[f"PR #{p.number}" for p in mine])

    in_flight = {m.group(1) for b in state["branches"] if (m := re.search(r"([AB]-\d{3})", b))}
    def ready(t: dict) -> bool:
        return (t.get("status") == "todo" and t["id"] not in in_flight
                and all(tasks.get(d, {}).get("status") == "done" for d in t.get("needs", [])))
    unfinished = [t for t in lane_tasks if t.get("status") != "done"]
    current = min((t["milestone"] for t in unfinished), default=None)
    ready_now = [t for t in lane_tasks if ready(t)]
    on_schedule = [t for t in ready_now if t["milestone"] == current]
    pick = on_schedule[0] if on_schedule else (ready_now[0] if (ready_now and work_ahead) else None)
    if pick:
        ahead = pick["milestone"] != current
        return act("START_TASK", f"Start {pick['id']} [{pick['milestone']}]: {pick['title']}"
                   + (" (ahead of schedule; current milestone is blocked)" if ahead else ""),
                   task=pick["id"], ahead=ahead)

    blockers = []
    for t in unfinished:
        if t["milestone"] != current:
            continue
        if t.get("status") == "blocked":
            blockers.append(f"{t['id']} blocked (see its Log)")
        else:
            missing = [d for d in t.get("needs", []) if tasks.get(d, {}).get("status") != "done"]
            if missing:
                blockers.append(f"{t['id']} needs {', '.join(missing)}")
    return act("WAIT", "Nothing actionable for this lane right now", waiting=blockers)


def user_queue(state: dict) -> list[str]:
    """Things only the user can do, for status.md and --user."""
    tasks = state["tasks"]
    out = []
    for t in sorted(tasks.values(), key=lambda t: (t["milestone"], t["id"])):
        ready = t.get("status") == "todo" and all(tasks.get(d, {}).get("status") == "done" for d in t.get("needs", []))
        if t.get("lane") == "X" and ready:
            out.append(f"{t['id']}: {t['title']}")
        if t.get("status") == "blocked":
            out.append(f"{t['id']} is blocked — see tasks/{t['id']}.md Log")
    return out


# --------------------------------------------------------------------------------------------
# Instructions handed to the agent
# --------------------------------------------------------------------------------------------

def prompt_for(action: dict) -> str:
    lane = action["lane"]
    me, other = AGENT[lane], AGENT[OTHER[lane]]
    clone = CLONES[lane]
    sign_lgtm, sign_changes = marker(me, "lgtm"), marker(me, "changes")
    pr, branch, task = action.get("pr"), action.get("branch"), action.get("task")
    checks = "; ".join(f"`{c}`" for c in CHECKS)
    locks = ", ".join(f"{f} (take origin/main's and run `{cmd}`)" for f, cmd in LOCKFILES.items())
    steps = {
        "FIX_CI": f"CI fails on your PR #{pr} (branch {branch}). `gh pr checks {pr}` and "
                  f"`gh run view --log-failed` show why. Check out the branch, fix it, run the checks "
                  f"locally, push, and reply on the PR with what you fixed.",
        "REBASE": f"Your PR #{pr} (branch {branch}) conflicts with main. `git fetch`, rebase it onto "
                  f"origin/main, resolve conflicts (never hand-merge lock files: {locks or 'none'}), "
                  f"run the checks, `git push --force-with-lease`, and comment on the PR.",
        "ADDRESS_REVIEW": f"{other} requested changes on your PR #{pr} (branch {branch}). Read every "
                          f"finding: `gh pr view {pr} --comments`. Fix each blocking one on the same branch, "
                          f"run the checks, push, then reply on the PR listing what you changed per finding "
                          f"(start the reply with `{marker(me, 'comment')}`). If you "
                          f"disagree with a finding, say why in the reply instead of changing it.",
        "MERGE": f"Your PR #{pr} is approved by {other} and CI is green. Merge it: "
                 f"`gh pr merge {pr} --squash --delete-branch`. Contract PRs may be merged this way "
                 f"while the user is away (user decision, AGENTS.md §13). After merging: if the task "
                 f"file on main still isn't `done`, fix that in your next PR.",
        "REVIEW": f"Review {other}'s PR #{pr} (branch {branch}). `gh pr diff {pr}`, `gh pr view {pr} "
                  f"--comments`. Check it out in a temporary worktree outside your clone if you need to "
                  f"run it, and **run the tests**; don't trust claims you haven't reproduced. Review for "
                  f"correctness, security (this is a crypto/safety app), contract breakage and missing "
                  f"tests, not style. "
                  f"Post ONE comment with `gh pr comment {pr}`: its first line must be `{sign_lgtm}` "
                  f"if it can merge as is, or `{sign_changes}` followed by numbered blocking findings. "
                  f"Non-blocking suggestions can accompany an LGTM.",
        "RFC": f"{other} filed RFC {task} addressed to your lane: read tasks/{task}.md on origin/main. "
               f"Implement it in your own PR (or a contract PR) or explain on a PR why not, and reference "
               f"the RFC id.",
        "CONTINUE_BRANCH": f"Your branch {branch} has pushed work but no PR. Check it out, finish the "
                           f"task ({task or 'see the branch'}), make everything green, and open the PR.",
        "CONTINUE_TASK": f"Task {task} is marked in-progress on main. Find or create its branch, finish it, "
                         f"and open the PR.",
        "START_TASK": f"Start task {task}: read tasks/{task}.md and the sections of docs/PLAN.md and "
                      f"REVIEW.md it cites. "
                      f"Branch `{me}/{task}-<slug>` from origin/main (use `contract/{me}-<slug>` if you must "
                      f"change contract files). Do the work with tests, meet its Done-when, set its status "
                      f"to `done` with evidence in the task file *in the same PR* (it only takes effect "
                      f"when the PR merges), push, and open the PR. If the task is bigger than ~2 hours of "
                      f"work, split it: create new task files in your lane and deliver the first slice."
                      + (" You are working ahead of schedule because the current milestone is blocked on "
                         "the user; that is allowed (AGENTS.md §13)." if action.get("ahead") else ""),
    }
    body = steps.get(action["kind"], action["summary"])
    return (
        f"{PROJECT.upper()} AUTOPILOT — Lane {lane} ({me}). The user is away; nobody will answer questions or "
        f"approve anything. Work only in {clone}. Follow AGENTS.md, including §13 Autopilot.\n\n"
        f"YOUR ONE ACTION NOW: {action['summary']}.\n{body}\n\n"
        f"The checks: {checks}.\n"
        f"Rules: never push to main, to {other}'s branches, or with plain --force. Never edit files "
        f"your lane doesn't own (`python tools/check_owners.py --who <path>`); file an RFC instead. "
        f"If something needs the user (installs needing admin, paid accounts, legal, server deploys, "
        f"physical devices), record it in the task Log / PR and move on. If a tool call is denied by "
        f"the guard, read the reason and choose a different approach; never try to bypass it. Keep "
        f"changes small and tested. When the action is done, end with a 3-line summary: what you did, "
        f"links, what's next."
    )


# --------------------------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------------------------

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lane", choices=["A", "B"])
    ap.add_argument("--json", action="store_true")
    ap.add_argument("--wait", type=int, default=0, help="block up to N seconds until there is work")
    ap.add_argument("--user", action="store_true", help="list what is waiting on the user")
    ap.add_argument("--no-work-ahead", action="store_true")
    args = ap.parse_args()

    if args.user:
        state = collect("A")
        for item in user_queue(state) or ["nothing"]:
            print("-", item)
        return 0
    if not args.lane:
        ap.error("--lane is required")

    deadline = time.time() + args.wait
    while True:
        on, why = autopilot_on()
        if args.wait and not on:
            action = {"kind": "STOP", "lane": args.lane, "summary": f"Autopilot is off ({why}); stop now"}
            break
        action = decide(collect(args.lane), args.lane, work_ahead=not args.no_work_ahead)
        if action["kind"] != "WAIT" or time.time() >= deadline:
            break
        time.sleep(min(60, max(1, deadline - time.time())))

    if action["kind"] not in ("WAIT", "STOP"):
        action["prompt"] = prompt_for(action)
    if args.json:
        print(json.dumps(action, indent=2))
    else:
        print(f"[{action['kind']}] {action['summary']}")
        for w in action.get("waiting", []):
            print(f"  - {w}")
        if "prompt" in action:
            print("\n" + action["prompt"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
