---
name: {SKILL}
description: >-
  Use this skill when the user says "start autopilot", "resume autopilot" or "run the {PROJECT}
  autopilot" in the {PROJECT} (offline mesh messenger) repository. It runs Lane B (Gemini):
  fetch the next action from the decision engine, do it, and repeat until the autopilot is off.
---

# {PROJECT} autopilot — Lane B (Gemini)

You are Lane B. Rules are in `AGENTS.md` (read §13 Autopilot first) and `GEMINI.md`.
Nobody will answer questions: make the reasonable choice, write down why, and continue.

## Steps
1. Work only in `{CLONE_B}`. Run `git fetch origin`.
2. Run `python tools/agents/next_action.py --lane B` (if that file is missing on your current
   branch, use `python "{RUNTIME}\tools\agents\next_action.py" --lane B`).
3. Do exactly the action it prints, following its instructions.
4. When the action is done, write a 3-line summary, then **go back to step 2 yourself**. Do not
   wait for the Stop hook: Antigravity does not call it (AGENTS.md §13, known limitation).
5. If it prints `[WAIT]`, run `python tools/agents/next_action.py --lane B --wait 600` and act on
   its output. If it prints `[STOP]` (the autopilot is off), end your turn.
6. After about 15 actions in one conversation, start a fresh one so your context stays small:
   `agentapi new-conversation --model=pro --title "{PROJECT} Lane B autopilot" "start autopilot"`
   from `{CLONE_B}`, check it printed a conversation id, then end your turn.

## Never
- Push to `main` or to `claude/…`/`contract/claude-…` branches; force-push without `--force-with-lease`.
- Edit files another lane owns (`python tools/check_owners.py --who <path>`); file an RFC instead.
- Try to get around a denied tool call or a failing pre-push hook. Read the reason and pick
  another approach.
- Put secrets (API keys, keystore passwords) in the repo.
