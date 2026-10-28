#!/usr/bin/env python3
"""Configure a lane clone for the autopilot (idempotent; the supervisor runs it at every start).

  python tools/agents/setup_clone.py --lane A --clone "C:\\THIS PC\\Products\\MeshLine-claude"
  python tools/agents/setup_clone.py --launcher       # (re)write <products>\\<launcher>.cmd

- `git config <slug>.lane <A|B>`: how the guards and hooks know which lane a clone is.
- `git config user.name/user.email` (local to the clone) when `--identity` is given, so the two
  agents' commits are told apart in history even though they share one GitHub login.
- `core.hooksPath` -> the runtime clone's `tools/agents/githooks` (absolute), so the pre-push
  guard is active on every branch, including branches created before the guard existed.
- Lane B only: installs Antigravity hooks + the autopilot skill as an *untracked* `.agents/`
  folder (listed in .git/info/exclude), with absolute paths into the runtime clone, so they stay
  in place whatever branch Gemini checks out.
- `--launcher`: renders Autopilot.cmd.template with autopilot.toml's values into the products
  folder, so the launcher never carries a hand-edited copy of the configuration.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from common import (CLONES, LANE_KEY, LAUNCHER, PROJECT, REPO_URL, RUNTIME, SLUG,  # noqa: E402
                    STATE, STOP_FILE)

HERE = Path(__file__).resolve().parent
SKILL = f"{SLUG}-autopilot"


def render(text: str, runtime: Path = RUNTIME, json_escape: bool = False) -> str:
    def p(path: Path) -> str:
        s = str(path)
        return s.replace("\\", "\\\\") if json_escape else s
    values = {"{RUNTIME}": p(runtime), "{PROJECT}": PROJECT, "{SLUG}": SLUG, "{SKILL}": SKILL,
              "{CLONE_A}": p(CLONES["A"]), "{CLONE_B}": p(CLONES["B"]), "{STATE}": p(STATE),
              "{STOP}": p(STOP_FILE), "{REPO}": REPO_URL, "{LAUNCHER}": LAUNCHER.name}
    for key, value in values.items():
        text = text.replace(key, value)
    return text


def configure(lane: str, clone: Path, runtime: Path = RUNTIME, identity: bool = False) -> list[str]:
    done = []
    def cfg(key: str, value: str) -> None:
        subprocess.run(["git", "-C", str(clone), "config", key, value], check=True)
        done.append(f"{key}={value}")
    cfg(LANE_KEY, lane)
    cfg("core.hooksPath", str(runtime / "tools" / "agents" / "githooks").replace("\\", "/"))
    if identity:
        # The name says which agent committed; the email stays the user's global one so GitHub
        # still links the commits to the shared account.
        cfg("user.name", {"A": f"Claude ({PROJECT} Lane A)", "B": f"Gemini ({PROJECT} Lane B)"}[lane])
        email = subprocess.run(["git", "config", "--global", "--get", "user.email"],
                               capture_output=True, text=True).stdout.strip()
        if email:
            cfg("user.email", email)
    if lane == "B":
        agents = clone / ".agents"
        (agents / "skills").mkdir(parents=True, exist_ok=True)
        template = (HERE / "antigravity" / "hooks.template.json").read_text(encoding="utf-8")
        (agents / "hooks.json").write_text(render(template, runtime, json_escape=True), encoding="utf-8")
        dst = agents / "skills" / SKILL
        shutil.rmtree(dst, ignore_errors=True)
        dst.mkdir(parents=True)
        for src in (HERE / "antigravity" / "skills" / "autopilot").iterdir():
            (dst / src.name).write_text(render(src.read_text(encoding="utf-8"), runtime), encoding="utf-8")
        exclude = clone / ".git" / "info" / "exclude"
        exclude.parent.mkdir(parents=True, exist_ok=True)
        lines = exclude.read_text(encoding="utf-8").splitlines() if exclude.exists() else []
        if ".agents/" not in lines:
            exclude.write_text("\n".join(lines + [".agents/"]) + "\n", encoding="utf-8")
        done.append(f"installed .agents/ (Antigravity hooks + {SKILL} skill)")
    return done


def write_launcher(path: Path = LAUNCHER) -> Path:
    text = render((HERE / "Autopilot.cmd.template").read_text(encoding="utf-8"))
    path.write_text(text.replace("\r\n", "\n").replace("\n", "\r\n"), encoding="utf-8")
    return path


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--lane", choices=["A", "B"])
    ap.add_argument("--clone")
    ap.add_argument("--identity", action="store_true", help="also set a local git user.name/email for the lane")
    ap.add_argument("--launcher", action="store_true", help=f"write {LAUNCHER}")
    a = ap.parse_args()
    if a.launcher:
        print("   wrote", write_launcher())
    if a.lane:
        for item in configure(a.lane, Path(a.clone or CLONES[a.lane]), identity=a.identity):
            print("  ", item)
    elif not a.launcher:
        ap.error("give --lane (and optionally --clone) or --launcher")
    return 0


if __name__ == "__main__":
    sys.exit(main())
