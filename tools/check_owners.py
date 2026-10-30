#!/usr/bin/env python3
"""Enforce OWNERS.toml: a branch may only change files its lane owns.

Usage:
  python tools/check_owners.py                       # current branch vs origin/main
  python tools/check_owners.py --branch gemini/B-104-svg --base origin/main
  python tools/check_owners.py --who path/to/file    # print the owner of a path
  python tools/check_owners.py --branch claude/x --files a.rs b.kt   # check given paths (tests)

Rules (see OWNERS.toml header):
  claude/…               may change owner A + "any"
  gemini/…               may change owner B + "any"
  contract/claude-…      may change "contract" + A + "any"   (user merges)
  contract/gemini-…      may change "contract" + B + "any"   (user merges)
  user/…, main           unrestricted
Exit code 1 on any violation. Standard library only (Python 3.11+).
"""
from __future__ import annotations

import argparse
import fnmatch
import subprocess
import sys
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Windows consoles default to cp1252; task titles contain Unicode (–, …, →).
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")


def load_rules() -> list[tuple[str, str]]:
    data = tomllib.loads((ROOT / "OWNERS.toml").read_text(encoding="utf-8"))
    rules = []
    for rule in data["rule"]:
        for pattern in rule["paths"]:
            rules.append((pattern, rule["owner"]))
    return rules


def specificity(pattern: str) -> int:
    return sum(1 for ch in pattern if ch not in "*?[]")


def owner_of(path: str, rules: list[tuple[str, str]]) -> str:
    path = path.replace("\\", "/")
    matches = [(specificity(p), o) for p, o in rules if fnmatch.fnmatchcase(path, p)]
    if not matches:
        return "contract"
    return max(matches)[1]


def allowed_owners(branch: str) -> set[str] | None:
    """None means unrestricted."""
    if branch in ("main", "HEAD") or branch.startswith("user/"):
        return None
    if branch.startswith("claude/"):
        return {"A", "any"}
    if branch.startswith("gemini/"):
        return {"B", "any"}
    if branch.startswith("contract/claude-"):
        return {"contract", "A", "any"}
    if branch.startswith("contract/gemini-"):
        return {"contract", "B", "any"}
    return set()  # unknown prefix: nothing allowed


def changed_files(base: str) -> list[str]:
    out = subprocess.run(
        ["git", "diff", "--name-only", f"{base}...HEAD"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout
    return [line for line in out.splitlines() if line.strip()]


def current_branch() -> str:
    return subprocess.run(
        ["git", "rev-parse", "--abbrev-ref", "HEAD"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout.strip()


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--branch", help="branch name (default: current branch)")
    ap.add_argument("--base", default="origin/main", help="base ref to diff against")
    ap.add_argument("--files", nargs="*", help="check these paths instead of git diff")
    ap.add_argument("--who", help="print the owner of one path and exit")
    args = ap.parse_args()

    rules = load_rules()
    if args.who:
        print(owner_of(args.who, rules))
        return 0

    branch = args.branch or current_branch()
    allowed = allowed_owners(branch)
    if allowed is None:
        print(f"check_owners: branch '{branch}' is unrestricted.")
        return 0
    if not allowed:
        print(f"check_owners: branch '{branch}' has no recognised prefix "
              "(claude/, gemini/, contract/claude-, contract/gemini-, user/).")
        return 1

    files = args.files if args.files is not None else changed_files(args.base)
    violations = [(f, owner_of(f, rules)) for f in files if owner_of(f, rules) not in allowed]
    if violations:
        print(f"check_owners: branch '{branch}' changed files it does not own:")
        for f, o in violations:
            hint = "open a contract/ branch" if o == "contract" else f"owned by {o} — file an RFC task instead"
            print(f"  {f}  ->  owner {o}  ({hint})")
        return 1
    print(f"check_owners: OK — {len(files)} file(s) on '{branch}' are within its lane.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
