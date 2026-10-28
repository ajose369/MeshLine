#!/usr/bin/env python3
"""Task board for tasks/*.md (printed, never committed).

Usage:
  python tools/board.py                 # whole board, grouped by milestone
  python tools/board.py --lane A        # only Lane A (Claude) tasks;  --lane B for Gemini, X for user
  python tools/board.py --next A        # the task Lane A should pick next
  python tools/board.py --validate      # check every task file (used in CI)

A task is READY when status is `todo` and every task in `needs` is `done`.
Tasks with a pushed remote branch (origin/claude/A-123-…, origin/gemini/B-045-…) are shown as
"in flight" even before their PR merges, so each agent can see what the other is doing.
Standard library only.
"""
from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Windows consoles default to cp1252; task titles contain Unicode (–, …, →).
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")
TASKS = ROOT / "tasks"
STATUSES = ("todo", "in-progress", "review", "done", "blocked")
LANES = {"A": "claude", "B": "gemini", "X": "user"}
ID_RE = re.compile(r"^[ABX]-(RFC-)?\d{3}$")
REQUIRED = ("id", "title", "lane", "owner", "milestone", "status", "needs")


def parse_front_matter(text: str) -> dict:
    if not text.startswith("---"):
        raise ValueError("missing front matter")
    block = text.split("---", 2)[1]
    data: dict = {}
    for line in block.strip().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, _, value = line.partition(":")
        value = value.strip()
        if value.startswith("[") and value.endswith("]"):
            data[key.strip()] = [v.strip() for v in value[1:-1].split(",") if v.strip()]
        elif value.startswith('"') and value.endswith('"'):
            data[key.strip()] = value[1:-1].replace('\\"', '"').replace("\\\\", "\\")
        else:
            data[key.strip()] = value
    return data


def load_tasks() -> tuple[dict[str, dict], list[str]]:
    tasks, errors = {}, []
    for path in sorted(TASKS.glob("*.md")):
        if path.name.startswith("_") or path.name == "README.md":
            continue
        try:
            t = parse_front_matter(path.read_text(encoding="utf-8"))
        except ValueError as e:
            errors.append(f"{path.name}: {e}")
            continue
        missing = [k for k in REQUIRED if k not in t]
        if missing:
            errors.append(f"{path.name}: missing {', '.join(missing)}")
            continue
        if path.stem != t["id"]:
            errors.append(f"{path.name}: id '{t['id']}' does not match file name")
        if not ID_RE.match(t["id"]):
            errors.append(f"{path.name}: bad id '{t['id']}'")
        if t["id"][0] != t["lane"] or LANES.get(t["lane"]) != t["owner"]:
            errors.append(f"{path.name}: lane/owner do not match id prefix")
        if t["status"] not in STATUSES:
            errors.append(f"{path.name}: bad status '{t['status']}'")
        if t["id"] in tasks:
            errors.append(f"{path.name}: duplicate id")
        tasks[t["id"]] = t
    for t in tasks.values():
        for dep in t["needs"]:
            if dep not in tasks:
                errors.append(f"{t['id']}: needs unknown task {dep}")
    return tasks, errors


def remote_in_flight() -> dict[str, str]:
    try:
        out = subprocess.run(["git", "branch", "-r"], cwd=ROOT, capture_output=True, text=True).stdout
    except OSError:
        return {}
    flight = {}
    for line in out.splitlines():
        m = re.search(r"origin/(claude|gemini)/([ABX]-(?:RFC-)?\d{3})", line)
        if m:
            flight[m.group(2)] = line.strip()
    return flight


def is_ready(t: dict, tasks: dict) -> bool:
    return t["status"] == "todo" and all(tasks[d]["status"] == "done" for d in t["needs"])


def sort_key(t: dict) -> tuple:
    return (t["milestone"], "RFC" not in t["id"], t["id"])  # RFCs first inside a milestone


def mark(t: dict, tasks: dict, flight: dict) -> str:
    if t["id"] in flight and t["status"] in ("todo", "in-progress"):
        return "IN FLIGHT"
    if t["status"] == "todo":
        return "READY" if is_ready(t, tasks) else "waiting"
    return t["status"].upper()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lane", choices=["A", "B", "X"])
    ap.add_argument("--next", choices=["A", "B"], dest="next_lane")
    ap.add_argument("--validate", action="store_true")
    args = ap.parse_args()

    tasks, errors = load_tasks()
    if args.validate:
        for e in errors:
            print("ERROR", e)
        print(f"board: {len(tasks)} tasks, {len(errors)} error(s)")
        return 1 if errors else 0
    if errors:
        print("WARNING: task files have errors — run with --validate", file=sys.stderr)

    flight = remote_in_flight()

    if args.next_lane:
        lane = args.next_lane
        rfcs = [t for t in tasks.values() if "RFC" in t["id"] and t["status"] == "todo"
                and t.get("to") == lane]
        mine = sorted((t for t in tasks.values() if t["lane"] == lane), key=sort_key)
        in_progress = [t for t in mine if t["status"] == "in-progress"]
        ready = [t for t in mine if is_ready(t, tasks) and t["id"] not in flight]
        if rfcs:
            print("Answer these RFCs addressed to you first:")
            for t in rfcs:
                print(f"  {t['id']}  {t['title']}")
        if in_progress:
            print("Already in progress (finish these first):")
            for t in in_progress:
                print(f"  {t['id']}  {t['title']}")
        # The lane's current milestone is the earliest one with unfinished work. Never silently
        # jump ahead: a ready task from a later milestone is only offered as an explicit option.
        unfinished = [t for t in mine if t["status"] != "done"]
        current_ms = min((t["milestone"] for t in unfinished), default=None)
        on_schedule = [t for t in ready if t["milestone"] == current_ms]
        if on_schedule:
            t = on_schedule[0]
            print(f"NEXT for Lane {lane}: {t['id']} [{t['milestone']}] {t['title']}")
            print(f"  file: tasks/{t['id']}.md   branch: {LANES[lane]}/{t['id']}-<slug>")
        elif current_ms is None:
            print(f"Lane {lane}: every task is done.")
        else:
            print(f"Lane {lane}: nothing ready in current milestone {current_ms}. Waiting on:")
            for t in unfinished:
                if t["milestone"] != current_ms:
                    continue
                blockers = [d for d in t["needs"] if tasks[d]["status"] != "done"]
                state = t["status"] if t["status"] != "todo" else "needs " + ", ".join(blockers)
                print(f"  {t['id']:<8} {state}")
            if ready:
                t = ready[0]
                print(f"Ahead-of-schedule option (only if the user agrees): {t['id']} [{t['milestone']}] {t['title']}")
        return 0

    rows = sorted((t for t in tasks.values() if not args.lane or t["lane"] == args.lane), key=sort_key)
    current = None
    for t in rows:
        if t["milestone"] != current:
            current = t["milestone"]
            print(f"\n== {current} " + "=" * 60)
        status = mark(t, tasks, flight)
        print(f"  {t['id']:<10} {status:<11} {t['title'][:80]}")
    done = sum(1 for t in tasks.values() if t["status"] == "done")
    print(f"\n{done}/{len(tasks)} done.  Ready now: A={sum(is_ready(t, tasks) for t in tasks.values() if t['lane']=='A')} "
          f"B={sum(is_ready(t, tasks) for t in tasks.values() if t['lane']=='B')} "
          f"X={sum(is_ready(t, tasks) for t in tasks.values() if t['lane']=='X')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
