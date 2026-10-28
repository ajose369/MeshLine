#!/usr/bin/env python3
"""Antigravity lifecycle hooks for Gemini (Lane B). Wired up in `.agents/hooks.json`.

  pretool  PreToolUse: allow/deny each command or file write via guard.py, so Gemini never
           waits for a human approval, and dangerous calls are blocked with a reason.
  stop     Stop: while the autopilot is on, refuse to stop and hand Gemini its next action
           (next_action.py). Every CONTEXT_RENEW continues, ask Gemini to start a fresh
           conversation with `agentapi` so its context never fills up.

KNOWN LIMITATION (AGENTS.md §13): in the Rekha project (tested 2026-09-23) Antigravity never
invoked these hooks, from either `.agents/hooks.json` or the global hooks file. They are kept so
they work if Antigravity starts honouring them; until then Lane B is semi-attended, and the
supervisor only monitors it.

Contract: JSON on stdin, JSON on stdout (Antigravity hooks guide). Any internal error falls back
to Antigravity's normal behaviour ("ask" / let it stop) and is logged, never crashes the agent.
"""
from __future__ import annotations

import json
import sys
import time
import traceback
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import CLONES, PROJECT, STATE, autopilot_on, lane_of  # noqa: E402

CONTEXT_RENEW = 15          # continues per conversation before handing off to a fresh one
IDLE_POLL_SECONDS = 200     # in-hook wait for work before telling the agent to wait itself (< hook timeout)
LOG = STATE / "logs" / "antigravity-hook.log"
COUNTS = STATE / "antigravity-conversations.json"

KICKOFF = (f"Start the {PROJECT} autopilot for Lane B: run `python tools/agents/next_action.py --lane B` "
           f"in {CLONES['B']} and do the action it prints. Follow AGENTS.md §13.")


def log(msg: str) -> None:
    try:
        LOG.parent.mkdir(parents=True, exist_ok=True)
        with LOG.open("a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}\n")
    except OSError:
        pass


def lane_for(payload: dict) -> str | None:
    for p in payload.get("workspacePaths") or []:
        lane = lane_of(p)
        if lane:
            return lane
    return lane_of(CLONES["B"])  # the hook ships in Gemini's clone


def pretool(payload: dict) -> dict:
    import guard
    call = payload.get("toolCall") or {}
    name, args = call.get("name", ""), call.get("args") or {}
    lane = lane_for(payload)
    if not name:
        # Unreadable payload: ask rather than allow something unchecked.
        log(f"no tool name in payload: {str(payload)[:200]}")
        return {"decision": "ask", "reason": "autopilot guard could not read the tool call"}
    if name == "run_command":
        decision, reason = guard.check_command(args.get("CommandLine", ""), args.get("Cwd"), lane)
    elif name in ("write_to_file", "replace_file_content", "multi_replace_file_content", "edit_file"):
        target = args.get("TargetFile") or args.get("AbsolutePath") or args.get("FilePath") or ""
        decision, reason = guard.check_write(target, (payload.get("workspacePaths") or [None])[0], lane)
    else:
        return {"decision": "allow", "reason": "read-only or internal tool"} if lane else {"decision": "ask"}
    if decision is None:
        return {"decision": "ask"}
    if decision == "deny":
        log(f"DENY {name}: {str(args)[:300]} :: {reason}")
    return {"decision": decision, "reason": reason}


def stop(payload: dict) -> dict:
    lane = lane_for(payload)
    on, why = autopilot_on()
    if lane != "B" or not on:
        return {"decision": "stop", "reason": why}
    conv = payload.get("conversationId", "?")
    try:
        counts = json.loads(COUNTS.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        counts = {}
    n = counts.get(conv, 0)
    if n < 0:  # this conversation already handed off
        return {"decision": "stop", "reason": "handed off to a fresh conversation"}
    if n >= CONTEXT_RENEW:
        counts[conv] = -1
        COUNTS.write_text(json.dumps(counts), encoding="utf-8")
        log(f"renew {conv} after {n} continues")
        return {"decision": "continue", "reason":
                "AUTOPILOT: this conversation is getting long. Start a fresh one so work continues with "
                f"a clean context: run `agentapi new-conversation --model=pro --title \"{PROJECT} Lane B "
                f"autopilot\" \"{KICKOFF}\"` from {CLONES['B']}, check that it "
                "printed a conversation id, then end your turn. (If agentapi fails, say so and "
                "end your turn; the user will restart you.)"}

    from next_action import collect, decide, prompt_for
    deadline = time.time() + IDLE_POLL_SECONDS
    while True:
        action = decide(collect("B"), "B")
        if action["kind"] != "WAIT" or time.time() >= deadline:
            break
        time.sleep(30)
    counts[conv] = n + 1
    COUNTS.parent.mkdir(parents=True, exist_ok=True)
    COUNTS.write_text(json.dumps(counts), encoding="utf-8")
    log(f"continue {conv} #{n + 1}: {action['kind']} {action['summary']}")
    if action["kind"] == "WAIT":
        return {"decision": "continue", "reason":
                "AUTOPILOT: nothing to do right now (" + "; ".join(action.get("waiting", [])[:4]) + "). "
                "Run `python tools/agents/next_action.py --lane B --wait 600` in "
                f"{CLONES['B']} (it blocks until there is work, up to 10 minutes) "
                "and do what it prints. If it prints STOP, end your turn."}
    return {"decision": "continue", "reason": prompt_for(action)}


def main() -> int:
    event = sys.argv[1] if len(sys.argv) > 1 else ""
    try:
        payload = json.loads(sys.stdin.read() or "{}")
    except ValueError:
        payload = {}
    log(f'CALLED event={event} tool={(payload.get("toolCall") or {}).get("name", "-")}')
    try:
        out = pretool(payload) if event == "pretool" else stop(payload) if event == "stop" else {}
    except Exception:
        log(f"ERROR in {event}: {traceback.format_exc()}")
        out = {"decision": "ask"} if event == "pretool" else {"decision": "stop"}
    sys.stdout.write(json.dumps(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
