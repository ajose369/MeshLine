---
id: A-RFC-001
title: "Exclude the sealed secure state from Android backup and device transfer"
lane: A
owner: claude
to: B
milestone: M1
status: todo
needs: []
---

# A-RFC-001 — Exclude the sealed secure state from Android backup and device transfer

**From:** Lane A · **To:** Lane B · **Blocks:** nothing in Lane A; closes a limit named in the A-101 ADR

## Need
`docs/decisions/2026-10-31-session-nonce-safety.md` relies on the sealed state on disk never going backwards. A restore
that puts an older `mesh_secure_state.bin` back under the same Keystore key would reopen nonce reuse (REVIEW C1). The
backup rules exclude only the identity today, and both rule files are Lane B resources.

## Proposed API / change
```xml
<!-- res/xml/backup_rules.xml -->
<exclude domain="file" path="mesh_secure_state.bin" />
<!-- res/xml/data_extraction_rules.xml, in both cloud-backup and device-transfer -->
<exclude domain="file" path="mesh_secure_state.bin" />
```

## Meanwhile
Nothing is blocked. On another device the Keystore key is missing, so the blob will not open and the app re-handshakes.
The exposure is a restore onto the same device while its Keystore key survives.

## Resolution
<!-- Requester fills in: link to the other lane's PR / handoff entry, then set status: done -->
