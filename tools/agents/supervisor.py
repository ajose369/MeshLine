#!/usr/bin/env python3
"""Autopilot supervisor: keeps both agents working while the user is away (AGENTS.md §13).

Start it from a terminal (the launcher `<products>\\<launcher>` from autopilot.toml does this;
for MeshLine `C:\\THIS PC\\Products\\MeshLine-Autopilot.cmd`):
    python tools/agents/supervisor.py              # run until stopped
    python tools/agents/supervisor.py --dry-run    # one cycle: decide + write status.md, launch nothing
It runs until you close the terminal, press Ctrl+C, or create the STOP file in the state folder.
Starting it again resumes where things are; all state lives in GitHub and the task files.

Each cycle:
  - writes a heartbeat: the on/off switch for both agents. Gemini's Stop hook (if Antigravity ever
    calls it) and `next_action.py --wait` keep Gemini working only while the heartbeat is fresh;
  - Lane A (Claude): if nothing is running, asks next_action.py what to do and, if there is
    work, runs ONE fresh headless Claude Code session for it (no permission prompts; every
    command still passes the guard hook). Fresh sessions mean context never piles up;
  - Lane B (Gemini): runs inside Antigravity, so here it is only monitored. If Gemini has work
    but its branches/PRs have not moved for a while, status.md says how to kick it;
  - writes status.md: what each lane is doing, recent runs, and what is waiting on the user.
Safety: at most MAX_RUNS_PER_DAY runs per lane; 3 failed runs in a row pauses the lane for 30 min;
the same action dispatched 3 times in a row without progress pauses the lane for an hour; a run
that hits the Claude usage limit waits for the limit's reset time instead of counting as a failure.
"""
from __future__ import annotations

import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import (CLONES, ENV, HEARTBEAT, LAUNCHER, PROJECT, RUNTIME, STATE, STOP_FILE,  # noqa: E402
                    git)
from next_action import collect, decide, prompt_for, user_queue  # noqa: E402
from setup_clone import configure  # noqa: E402

POLL_SECONDS = 60
RUN_TIMEOUT = 150 * 60
MAX_RUNS_PER_DAY = 40
MAX_TURNS = 200
GEMINI_IDLE_WARN = 45 * 60
LOGS = STATE / "logs"
RUNS = STATE / "runs.jsonl"
STATUS = STATE / "status.md"
LOCK = STATE / "supervisor.lock"
CURRENT = STATE / "current-run.json"
CLAUDE_SETTINGS = STATE / "claude-autopilot-settings.json"
GEMINI_HOOK_LOG = LOGS / "antigravity-hook.log"


def say(msg: str) -> None:
    print(f"{datetime.now():%H:%M:%S}  {msg}", flush=True)


def find_claude() -> str | None:
    if os.environ.get(f"{ENV}_CLAUDE"):
        return os.environ[f"{ENV}_CLAUDE"]
    found = shutil.which("claude")
    if found:
        return found
    home = Path.home()
    candidates = []
    for pattern in (".antigravity-ide/extensions/anthropic.claude-code-*/resources/native-binary/claude.exe",
                    ".vscode/extensions/anthropic.claude-code-*/resources/native-binary/claude.exe"):
        candidates += glob.glob(str(home / pattern))
    return max(candidates, key=os.path.getmtime) if candidates else None


def pid_alive(pid: int) -> bool:
    out = subprocess.run(["tasklist", "/FI", f"PID eq {pid}", "/NH"], capture_output=True, text=True).stdout
    return str(pid) in out


def kill_tree(pid: int) -> None:
    subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)], capture_output=True)


def take_lock() -> bool:
    if LOCK.exists():
        try:
            pid = int(LOCK.read_text().strip())
            if pid != os.getpid() and pid_alive(pid):
                say(f"Another supervisor is already running (PID {pid}). Close it first.")
                return False
        except ValueError:
            pass
    LOCK.write_text(str(os.getpid()))
    return True


def write_claude_settings() -> None:
    hook = str(RUNTIME / "tools" / "agents" / "claude_hook.py").replace("\\", "/")
    CLAUDE_SETTINGS.write_text(json.dumps({"hooks": {"PreToolUse": [{
        "matcher": "Bash|PowerShell|Edit|Write|MultiEdit|NotebookEdit",
        "hooks": [{"type": "command", "command": f'python "{hook}"', "timeout": 60}],
    }]}}, indent=2), encoding="utf-8")


class LaneA:
    def __init__(self, claude: str | None):
        self.claude = claude
        self.proc: subprocess.Popen | None = None
        self.action: dict | None = None
        self.started = 0.0
        self.log_path: Path | None = None
        self.fails = 0
        self.paused_until = 0.0
        self.pause_reason = ""
        self.recent: list[str] = []

    def runs_today(self) -> int:
        if not RUNS.exists():
            return 0
        today = datetime.now().strftime("%Y-%m-%d")
        return sum(1 for line in RUNS.read_text(encoding="utf-8").splitlines()
                   if '"lane": "A"' in line and f'"start": "{today}' in line
                   and '"rate_limited": true' not in line)

    def tick(self, action: dict | None) -> None:
        if self.proc is not None:
            code = self.proc.poll()
            elapsed = time.time() - self.started
            if code is None and elapsed > RUN_TIMEOUT:
                say(f"Lane A run exceeded {RUN_TIMEOUT // 60} min; stopping it.")
                kill_tree(self.proc.pid)
                code = -1
            if code is None:
                return
            self.finish(code)
        if time.time() < self.paused_until or action is None or action["kind"] == "WAIT":
            return
        if not self.claude:
            self.pause_reason = f"claude.exe not found (set {ENV}_CLAUDE to its path)"
            return
        if self.runs_today() >= MAX_RUNS_PER_DAY:
            self.pause_reason = f"daily limit of {MAX_RUNS_PER_DAY} runs reached"
            return
        key = f"{action['kind']}:{action.get('pr') or action.get('task') or ''}"
        if self.recent[-3:] == [key] * 3:
            self.paused_until = time.time() + 3600
            self.recent.clear()  # otherwise the lane re-pauses forever when the hour ends
            self.pause_reason = f"same action ({key}) dispatched 3 times without progress; paused 1h"
            say(self.pause_reason)
            return
        self.start(action, key)

    def start(self, action: dict, key: str) -> None:
        clone = CLONES["A"]
        dirty = git(clone, "status", "--porcelain").strip()
        prompt = prompt_for(action)
        if dirty:
            prompt += ("\n\nNOTE: your clone has uncommitted changes from an interrupted earlier run. "
                       "Inspect them first: commit them to the branch they belong to, or discard them "
                       "if they are obsolete.")
        stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
        self.log_path = LOGS / f"A-{stamp}-{action['kind']}.jsonl"
        cmd = [self.claude, "-p", prompt, "--permission-mode", "bypassPermissions",
               "--settings", str(CLAUDE_SETTINGS), "--max-turns", str(MAX_TURNS),
               "--output-format", "stream-json", "--verbose"]
        env = dict(os.environ, **{f"{ENV}_LANE": "A"})
        with open(self.log_path, "w", encoding="utf-8") as out:
            self.proc = subprocess.Popen(cmd, cwd=clone, stdout=out, stderr=subprocess.STDOUT, env=env,
                                         creationflags=getattr(subprocess, "CREATE_NEW_PROCESS_GROUP", 0))
        self.action, self.started, self.pause_reason = action, time.time(), ""
        self.recent = (self.recent + [key])[-6:]
        CURRENT.write_text(json.dumps({"pid": self.proc.pid, "action": action["summary"]}), encoding="utf-8")
        say(f"Lane A → {action['kind']}: {action['summary']}  (log {self.log_path.name})")

    def finish(self, code: int) -> None:
        minutes = (time.time() - self.started) / 60
        reset = rate_limit_reset(self.log_path) if code != 0 else None
        entry = {"lane": "A", "kind": self.action["kind"], "summary": self.action["summary"],
                 "start": datetime.fromtimestamp(self.started).strftime("%Y-%m-%d %H:%M:%S"),
                 "minutes": round(minutes, 1), "exit": code, "log": str(self.log_path),
                 "rate_limited": bool(reset)}
        with RUNS.open("a", encoding="utf-8") as f:
            f.write(json.dumps(entry) + "\n")
        if reset:
            # The run never did any work, so it is not a failure and must not eat the daily
            # budget. Wait for the window the event reports instead of retrying blindly.
            self.paused_until = max(self.paused_until, reset + 60)
            self.pause_reason = (f"Claude usage limit reached; resumes "
                                 f"{datetime.fromtimestamp(reset + 60):%H:%M}")
            if self.recent:
                self.recent.pop()
            say(f"Lane A hit the Claude usage limit; waiting until "
                f"{datetime.fromtimestamp(reset + 60):%H:%M}. Gemini is unaffected.")
            self.proc, self.action = None, None
            CURRENT.unlink(missing_ok=True)
            return
        say(f"Lane A run finished (exit {code}, {minutes:.0f} min).")
        self.fails = 0 if code == 0 else self.fails + 1
        if self.fails >= 3:
            self.paused_until = time.time() + 1800
            self.pause_reason = "3 failed runs in a row; paused 30 min (see logs)"
            say(self.pause_reason)
        self.proc, self.action = None, None
        CURRENT.unlink(missing_ok=True)


def rate_limit_reset(log_path: Path | None) -> float | None:
    """When a run died on a Claude usage limit, the epoch second it resets; otherwise None.

    A headless run that hits the limit emits a `rate_limit_event` carrying `resetsAt` and ends
    with "You've hit your session limit". It did no work, so the lane should wait for the window
    rather than count a failure and retry blindly.
    """
    if not log_path or not log_path.exists():
        return None
    try:
        text = log_path.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return None
    if not any(k in text for k in ("rate_limit_event", "session limit", "usage limit")):
        return None
    # The event carries several windows (five-hour, weekly, per-model). The one that matters is
    # the rejected limit's own resetsAt; anything else would park the lane for hours too long.
    for line in text.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            continue
        if event.get("type") == "rate_limit_event":
            at = (event.get("rate_limit_info") or {}).get("resetsAt")
            if isinstance(at, (int, float)) and at > 0:
                return min(float(at), time.time() + 6 * 3600)
    # No usable event: fall back to the soonest future timestamp mentioned, else half an hour.
    future = [int(m) for m in re.findall(r'"resetsAt"\s*:\s*(\d{9,12})', text) if int(m) > time.time()]
    return min(min(future), time.time() + 6 * 3600) if future else time.time() + 1800


def gemini_last_activity(state_b: dict | None = None) -> float:
    """Newest sign of life from Lane B, as an epoch second (0 = never).

    Antigravity does not call the hooks (AGENTS.md §13, known limitation), so the hook log alone
    would say "never". Gemini's pushes are visible, though: the newest commit on any
    origin/gemini/* or origin/contract/gemini-* branch, or on an open Lane B PR, also counts.
    """
    times = []
    try:
        times.append(GEMINI_HOOK_LOG.stat().st_mtime)
    except OSError:
        pass
    try:
        out = git(CLONES["B"], "for-each-ref", "--format=%(refname:short) %(committerdate:unix)",
                  "refs/remotes/origin/gemini/", "refs/remotes/origin/contract/")
        for line in out.splitlines():
            ref, _, ts = line.partition(" ")
            if ref.startswith(("origin/gemini/", "origin/contract/gemini-")) and ts.strip():
                times.append(float(ts))
    except Exception:
        pass
    for p in (state_b or {}).get("prs", []):
        if p.lane == "B" and p.last_commit_at:
            times.append(datetime.strptime(p.last_commit_at, "%Y-%m-%dT%H:%M:%SZ")
                         .replace(tzinfo=timezone.utc).timestamp())
    return max(times, default=0.0)


def write_status(lane_a: LaneA, actions: dict, queue: list[str], state_b: dict | None = None) -> None:
    now = datetime.now()
    lines = [f"# {PROJECT} autopilot status", f"Updated {now:%Y-%m-%d %H:%M:%S}. Supervisor PID {os.getpid()}.", ""]
    a = actions.get("A")
    lines.append("## Lane A — Claude")
    if lane_a.proc:
        lines.append(f"- **Running:** {lane_a.action['summary']} (since {datetime.fromtimestamp(lane_a.started):%H:%M})")
    elif time.time() < lane_a.paused_until:
        lines.append(f"- **Paused:** {lane_a.pause_reason}")
    elif lane_a.pause_reason and a and a["kind"] != "WAIT":
        lines.append(f"- **Not starting work:** {lane_a.pause_reason}")
    elif a:
        lines.append(f"- Next: [{a['kind']}] {a['summary']}")
        lines += [f"  - {w}" for w in a.get("waiting", [])]
    b = actions.get("B")
    last = gemini_last_activity(state_b)
    idle = time.time() - last if last else float("inf")
    lines += ["", "## Lane B — Gemini (runs inside Antigravity; semi-attended, AGENTS.md §13)"]
    if b:
        lines.append(f"- Next: [{b['kind']}] {b['summary']}")
        lines += [f"  - {w}" for w in b.get("waiting", [])]
    lines.append(f"- Last Gemini activity (push or hook): "
                 f"{'never' if idle == float('inf') else f'{int(idle // 60)} min ago'}")
    if b and b["kind"] != "WAIT" and idle > GEMINI_IDLE_WARN:
        lines.append(f"- ⚠️ Gemini has work but looks idle. In Antigravity ({CLONES['B'].name} window) "
                     f"tell Gemini: **start autopilot** (and approve its steps; the hooks are not "
                     f"called by Antigravity).")
    lines += ["", "## Waiting on you"] + [f"- {q}" for q in (queue or ["nothing"])]
    lines += ["", "## Recent runs"]
    if RUNS.exists():
        for line in RUNS.read_text(encoding="utf-8").splitlines()[-8:]:
            r = json.loads(line)
            lines.append(f"- {r['start']} Lane {r['lane']} {r['kind']} → exit {r['exit']} ({r['minutes']} min): {r['summary']}")
    lines += ["", f"Stop: close the supervisor terminal or create `{STOP_FILE}`. "
              f"Restart: double-click `{LAUNCHER}`, then tell Gemini `start autopilot` in Antigravity."]
    STATUS.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    dry_run = "--dry-run" in sys.argv  # one cycle: configure, decide, write status; launch nothing
    for d in (STATE, LOGS):
        d.mkdir(parents=True, exist_ok=True)
    if not take_lock():
        return 1
    STOP_FILE.unlink(missing_ok=True)
    if CURRENT.exists():  # a run orphaned by a closed terminal: stop it before starting fresh
        try:
            old = json.loads(CURRENT.read_text(encoding="utf-8"))
            if pid_alive(int(old["pid"])):
                say(f"Stopping orphaned Lane A run from last session (PID {old['pid']}).")
                kill_tree(int(old["pid"]))
        except (ValueError, KeyError, OSError):
            pass
        CURRENT.unlink(missing_ok=True)
    for lane, clone in CLONES.items():
        if clone.exists():
            configure(lane, clone)
    write_claude_settings()
    claude = find_claude()
    say(f"{PROJECT} autopilot started. Claude: {claude or 'NOT FOUND'}. State: {STATE}")
    say(f"Stop: close this window, Ctrl+C, or create {STOP_FILE}. Gemini: tell it 'start autopilot'.")
    lane_a = LaneA(claude)
    last_state_err = ""
    try:
        while True:
            HEARTBEAT.write_text(json.dumps({"time": time.time(), "pid": os.getpid()}), encoding="utf-8")
            if STOP_FILE.exists():
                say("STOP file found; finishing.")
                break
            actions, queue, state_b = {}, [], None
            try:
                state_a = collect("A")
                state_b = collect("B")
                actions["A"] = decide(state_a, "A")
                actions["B"] = decide(state_b, "B")
                queue = user_queue(state_a)
                last_state_err = ""
            except Exception as e:  # GitHub/network hiccup: keep going, try next cycle
                if str(e) != last_state_err:
                    say(f"Could not read state ({e}); retrying.")
                last_state_err = str(e)
            if dry_run:
                for lane, act in actions.items():
                    say(f"[dry-run] Lane {lane}: [{act['kind']}] {act['summary']}")
                write_status(lane_a, actions, queue, state_b)
                say(f"[dry-run] wrote {STATUS}")
                break
            lane_a.tick(actions.get("A"))
            write_status(lane_a, actions, queue, state_b)
            time.sleep(POLL_SECONDS)
    except KeyboardInterrupt:
        say("Ctrl+C: stopping.")
    finally:
        HEARTBEAT.unlink(missing_ok=True)
        LOCK.unlink(missing_ok=True)
        if lane_a.proc and lane_a.proc.poll() is None:
            say("A Lane A run is still active; it will finish its current action on its own.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
