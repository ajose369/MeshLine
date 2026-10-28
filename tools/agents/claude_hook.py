#!/usr/bin/env python3
"""Claude Code PreToolUse hook for Lane A (wired up by the supervisor's `--settings` file,
`<state>/claude-autopilot-settings.json`).

Headless autopilot runs use `--permission-mode bypassPermissions`, so this hook is what keeps
them inside the rules: every shell command and file edit goes through guard.py. Outside a lane
clone (e.g. the user's own sessions in the integration folder) it prints nothing, which
leaves Claude Code's normal permission flow untouched.
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))


def main() -> int:
    try:
        payload = json.loads(sys.stdin.read() or "{}")
        import guard
        from common import ENV
        tool, args, cwd = payload.get("tool_name", ""), payload.get("tool_input") or {}, payload.get("cwd")
        lane = os.environ.get(f"{ENV}_LANE")  # set by the supervisor for autopilot runs
        if tool in ("Bash", "PowerShell"):
            decision, reason = guard.check_command(args.get("command", ""), cwd, lane)
        elif tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
            decision, reason = guard.check_write(args.get("file_path") or args.get("notebook_path") or "", cwd, lane)
        else:
            return 0
    except Exception as e:  # never break the session because of the guard itself
        sys.stderr.write(f"autopilot guard error: {e}\n")
        return 0
    if decision is None:
        return 0
    sys.stdout.write(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": decision,
        "permissionDecisionReason": reason,
    }}))
    return 0


if __name__ == "__main__":
    sys.exit(main())
