---
id: A-RFC-002
title: "Move group adds, removal and rekey reporting to the epoch bridge API"
lane: A
owner: claude
to: B
milestone: M1
status: todo
needs: [A-103]
---

# A-RFC-002 — Move group adds, removal and rekey reporting to the epoch bridge API

**From:** Lane A · **To:** Lane B · **Blocks:** REVIEW H5 being fixed in the app; B-111

## Need
A-103 fixes H5 in the core, but the app still calls `MeshCoreBridge.inviteToGroup`, which is deprecated and keeps the
bug: existing members never hear about the new member and reject their messages. **H5 stays live on phones until
`StoreAndForwardManager.inviteToGroup` moves to `addToGroup`.** This has to ship before release; please do it first,
alongside or ahead of B-111.

## Proposed API / change
All of this is in `MeshCoreBridge.kt` once A-103 merges. Design: `docs/decisions/2026-10-31-group-membership-epochs.md`.

```kotlin
fun addToGroup(groupIdHex: String, peerIdHex: String): String?       // replaces inviteToGroup
fun resendGroupUpdates(groupIdHex: String): String?                  // same JSON
fun removeAndRotate(groupIdHex: String, peerIdHex: String): String?  // replaces removeFromGroup in the recovery flow
fun groupEpochStatusJson(groupIdHex: String): String?                // admin only
```

- **`addToGroup`, `resendGroupUpdates`, `removeAndRotate`** return the JSON `applyRekey` already parses:
  `{"invite_packets_hex": [...], "unreachable": [...]}`. Queue **every** packet: if only the new member's goes out, the
  rest of the group is locked out, because the key has rotated.
- **`addToGroup` null:** nothing changed (not the admin, or no session with a new peer). Same handling as today's null.
- **`groupEpochStatusJson`:** `{"group_id", "epoch", "members": [{"member_id", "acked_epoch", "state"}]}`, with `state`
  one of `Acknowledged`, `Queued` (packet built, nothing heard back) or `NotSent` (no packet for this epoch, usually no
  session). Show only `Acknowledged` as "has the key". The admin's own row is not included. Null on a non-admin device.
- **Receive JSON:** a new optional `group_epoch_ack: {group_id, member_id, epoch}`, on an `Ack` packet. Refresh the status
  and `schedulePersist()` when it is present. Do not treat such an ACK as a chat delivery.
- **Invite ACKs:** a `GroupInvite` result can now carry `ack_packet_hex`. Queue it as for chat.

## What the app has to handle
- **Copy (B-111):** "key updates queued", never delivered. Offer remove-and-rotate for a seized device; a plain
  `rekeyGroup` sends that phone the new key too.
- **Retry:** nothing retries on its own. Call `resendGroupUpdates` when a session is established with a member who is
  not `Acknowledged`, and on a slow timer while any are `Queued`.
- **Pacing:** a rotation is one packet per member from one sender, and relays allow a burst of 10 and then 2 per second
  per sender, so space these out in groups over about 10 members.
- **Custody:** invites belong in the persisted outbox. If they are lost, `resendGroupUpdates` rebuilds them.
- **Known gap:** a member who has not got the new epoch sends under the old key, and others cannot read it (A-109).
  An in-chat note such as "waiting for N members to get the new key" would be honest.

## Meanwhile
Lane A's tests use `add_to_group` directly in the core. Nothing in the app changes until this RFC is done.

## Resolution
<!-- Requester fills in: link to the other lane's PR / handoff entry, then set status: done -->
