# Canonical packet IDs and trustworthy ACKs

**Date:** 2026-10-31 · **Status:** accepted
**Fixes:** REVIEW H3 (core half) · **Task:** A-102 · **App half:** B-105

## The failures

Telling someone a message was delivered when it was not can cost a life: they stop trying another route.

1. **ACK without reading.** Bob reinstalls and loses the session. Alice's chat reaches Bob, decryption fails, and Bob's
   core still sends an ACK. Alice sees "delivered" for a message nobody read. Any stranger who signs junk addressed to
   Bob also gets an ACK. `a_chat_that_will_not_decrypt_is_never_acknowledged`, run on the code before this change,
   failed at the `ack.is_none()` assertion.
2. **ACK from anyone.** Message ids cross the radio in the clear, and nothing tied an ACK to the node the message went
   to. Once the app correlates ACKs correctly, a relay could sign its own ACK for a message it carried and mark it
   delivered without the recipient ever having it.
3. **No correlation.** History stores a Kotlin UUID, the wire carries a Rust id, and the bridge exposes only the ACK
   packet's own id. A genuine ACK can match nothing.

## Decision

**An ACK means exactly one thing: the recipient's core decrypted this chat.** It is built only when `process_incoming`
produced plaintext for a `Chat` addressed to this node. Duplicates and replays produce no plaintext, so they get no ACK.
`PrivateSos` no longer gets one either, because it has no decryption path and nothing creates it yet.

**The sender keeps a table of `msg_id -> recipient`.** `create_chat_packet` records the entry. An inbound `Ack` addressed
to this node settles an entry only if its verified `sender_id` is that recipient and its payload is exactly one message
id. `sender_id` is already bound to the signing key by `Packet::verify`, so this checks the actual signer.

- A rejected ACK leaves the entry in place, so a forgery cannot burn the slot before the genuine ACK arrives.
- An accepted ACK removes the entry, so a message is settled once.
- At 1024 entries the oldest is dropped. That message keeps showing "sent", which is all we can prove.
- The core, not Kotlin, makes this decision (`AGENTS.md` §8). The bridge exposes the result as `acked_message_id`.

**The table is sealed with the rest of the secure state,** because an ACK can take days to cross a partitioned mesh and
the app may restart in between. It also records who this device has been writing to, so it stays encrypted and
`wipe_secure_state` clears it.

**The sealed format changes additively.** The table is a second bincode value after the existing state:

- A blob without it restores with an empty table, and those messages stay "sent".
- An older build reading a new blob ignores the trailing bytes, because `bincode::deserialize` allows them.

So this is not an incompatible change under `AGENTS.md` §7 and needs no `contract/` branch. A crash between creating a
chat and the next write loses that entry. The result is a message stuck on "sent", the safe direction.

**One id from creation to history.** The new `nativePacketId` reads the id from a packet this device built, and
`MeshCoreBridge.createChatPacket`, `createSosPacket` and `createGroupChatPacket` return it with the bytes. The existing
functions stay until B-105 has moved over, so the JNI change is additive too.

## Not fixed here

- **Lost ACKs are not re-sent.** A retransmission of a chat hits dedup and the replay window and gets no second ACK, so
  a lost ACK leaves the message on "sent". That is honest but pessimistic. Re-acknowledging duplicates needs flood
  control and belongs with end-to-end delivery in M3.
- **Dedup is not yet keyed by sender.** A stranger who sees a genuine ACK in flight can reach Alice first with its own
  packet under the same packet id, so Alice's dedup drops the real ACK. The result is again "sent", never a false
  "delivered". A-105 closes this.
- **ACKs are not encrypted.** A listener can see that Bob acknowledged a given id. Headers already show that Alice and
  Bob are talking, so this reveals little new.
- **Merging with A-101.** A-101 versions the vault and is still open. Whichever lands second must keep this trailing
  value when writing and read it after the state in the current-version branch. The legacy branch has no table.
