"""Shared plumbing for the autopilot: configuration, paths, lane detection, git/gh helpers.

Everything project-specific (names, repo URL, folders, check commands) lives in
`autopilot.toml` next to this file and is exposed here as constants. With the MeshLine values:
  C:\\THIS PC\\Products\\MeshLine          user's integration clone (no lane)
  C:\\THIS PC\\Products\\MeshLine-claude   Lane A clone  (git config meshline.lane = A)
  C:\\THIS PC\\Products\\MeshLine-gemini   Lane B clone  (git config meshline.lane = B)
  C:\\THIS PC\\Products\\MeshLine-agents   autopilot state: logs, heartbeat, STOP, status.md, runtime clone
Standard library only (Python 3.11+ for tomllib).
"""
from __future__ import annotations

import json
import ntpath
import os
import subprocess
import sys
import tomllib
from pathlib import Path, PureWindowsPath

for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

HERE = Path(__file__).resolve().parent
TOOLS = HERE.parent                                     # <repo>/tools
CONFIG = tomllib.loads((HERE / "autopilot.toml").read_text(encoding="utf-8"))

PROJECT = CONFIG["project"]["name"]
SLUG = CONFIG["project"]["slug"]
REPO_URL = CONFIG["project"]["repo"]
ENV = SLUG.upper()                                      # env var prefix: MESHLINE_LANE, MESHLINE_PRODUCTS, ...
LANE_KEY = f"{SLUG}.lane"                               # git config key that marks a lane clone
CHECKS: list[str] = CONFIG["checks"]["commands"]
LOCKFILES: dict[str, str] = CONFIG["checks"].get("lockfiles", {})

_paths = CONFIG["paths"]
PRODUCTS = Path(os.environ.get(f"{ENV}_PRODUCTS", _paths["products"]))
CLONES = {"A": PRODUCTS / _paths["clone_a"], "B": PRODUCTS / _paths["clone_b"]}
INTEGRATION = PRODUCTS / _paths["integration"]
STATE = PRODUCTS / _paths["state"]
LAUNCHER = PRODUCTS / _paths["launcher"]
AGENT = {"A": "claude", "B": "gemini"}
LANE_OF_AGENT = {"claude": "A", "gemini": "B"}
OTHER = {"A": "B", "B": "A"}
RUNTIME = STATE / "runtime"   # clone pinned to origin/main; hooks and guards always run from here
HEARTBEAT = STATE / "heartbeat.json"
STOP_FILE = STATE / "STOP"
HEARTBEAT_MAX_AGE = 300  # seconds; older = supervisor not running = autopilot off

sys.path.insert(0, str(TOOLS))


def marker(agent: str, verdict: str) -> str:
    """The hidden first line of every signed review comment (AGENTS.md §5.4)."""
    return f"<!-- {SLUG}:agent={agent} verdict={verdict} -->"


def run(args: list[str], cwd: Path | str | None = None, timeout: int = 120) -> str:
    """Runs a command and returns stdout; raises on failure."""
    out = subprocess.run(args, cwd=cwd, capture_output=True, text=True, encoding="utf-8",
                         errors="replace", timeout=timeout)
    if out.returncode != 0:
        raise RuntimeError(f"{' '.join(args[:3])} failed ({out.returncode}): {out.stderr.strip()[:400]}")
    return out.stdout


def git(clone: Path, *args: str, timeout: int = 120) -> str:
    return run(["git", "-C", str(clone), *args], timeout=timeout)


def gh_json(clone: Path, *args: str) -> object:
    return json.loads(run(["gh", *args], cwd=clone, timeout=120) or "null")


def repo_root(path: Path | str) -> Path | None:
    try:
        return Path(run(["git", "-C", str(path), "rev-parse", "--show-toplevel"], timeout=20).strip())
    except Exception:
        return None


def lane_of(path: Path | str | None) -> str | None:
    """The lane configured for the clone containing `path` (git config <slug>.lane), or None."""
    if not path:
        return None
    p = Path(path)
    probe = p if p.is_dir() else p.parent
    while not probe.exists() and probe != probe.parent:
        probe = probe.parent
    try:
        lane = run(["git", "-C", str(probe), "config", "--get", LANE_KEY], timeout=20).strip()
    except Exception:
        return None
    return lane if lane in ("A", "B") else None


def autopilot_on() -> tuple[bool, str]:
    """On while the supervisor terminal is running (fresh heartbeat) and no STOP file exists."""
    import time
    if STOP_FILE.exists():
        return False, f"STOP file present ({STOP_FILE})"
    try:
        beat = json.loads(HEARTBEAT.read_text(encoding="utf-8"))
        age = time.time() - float(beat["time"])
    except Exception:
        return False, "supervisor not running (no heartbeat)"
    if age > HEARTBEAT_MAX_AGE:
        return False, f"supervisor not running (heartbeat {int(age)}s old)"
    return True, "supervisor running"


def is_within(path: Path | str, root: Path) -> bool:
    """Windows path semantics on every OS (ntpath), so CI on Linux judges paths like the real guard."""
    def norm(p: Path | str) -> PureWindowsPath:
        return PureWindowsPath(ntpath.normcase(ntpath.abspath(str(p))))
    try:
        norm(path).relative_to(norm(root))
        return True
    except ValueError:
        return False
