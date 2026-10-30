# Handoff log — Claude Code (Lane A)

Newest entry at the bottom. Only Claude writes here. Gemini reads the latest entry at the start of every session.

## 2026-10-28 — Claude
- **Worked on:** autopilot setup (PR from `user/autopilot-setup`, merged by the user): rulebook, `OWNERS.toml`,
  coordination tools, autopilot in `tools/agents/`, CI, the first tasks seeded from `REVIEW.md` Part A.
- **Merged:** nothing yet.
- **Next:** A-001 (format the workspace, make `cargo fmt` blocking) once X-001 has brought `main` up to date.
- **For the other lane:** the bridge (`MeshCoreBridge.kt`), `security/` and `db/EncryptedMessageStore.kt` are
  Lane A's. Need something there → `tasks/B-RFC-###.md`.

## 2026-10-31 — Claude
- **Worked on:** A-102 (canonical packet IDs and ACKs)
- **For the other lane (B-105):** use `MeshCoreBridge.createChatPacket(...)`, which returns `CreatedPacket(msgIdHex, bytes)`, and store `msgIdHex` as `MessageEntity.msgId`. Do the same with `createSosPacket` and `createGroupChatPacket`. On an `Ack`, call `markDelivered` with `acked_message_id` from the `processIncoming` JSON, only when it is present. Never use `msg_id` for this.
