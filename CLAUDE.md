@AGENTS.md

# Claude Code — Lane A

You are **Lane A**. Your identity for everything in `AGENTS.md`:

- **Branch prefix:** `claude/` (contract work: `contract/claude-<slug>`)
- **Clone:** `C:\THIS PC\Products\MeshLine-claude`. Don't work in the user's integration clone `C:\THIS PC\Products\MeshLine` or in Gemini's clone `MeshLine-gemini`. (When the user opens a session in the integration clone themselves, they are the integrator and these lane rules don't bind them.)
- **Task files:** `tasks/A-###.md`. **Handoff log:** `docs/handoff/claude.md`. **Your next task:** `python tools/board.py --next A`
- **You own:** `crates/*` (`meshline-core`, `meshline-ffi`, `meshline-sim`; `packet/schema.rs` is a contract), the Kotlin bridge `android/.../bridge/`, Kotlin key and at-rest crypto `android/.../security/` and `android/.../db/EncryptedMessageStore.kt` (with their tests), `docs/protocol.md`, `docs/security/`.
- **Gemini owns** the rest of the Android app (BLE transport, relay service, `StoreAndForwardManager` and the other `db/` files, location, permissions, UI, resources), the site and the release docs. Ask through `tasks/A-RFC-###.md`; never edit those files.

## Lane-A specifics
- **You are the security lane.** REVIEW.md's C1 (nonce reuse after a crash), the wire-format fixes (H3, H7–H10) and group membership (H5) are yours. For every crypto change, write the design as an ADR first (`docs/decisions/YYYY-MM-DD-<slug>.md`) with the failure scenario, then a test that reproduces the scenario before the fix.
- **You own both sides of JNI.** When you add or change a bridge function, change `meshline-ffi` and `MeshCoreBridge.kt` in the same PR, run the JNI symbol check (`RELEASE.md` §3), and tell Gemini in the PR description what to call. Incompatible changes go through `contract/claude-…` (`AGENTS.md` §7).
- **Many fixes cross the lane boundary.** Ship the core/bridge half with tests, then make sure the matching Lane B task (its `needs` lists yours) has what it needs: the exact bridge API, the semantics, and the failure cases.
- Keep `meshline-sim` honest: when routing or rate-limit behaviour changes, add or update a scenario so `cargo run -p meshline-sim` asserts it.
- Use your methodology file `C:\THIS PC\Products\SKILL.md` (hard-session playbook) for load-bearing work: prove the core against a reference before building on it.
