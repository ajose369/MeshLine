# AGENTS.md — the rulebook for every AI agent working on MeshLine

> **Contract file.** Change it only through a contract PR (§7).
> Claude Code loads this through `CLAUDE.md`; Gemini loads it through `GEMINI.md`.
> The product plan and milestones are in [`docs/PLAN.md`](docs/PLAN.md). The engineering review that seeded the
> first tasks is [`REVIEW.md`](REVIEW.md) (Part A: bugs C1–C3, H1–H14, M1–M6; Part B: product and UX).

## 1. What we are building

**MeshLine** is an offline disaster messenger for **Android**: phones talk directly to each other over
Bluetooth LE, with no cell tower, internet, SIM or server.

- One-tap public **SOS** with location, relayed by every phone in range.
- **End-to-end encrypted** 1:1 chat (Noise-XX, Ed25519 mesh identities) and private groups.
- **Signed resource pins** (water, shelter, medical, hazards), store-and-forward relaying, panic wipe.
- It is honest: no plaintext fallback, no invented coordinates, no fake signal bars.

The stack:
- Rust protocol core (Cargo workspace in `crates/`): `meshline-core` (crypto, packets, routing, pins,
  rate limits), `meshline-ffi` (JNI), `meshline-sim` (network simulator with pass/fail assertions)
- Android app (`android/`): Kotlin + Jetpack Compose, BLE GATT transport, Keystore-backed storage.
  Gradle builds the Rust core for three ABIs with `cargo-ndk`.
- Static marketing/privacy site (`site/`), live at `meshline.praharilabs.com`.

This is a safety app. A wrong "delivered", a made-up location or a silent plaintext fallback can hurt someone.
"Done" means proven by tests, not "looks right".

## 2. Who is who

| Who | Lane | Branch prefix | Clone | Handoff log | Task IDs |
|---|---|---|---|---|---|
| **Claude Code** | **A**: Rust core (crypto, wire format, routing, pins, rate limits), JNI on both sides (`meshline-ffi` + `MeshCoreBridge.kt`), Kotlin key/at-rest crypto (`security/`, `EncryptedMessageStore.kt`), simulator | `claude/` | `C:\THIS PC\Products\MeshLine-claude` | `docs/handoff/claude.md` | `A-###` |
| **Gemini** | **B**: the Android app above the bridge (BLE transport, relay service, persistence and outbox, location, permissions, all UI and resources, Kotlin tests), the site, release docs | `gemini/` | `C:\THIS PC\Products\MeshLine-gemini` | `docs/handoff/gemini.md` | `B-###` |
| **User** | Integrator: merges the setup and any PR they choose, runs on-device and two-device tests, owns Play Console, the server and the keystore, decides disputes | `user/` | `C:\THIS PC\Products\MeshLine` | tells an agent | `X-###` |

## 3. The golden rule: one owner per file

Every path has exactly **one** owner, defined in [`OWNERS.toml`](OWNERS.toml). CI runs `tools/check_owners.py`, which **fails any PR that touches a file its lane doesn't own**. Check a path before editing it:

```
python tools/check_owners.py --who android/app/src/main/java/org/meshline/app/ui/sos/SosScreen.kt   # -> B
```

- **Your files:** change freely, in your own branches.
- **The other lane's files:** never edit them, not even a one-line fix. File an RFC (§6.3) instead.
- **Contract files** (owner `contract`: this file, `OWNERS.toml`, `README.md`, `PRIVACY.md`, `Cargo.toml`, the Gradle build files and `proguard-rules.pro`, the wire format `crates/meshline-core/src/packet/schema.rs`, CI, the coordination tools and the autopilot in `tools/agents/`): only on a `contract/<claude|gemini>-<slug>` branch. The other agent reviews it and **the user merges** it; while the autopilot runs, the author agent may merge it instead once the other agent has approved it (§13).
- **`Cargo.lock`** (owner `any`): never hand-merge it. On conflict run `git checkout origin/main -- Cargo.lock` and then `cargo check --workspace`, and commit the result.
- **Dependencies:** Rust dependencies go in **your own crate's** `Cargo.toml` (all crates are Lane A's). Android dependencies live in `android/app/build.gradle.kts`, a contract file: Lane B adds them through a `contract/gemini-…` PR.
- **New ADRs** in `docs/decisions/` (owner `any`): new files only, named `YYYY-MM-DD-<slug>.md`. Never edit an ADR written by the other lane; supersede it with a new one.
- **Shared Kotlin types.** `MeshCoreBridge.kt` is Lane A's. Lane B calls it but never edits it; need a new bridge function or field → RFC to Lane A. Likewise `EncryptedMessageStore.kt` and everything in `security/` are Lane A's, while `StoreAndForwardManager.kt` and the rest of `db/` are Lane B's.

## 4. Session protocol (every session, in this order)

**Start**
1. `git fetch origin && git checkout main && git pull --ff-only`
2. Read the **latest entry** in the *other* agent's handoff log.
3. `python tools/board.py --next <A|B>`. It lists RFCs addressed to you (answer those first), your in-progress tasks, then the next ready task.

**Pick**
4. Take the task `--next` suggests: earliest milestone, all `needs` done, lowest ID. Don't cherry-pick later work while earlier ready work exists, unless the user asks.
5. `git checkout -b <claude|gemini>/<ID>-<short-slug>`, then set `status: in-progress` in the task file and add a Log line.
6. Push the branch early (`git push -u origin HEAD`) so the other agent's board shows your task as **IN FLIGHT**.

**Work**
7. Stay inside your lane's paths. Before pushing, run: `python tools/check_owners.py`.
8. Keep PRs small: ideally ≤ 500 changed lines and ≤ 2 days of work. Split big tasks into new tasks in your lane.
9. Need something from the other lane? File an RFC (§6.3) and keep working against a local shim or fake.

**Finish**
10. Everything green locally (the list is `[checks]` in `tools/agents/autopilot.toml`):
    - `cargo fmt --all --check`
    - `cargo clippy --workspace --all-targets -- -D warnings`
    - `cargo test --workspace` and `cargo run -q -p meshline-sim` (it exits 1 if a network assertion fails)
    - `cd android && ./gradlew testDebugUnitTest lintDebug -Pmeshline.skipRustBuild=true`
    - `python tools/board.py --validate` and `python tools/check_owners.py`
    - Lane B touching anything that calls the bridge: also build with the native core once (`./gradlew assembleDebug`, no skip flag).
11. Update the task file: tick "Done when" boxes with evidence (test names, numbers, PR link), set `status: review`, and add a Log line.
12. Open the PR: `gh pr create --title "[<ID>] <title>" --body "<what, why, evidence>"`.
13. **Review any open PR from the other lane** (§5.4).
14. Append a handoff entry (§11) to **your** log. After merge, set the task `status: done` (in your next PR, or a tiny follow-up PR).

## 5. Git workflow

1. **Separate clones.** The agents never share a working tree, a `target/` or an `android/build/` directory. Never run two agents in the same folder.
2. **Branches.** Use `claude/<ID>-<slug>`, `gemini/<ID>-<slug>` or `contract/<claude|gemini>-<slug>`. Keep them short-lived and rebase on `origin/main` before opening or updating a PR. Never force-push `main`, and never push to the other lane's branches. The user's own branches start with `user/`; any other prefix fails the ownership check.
3. **Required CI** (`.github/workflows/ci.yml`): ownership check, task-file validation, Python tool tests, Rust fmt/clippy `-D warnings`/tests/simulator, Android unit tests and lint.
4. **Cross-review.** The other agent reviews every PR with `gh pr diff <n>`, reproduces the key claims (runs the tests), then posts **one signed comment**. Both agents use the same GitHub login, so the first line of every review comment must be a hidden marker saying who wrote it and the verdict:
   `<!-- meshline:agent=claude verdict=lgtm -->`, `<!-- meshline:agent=gemini verdict=changes -->`, or `verdict=comment` for replies. The autopilot reads these markers to decide who has to act next; unsigned comments are ignored. Review for correctness, security, contract breakage and missing tests, not style.
5. **Merge.**
   - Lane PRs: the author squash-merges (`gh pr merge --squash --delete-branch`) once CI is green and the other agent has commented LGTM.
   - Contract PRs: the user merges them. While the autopilot runs, the author agent merges once the other agent's LGTM is newer than the last commit and CI is green (§13).
   - The user can veto anything.
6. **Commits.** Conventional style, `<type>(<crate or area>): <summary> [<ID>]`, e.g. `fix(core): namespace dedup by sender [A-106]`, `fix(ble): requeue the packet whose last fragment failed [B-101]`.

## 6. Tasks

### 6.1 Files
There is one file per task in `tasks/`: `A-###.md` (Claude), `B-###.md` (Gemini), `X-###.md` (user). Front matter:

```yaml
---
id: B-101
title: "BLE retry keeps the packet whose last fragment failed"
lane: B            # A | B | X
owner: gemini      # claude | gemini | user
milestone: M1
status: todo       # todo | in-progress | review | done | blocked
needs: [X-001]
---
```
Only the owning lane edits a task file, and it does so inside its own PRs, so status changes never conflict. Use `tasks/_TEMPLATE.md` for new tasks.

### 6.2 IDs
Each lane numbers only its own IDs, taking the next free number in its lane and milestone range (M2 → `x-2##`), so the two agents can never collide. `python tools/board.py --lane A` shows what is taken.

### 6.3 RFCs (asking the other lane for something)
1. Create `tasks/<your-lane>-RFC-###.md` from `tasks/_RFC_TEMPLATE.md`. Set `to: <A|B>`, say what you need and why, and propose an API.
2. The target lane sees it at the top of `board.py --next` and answers **at the start of its next session**, in its handoff log and by implementing it in its own PR (or a contract PR).
3. The requester sets the RFC to `done` once it has merged. Disagreement: both agents write their positions in the RFC, the user decides, and the decision becomes an ADR.

## 7. Contracts

- The contracts are: the **wire format** (`crates/meshline-core/src/packet/schema.rs`), the **JNI surface** (the `Java_org_meshline_…` exports in `crates/meshline-ffi/src/lib.rs` and the `external fun` declarations and public API of `MeshCoreBridge.kt`), and the **sealed-state formats** written by `security/` and `EncryptedMessageStore.kt`.
- `schema.rs` is a contract file. The JNI surface and the sealed formats are Lane A's files, but any PR that changes them **incompatibly** must go through a `contract/claude-…` branch so Lane B reviews it, even though the files are Lane A's.
- Changes are **additive** wherever possible: new packet types or fields that old builds ignore, new bridge functions rather than changed signatures, versioned sealed formats with a migration. An incompatible wire change needs a version bump and an ADR.
- The JNI names must match exactly or the app fails at runtime on the user's phone. Any PR that touches the JNI surface quotes the symbol check from `RELEASE.md` §3 (or the CI step that runs it) as evidence.

## 8. Engineering rules (both lanes)

**Architecture**
- **Core/adapter split.** Protocol logic, crypto and validation live in Rust. Kotlin does radio, storage, OS integration and UI. If Kotlin needs a decision that depends on packet contents or keys, the core makes it and returns the answer.
- **Fail closed.** No plaintext fallback, ever. If the native core or a session is missing, refuse to send and say so.
- **No invented data.** A missing location is `None`, not `(0, 0)`. Unknown RSSI is "unknown", not 0 dBm. "Queued", "relayed", "delivered" and "read" are different facts; never show one as another.
- **Custody is not history.** Message history is what the user sees; the outbox is what the phone still owes the mesh. Persist both, separately (REVIEW §4).

**Safety and correctness**
- **Never panic on input.** Everything from the radio, from disk and from the user is untrusted. No `unwrap()`/`expect()` on such data in Rust, no `!!` on it in Kotlin. Verify signatures before any other processing.
- **Crypto changes need a written design first** (an ADR in `docs/decisions/`), with the failure scenario it fixes, and a test that reproduces that scenario. Key or nonce reuse is a release blocker.
- **`unsafe`.** `meshline-core` carries `#![forbid(unsafe_code)]` once A-001 lands; JNI code keeps each `unsafe` block small with a `// SAFETY:` comment.
- **Tests are part of the task:** Rust unit tests, a simulator scenario when routing/flooding behaviour changes, Robolectric/JUnit tests for Kotlin logic. Anything that can only be checked on real phones goes in the task Log as an on-device check for the user (X tasks), never claimed as done.

**Conventions**
- Rust: errors with `thiserror`, context-rich messages, no `println!` in `meshline-core`. Kotlin: coroutines/Flows as already used, no blocking I/O on the main thread.
- Public items get doc comments that say *why*, not just *what*.
- User-visible strings live in `res/values/strings.xml`.

## 9. Licence, privacy and secrets (non-negotiable)

- **Dependencies:** MIT, Apache-2.0, BSD, ISC, Zlib, MPL-2.0, Unicode, CC0 only. No GPL/AGPL, no unclear licences.
- **Privacy:** the app sends nothing to any server. No analytics, no crash reporters, no network permission beyond what BLE needs. Any change that would affect `PRIVACY.md`, the Play Data Safety answers (`RELEASE.md` §5) or requested permissions must say so in the PR description.
- **Secrets never enter the repo:** upload keystores, `keystore.properties`, API keys. They live only on the user's machine or the server `.env`. The repo is **public**.
- **No real people's data** in tests, fixtures, screenshots or the simulator.

## 10. Shared resources

- **Phones.** Agents have no phone. On-device and two-device checks (`RELEASE.md` §4) are written into an X task for the user, with the exact steps and what to look for. The guard blocks `adb install` and similar.
- **Server:** OCI `144.24.141.188`, `n8n-caddy` container, site at `meshline.praharilabs.com` served from `/srv/meshline-site` (`site/DEPLOY.md`). Agents never deploy: Lane B prepares the change in a PR and an X task for the deploy. When the site goes live or changes, the praharilabs.com landing page (`C:\THIS PC\RMMS Backup\Laptop\5. Jyothir\prahari-labs-site\index.html`) is updated too, by the user.
- **Play Console and the upload key:** the user's only.

## 11. Handoff entry (append to *your* log at the end of every session)

```markdown
## YYYY-MM-DD — <agent>
- **Worked on:** A-103 (in-progress), A-104 (review → PR #12)
- **Merged:** PR #9 [A-101]
- **Next:** finish A-103 ACK correlation
- **Blocked / waiting on:** B-RFC-002 (need the outbox to expose packet ids)
- **For the other lane:** new bridge fn `ackedMessageId(packet)`; switch StoreAndForward to it (B-105).
```

## 12. Definition of done (every task)

1. The task's "Done when" boxes are ticked, with evidence.
2. CI is green, and the other agent has commented LGTM.
3. Tests are added, and any simulator scenario the task touches passes.
4. User-facing behaviour is documented, and the handoff log is updated.

If you can't meet an item, say so plainly in the PR and the handoff log. Never report "done" from reasoning alone. Report it from execution.

## 13. Autopilot (unattended operation)

The user can leave both agents running. The user decided on 2026-10-28 (ADR `2026-10-28-autopilot`, carrying over
Rekha's ADR `2026-09-21-autopilot`) that while the autopilot runs:
- **Contract PRs** are merged by their author agent once the other agent has posted a signed LGTM newer than the
  PR's last commit and CI is green. The PR history is the audit trail; the user can revert anything.
- **Working ahead is allowed.** When the current milestone is blocked on the user (devices, accounts, merges),
  take the earliest ready task from a later milestone.

**How it runs**
- All project-specific settings (names, repo, folders, check commands) are in `tools/agents/autopilot.toml`.
- `C:\THIS PC\Products\MeshLine-Autopilot.cmd` starts `tools/agents/supervisor.py` from the runtime clone
  `C:\THIS PC\Products\MeshLine-agents\runtime` (always `origin/main`). While that terminal is open, the heartbeat
  file says the autopilot is **on**. Closing the terminal, pressing Ctrl+C, or creating
  `C:\THIS PC\Products\MeshLine-agents\STOP` turns it **off**. Current actions finish; nothing new starts.
- **Lane A (Claude):** the supervisor runs one fresh headless Claude Code session per action.
- **Lane B (Gemini):** Gemini runs inside Antigravity, started by telling it **"start autopilot"** (skill
  `meshline-autopilot`, installed into the Gemini clone's untracked `.agents/` folder). **Known limitation (tested
  2026-09-23 on Rekha, Antigravity IDE):** the `PreToolUse` and `Stop` hooks in `.agents/hooks.json` and in the
  global `~/.gemini/config/hooks.json` were never invoked, after full IDE restarts. So Gemini still asks the user to
  approve each step, and nothing hands it the next action when it stops. Until that changes, **Lane B is
  semi-attended**: it needs a human to approve steps and to re-prompt it. The skill tells Gemini to loop on
  `next_action.py` by itself, and the supervisor only *monitors* Lane B: `status.md` says when Gemini has work but
  has pushed nothing for 45 minutes. Its rules are still enforced — by the git `pre-push` hook in its clone, by CI,
  and by review.
- The **next action** always comes from `python tools/agents/next_action.py --lane <A|B>`. It checks, in order:
  fix failing CI on your PRs → rebase conflicting PRs → address review findings → merge approved PRs → review the
  other lane's PRs → answer RFCs → continue in-flight work → start the next ready task. Do exactly that one action.
- **Guards:** every command and file write passes `tools/agents/guard.py` (PreToolUse hooks for both agents), and
  every push passes the pre-push hook. They deny pushes to `main` or the other lane's branches, plain force-push,
  `--no-verify`, touching the other clones, system installs (`winget`, `sdkmanager`, `cargo install`, …), server
  access, `adb install`, and edits to files your lane doesn't own. A denial comes with a reason: change approach;
  never try to get around it.
- **Limits:** at most 40 Lane A runs a day; 3 failed runs in a row pause the lane 30 min; the same action
  dispatched 3 times without progress pauses it 1 h; a run times out after 150 min; a run that hits the Claude
  usage limit waits for the limit's reset time instead of counting as a failure.

**Rules while unattended**
1. Nobody will answer questions. Make the reasonable choice, write down why (PR description, task Log), and continue.
2. Anything that truly needs the user (phones, admin installs, Play Console, the keystore, server deploys, legal)
   goes in the task Log and `status.md` picks it up. Then move on to other work.
3. Keep each action small enough to finish in about two hours. Split bigger tasks into new task files in your lane.
4. In a task PR, set the task's status to `done` with evidence **in the same PR**. It only takes effect when the
   PR merges, which saves a follow-up PR.
5. Reviews must reproduce claims (run the tests) and must be signed (§5.4). "LGTM" without running anything is not
   a review.
6. Handoff notes: the PR description and review comments are the handoff. Only write `docs/handoff/` entries for
   news the other lane must act on, and include them in a PR you are about to merge. Append-only files conflict
   easily: on conflict keep both entries, oldest first.
7. The user reads `C:\THIS PC\Products\MeshLine-agents\status.md` on return. Keep task Logs truthful so it is too.
