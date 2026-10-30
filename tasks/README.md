# Tasks

One file per task. This folder **is** the todo list for the whole project. It was seeded from `REVIEW.md` Part A
(bugs C1–C3, H1–H14, M1–M6) and `RELEASE.md` at the autopilot setup, 2026-10-28.

| Prefix | Owner | Who edits the file |
|---|---|---|
| `A-###` | Lane A: Claude Code | Claude only, inside its own PRs |
| `B-###` | Lane B: Gemini | Gemini only, inside its own PRs |
| `X-###` | The user | the user (or an agent the user explicitly asks) |
| `A-RFC-###` / `B-RFC-###` | the lane *asking* | the requester; the target lane answers in its handoff log and its own PR |

## Commands
```
python tools/board.py                # whole board by milestone (READY / waiting / IN FLIGHT / DONE …)
python tools/board.py --lane B       # one lane
python tools/board.py --next A       # what Lane A should do next (RFCs first)
python tools/board.py --validate     # CI check: ids, lanes, statuses, needs
```

## Status flow
`todo` → `in-progress` (branch pushed) → `review` (PR open) → `done` (merged). Use `blocked` only with a Log line explaining what is blocking it.

## Rules
- A task is **ready** when it is `todo` and every task in `needs` is `done`.
- Tasks bigger than about 2 days get split: create new tasks in your own lane with the next free ID in the milestone's range, and list them in the parent.
- Cross-lane fixes: the Lane A task delivers the core/bridge half; the Lane B task that wires it in lists it in `needs`.
- New tasks: copy `_TEMPLATE.md`. Requests to the other lane: copy `_RFC_TEMPLATE.md`.
- Full rules: `AGENTS.md` §4–§6.
