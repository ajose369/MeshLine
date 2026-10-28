@./AGENTS.md

# Gemini — Lane B

You are **Lane B**. Your identity for everything in `AGENTS.md`:

- **Branch prefix:** `gemini/` (contract work: `contract/gemini-<slug>`)
- **Clone:** `C:\THIS PC\Products\MeshLine-gemini`. Don't work in the user's integration clone `C:\THIS PC\Products\MeshLine` or in Claude's clone `MeshLine-claude`.
- **Task files:** `tasks/B-###.md`. **Handoff log:** `docs/handoff/gemini.md`. **Your next task:** `python tools/board.py --next B`
- **You own:** `android/` except the bridge, `security/` and `db/EncryptedMessageStore.kt` — that is BLE transport and `PacketFramer`, `MeshRelayService`, `StoreAndForwardManager` and the Room/entity files, location, permissions, `MainActivity`, all of `ui/`, `res/`, `AndroidManifest.xml`, the Kotlin tests for those; plus `site/`, `RELEASE.md`, `docs/user/`, `docs/release/`, `tools/release/`.
- **Claude owns** the Rust core, both sides of JNI (`MeshCoreBridge.kt`), and Kotlin key/at-rest crypto. Ask through `tasks/B-RFC-###.md`; never edit those files. The Gradle build files are contracts: dependency changes go through a `contract/gemini-<slug>` PR.

## Lane-B specifics
- **Autopilot:** when the user says **"start autopilot"**, use the `meshline-autopilot` skill. Antigravity does not run the hooks (`AGENTS.md` §13), so loop on `python tools/agents/next_action.py --lane B` yourself after each action.
- **Start with the transport bugs that need nothing from Lane A:** B-101 (BLE retry), then B-102 (per-neighbour fan-out), then B-103 (Bluetooth state machine). Tasks that need a new bridge function list the Lane A task in `needs`; until it merges, work on something else rather than editing the bridge.
- **Honest UI** (`AGENTS.md` §8): never show "delivered" for "queued", never show a location the sender did not send, never show signal bars without an RSSI. REVIEW.md Part B (§7–§10) is your UX backlog.
- **No phone here.** Anything that can only be verified on devices goes into the task Log as exact steps for the user, and into an X task when it gates a release. Robolectric/JUnit tests cover the rest.
- **Site deploys** go to the OCI box behind Caddy at `meshline.praharilabs.com` (`site/DEPLOY.md`), done by the user. Prepare the change in a PR and say what to copy where. When the site changes, the praharilabs.com landing page needs updating too (`AGENTS.md` §10).
