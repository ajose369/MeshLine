# ADR 2026-10-28 — Autopilot: both agents work unattended on MeshLine

**Status:** accepted by the user (request of 2026-10-28: "set up the same two-agent autopilot I use on my Rekha
project … copy and adapt its code") · **Author:** Claude (Lane A) · **Rules:** `AGENTS.md` §13 ·
**Reference implementation:** Rekha (`C:\THIS PC\Products\CDR`), ADR `2026-09-21-autopilot`

## Context
The user runs Claude Code and Gemini (in Google Antigravity) as two lanes on Rekha, with a supervisor that keeps
them working while the user is away. They asked for the same system on MeshLine, adapted rather than redesigned.

## User decisions (carried over from Rekha, 2026-09-21)
1. **Contract PRs:** while the autopilot runs, the author agent merges once the other agent's signed LGTM is newer
   than the last commit and CI is green.
2. **Work ahead:** allowed when the current milestone is blocked on the user.
3. **Duration:** until the user closes the autopilot terminal or creates the STOP file. Restarting must be one step.
4. **Lane split (2026-10-28):** "core vs app" — Lane A owns the Rust core, both sides of JNI and Kotlin key/at-rest
   crypto; Lane B owns the rest of the Android app, the site and release docs (`OWNERS.toml`).

## Design (same as Rekha, with these adaptations)
- **One config spot.** Everything project-specific lives in `tools/agents/autopilot.toml`: name, slug, repo URL,
  folders, and the check commands quoted into every prompt. The launcher `.cmd`, the Antigravity hooks and the
  skill are rendered from templates by `setup_clone.py`, so no copy carries hand-edited values.
- **Signed review markers** use the slug: `<!-- meshline:agent=<claude|gemini> verdict=<lgtm|changes|comment> -->`.
  Rekha's fallback recognition of unsigned comments was dropped: this project starts with markers.
- **Decision engine, supervisor, guards, runtime clone, heartbeat/STOP switch, single-instance lock, run caps,
  failure and no-progress back-off, waiting out Claude usage limits:** unchanged from Rekha.
- **Checks:** Rekha's cargo fmt/clippy/test plus the MeshLine simulator (`cargo run -p meshline-sim`, exits 1 on a
  failed network assertion) and Android unit tests + lint (`./gradlew testDebugUnitTest lintDebug
  -Pmeshline.skipRustBuild=true`). CI starts with `cargo fmt --check` non-blocking because the imported code is
  unformatted; A-001 formats it and makes the step blocking.
- **Guard additions:** `sdkmanager`, `cargo install` (system-wide toolchain changes) and `adb install`/`pm clear`
  (the connected phone may be the user's own) are denied with a reason.
- **Lane B is monitored, not driven.** Antigravity did not invoke the PreToolUse/Stop hooks when tested on Rekha
  (2026-09-23). The hook files are kept; the skill tells Gemini to loop on `next_action.py` itself; the supervisor's
  idle check counts Gemini's pushes as activity (the hook log alone would always read "never").
- **Lane identity:** each lane clone gets a local `user.name` ("Claude (MeshLine Lane A)" / "Gemini (MeshLine
  Lane B)") so history shows which agent committed; the email stays the user's.

## Consequences
- Nobody reviews individual steps. Quality rests on cross-review (which must reproduce claims), CI, the guards, and
  task Done-when criteria.
- Anything needing a human (phones, Play Console, the upload keystore, server deploys, admin installs) is logged
  and skipped rather than attempted.
- The user can revert any merged PR; the PR history is the audit trail.
