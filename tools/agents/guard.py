"""Tool-call policy for unattended agents (AGENTS.md §13). Used by both agents' PreToolUse hooks
(`antigravity_hook.py` for Gemini, `claude_hook.py` for Claude).

The agents run without anyone to approve prompts, so every call gets a hard answer:
  allow  — routine work inside the agent's own clone (build, test, git on its own branches, gh).
  deny   — anything that could damage shared state or the machine, with a reason the agent can act
           on. The pre-push git hook and CI enforce the same rules again, so nothing relies on this
           filter alone.
Calls outside a configured lane clone get no decision (`None`) and fall back to the tool's
normal behaviour, so the guard never changes how other projects work.
"""
from __future__ import annotations

import ntpath
import os
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import AGENT, CLONES, ENV, INTEGRATION, LANE_KEY, OTHER, STATE, is_within, lane_of  # noqa: E402

TEMP_DIRS = [Path(os.environ.get("TEMP", r"C:\Windows\Temp")), Path.home() / ".gemini",
             Path.home() / ".claude", STATE]

_I = re.I
DENY_COMMANDS: list[tuple[re.Pattern, str]] = [
    (re.compile(r"\bgit\b[^\n|;&]*\bpush\b[^\n|;&]*(?:\s|:|refs/heads/)(?:main|master)\b", _I),
     "Never push to main. Push your own branch and open a PR."),
    (re.compile(r"\bgit\b[^\n|;&]*\bpush\b[^\n|;&]*\s--force(?!-with-lease)\b|\bgit\b[^\n|;&]*\bpush\b[^\n|;&]*\s-f\b", _I),
     "Plain force-push is not allowed. Use `git push --force-with-lease` on your own branch."),
    (re.compile(r"--no-verify\b", _I), "Skipping git hooks is not allowed; fix what the hook reports."),
    (re.compile(rf"\bgit\b[^\n|;&]*\bconfig\b[^\n|;&]*(?:--global|--system|core\.hookspath|{re.escape(LANE_KEY)})", _I),
     "Changing global git config, hooks path or lane identity is not allowed."),
    (re.compile(r"\brustup\s+default\b", _I),
     "Don't change the machine-wide Rust toolchain; use `cargo +<toolchain>` or rust-toolchain.toml."),
    (re.compile(r"\b(?:winget|choco|scoop|msiexec|sdkmanager)\b|Install-Module|Set-ExecutionPolicy"
                r"|\breg\s+(?:add|delete)\b|\bsetx\b|-Verb\s+RunAs|\bnpm\s+(?:i|install)\s+(?:-g|--global)\b"
                r"|\bcargo\s+install\b", _I),
     "System-wide installs/changes need the user. Record what's needed in the task Log and continue "
     "with other work."),
    (re.compile(r"\b(?:ssh|scp|sftp|rsync)\b", _I),
     "Server access (SSH/SCP) waits for the user. Prepare the change in a PR and note the deploy step."),
    (re.compile(r"\badb\b[^\n|;&]*\b(?:install|uninstall|shell\s+pm\s+clear)\b", _I),
     "Installing to or wiping a connected phone waits for the user (it may be their own device). "
     "Note the on-device check in the task Log."),
    (re.compile(r"\bgh\s+(?:repo\s+(?:delete|edit|rename|archive)|secret\b|auth\s+(?:logout|refresh)|api\b[^\n]*-X\s*(?:DELETE|PUT|PATCH))", _I),
     "Changing repository settings, secrets or auth is not allowed."),
    (re.compile(r"\b(?:shutdown|diskpart|bcdedit)\b|\bformat\s+[a-z]:|\bcipher\s+/w\b", _I), "Not allowed."),
]

RECURSIVE_DELETE = re.compile(r"\brm\s+-[a-z]*r[a-z]*f?|\brm\s+-[a-z]*f[a-z]*r|Remove-Item\b[^\n]*-Recurse"
                              r"|\brmdir\s+/s\b|\brd\s+/s\b|\bdel\s+/s\b|shutil\.rmtree", _I)
ABS_PATH = re.compile(r"[A-Za-z]:[\\/][^\s\"'`;|&]*|/[a-z]/[^\s\"'`;|&]*")
QUOTED_ABS_PATH = re.compile(r"[\"']((?:[A-Za-z]:[\\/]|/[a-z]/)[^\"']*)[\"']")  # paths with spaces


def _norm(p: str) -> Path:
    m = re.match(r"^/([a-zA-Z])/(.*)$", p)      # Git-bash style /c/THIS PC/... -> C:/THIS PC/...
    if m:
        p = f"{m.group(1)}:/{m.group(2)}"
    return Path(p)


def _mentions(cmd: str, root: Path) -> bool:
    """True if `cmd` names `root` as a path (either slash style, any case), not a longer sibling."""
    parts = [re.escape(x) for x in str(root).replace("\\", "/").split("/") if x]
    drive, rest = parts[0].rstrip(":").rstrip("\\:"), parts[1:]
    body = r"[\\/]+".join(rest)
    pat = rf"(?:{drive}:|/{drive.lower()})[\\/]+{body}(?![\w-])"
    return re.search(pat, cmd, re.I) is not None


def _merge_allowed(cmd: str, lane: str) -> tuple[bool, str]:
    m = re.search(r"\bgh\s+pr\s+merge\s+(\d+)", cmd, re.I)
    if not m:
        return False, "Merge PRs with `gh pr merge <number> --squash --delete-branch`."
    number = int(m.group(1))
    try:
        from next_action import collect  # local import: needs git/gh
        pr = next((p for p in collect(lane)["prs"] if p.number == number), None)
    except Exception as e:  # pragma: no cover - network failure
        return False, f"Could not verify PR #{number} ({e}); try again later."
    if pr is None:
        return False, f"PR #{number} is not open."
    if pr.lane != lane:
        return False, f"PR #{number} belongs to the other lane; only its author merges it."
    v = pr.verdicts.get(AGENT[OTHER[lane]])
    if not (v and v["verdict"] == "lgtm" and v["at"] > pr.last_commit_at):
        return False, f"PR #{number} has no LGTM from {AGENT[OTHER[lane]]} after its last commit."
    if pr.ci != "success":
        return False, f"PR #{number} CI is {pr.ci}; wait for green."
    return True, "approved by the other agent after the last commit, CI green"


def _lane(lane: str | None, cwd: str | None) -> str | None:
    """Who is acting. The caller (hook) knows the agent's lane; the folder a command points at
    must never decide it, or an agent working in the other clone would be judged as that lane."""
    return lane or os.environ.get(f"{ENV}_LANE") or lane_of(cwd)


def _abs_paths(cmd: str) -> list[str]:
    quoted = QUOTED_ABS_PATH.findall(cmd)
    rest = QUOTED_ABS_PATH.sub(" ", cmd)
    return quoted + ABS_PATH.findall(rest)


def check_command(cmd: str, cwd: str | None, lane: str | None = None) -> tuple[str | None, str]:
    """Decision for a shell command: ('allow'|'deny', reason), or (None, why) when not a lane clone."""
    lane = _lane(lane, cwd)
    if lane not in ("A", "B"):
        return None, "not running in a lane clone"
    own = CLONES[lane]
    if cwd and not is_within(cwd, own) and not any(is_within(cwd, t) for t in TEMP_DIRS):
        return "deny", f"Run commands inside your clone {own}."
    for root in (CLONES[OTHER[lane]], INTEGRATION):
        if _mentions(cmd, root):
            return "deny", f"{root} belongs to someone else. Work only in {own}."
    for other_prefix in (f"{AGENT[OTHER[lane]]}/", f"contract/{AGENT[OTHER[lane]]}-"):
        if re.search(rf"\bgit\b[^\n|;&]*\bpush\b[^\n|;&]*{re.escape(other_prefix)}", cmd, re.I):
            return "deny", f"Never push to {AGENT[OTHER[lane]]}'s branches."
    for pattern, reason in DENY_COMMANDS:
        if pattern.search(cmd):
            return "deny", reason
    if re.search(r"\bgh\s+pr\s+merge\b", cmd, re.I):
        ok, why = _merge_allowed(cmd, lane)
        return ("allow" if ok else "deny"), why
    if RECURSIVE_DELETE.search(cmd):
        for raw in _abs_paths(cmd):
            p = _norm(raw)
            if not is_within(p, own) and not any(is_within(p, t) for t in TEMP_DIRS):
                return "deny", f"Recursive delete outside your clone ({raw}) is not allowed."
    return "allow", "routine command in own clone"


def check_write(path: str, cwd: str | None = None, lane: str | None = None) -> tuple[str | None, str]:
    """Decision for a file write/edit by an editor tool."""
    target = path if ntpath.isabs(path) else ntpath.join(cwd or os.getcwd(), path)  # Windows semantics on any OS
    lane = _lane(lane, cwd)
    if lane not in ("A", "B"):
        return None, "not a lane clone"
    own = CLONES[lane]
    for root in (CLONES[OTHER[lane]], INTEGRATION):
        if is_within(target, root):
            return "deny", f"{root} is not your clone. Work only in {own}."
    if not is_within(target, own):
        return ("allow", "scratch/temp file") if any(is_within(target, t) for t in TEMP_DIRS) else \
               ("deny", f"Write inside your clone {own} or a temp folder.")
    import check_owners
    rel = ntpath.relpath(target, str(own)).replace("\\", "/")
    owner = check_owners.owner_of(rel, check_owners.load_rules())
    if owner in (lane, "any", "contract"):
        return "allow", f"{rel} is owned by {owner}"
    return "deny", (f"{rel} is owned by {'the user' if owner == 'X' else 'lane ' + owner}. "
                    f"File an RFC task instead of editing it (AGENTS.md §6.3).")
