# Session nonce safety: durably reserved counter ranges

**Date:** 2026-10-31 · **Status:** accepted for implementation; human crypto review pending (X-102)
**Fixes:** REVIEW C1 · **Task:** A-101 · **App half:** B-104

## The failure

A pairwise session encrypts with ChaCha20-Poly1305 under a persisted key, with a counter as the nonce.

1. Persist the state with counter `n`.
2. Encrypt message A with `n` and hand it to the radio.
3. The process is killed before the advanced counter is written.
4. Restart restores `n`.
5. Encrypt message B with `n`.

A and B now share a key and nonce. A passive listener can XOR them to recover the XOR of the plaintexts and can
recover the Poly1305 key, which allows forgeries. Replay rejection at the receiver does nothing against that listener.
More frequent saves only narrow the window.

The same rollback affects receiving. Accept and show a message, crash before the write, restore: the replay window
has forgotten it, so a captured copy is accepted and shown a second time.

`c1_reproducer_on_unfixed_code` (a temporary test, run on the code before this change) failed with both counters
`[0, 0, 0, 0, 0, 0, 0, 0]`.

## Decision

### Sending: a counter is spent only if a durable write already covers it

Each session has three values:

| Value | Stored | Meaning |
|---|---|---|
| `sending_counter` | yes | next counter to use |
| `reserved_until` | yes | end of the range in the sealed state; a restore resumes here |
| `durable_limit` | **no** | counters below this are covered by a completed write |

- **Export** sets `reserved_until = max(reserved_until, sending_counter + 64)` and seals it.
- **Commit**, called only after the blob is durably in place, sets `durable_limit = reserved_until`.
- **Encrypt** refuses with `NonceReservationExhausted` when `sending_counter >= durable_limit`.
- **Restore** sets all three values to the stored `reserved_until`, so nothing can be spent until a new write commits.

Safety does not depend on the app ordering its calls correctly. Every counter a process has ever used is below a limit
already on disk, and a restore starts at or above that limit. A new session has `durable_limit = 0`, and a commit for
an export that predates the session grants nothing, because the session's `reserved_until` is still 0.

**Single writer.** Export, write and commit must be one step (`SecureStateStore.persist` is `@Synchronized`). This is
the one rule the core cannot check: a commit following an unwritten newer export, or an older blob landing on top of a
newer one, would allow reuse.

The block is 64. A crash abandons at most one block, which keeps delayed traffic inside the 512-counter replay window,
and it costs about one write per 48 sends per session (the bridge tops up below 16 left). The wire format is unchanged
and old peers see only a jump in counters.

### Receiving: durable before released

`process_incoming` still moves the window in memory, and a generation counter records that it moved.
`has_uncommitted_receives()` stays true until a committed export covers that receive. `MeshCoreBridge.processIncoming`
persists before returning, so nothing reaches the app, the screen or an ACK before the window that accepted it is
durable.

- Crash before the write: nothing was shown and no ACK was sent. The window rolls back and the sender's retransmission
  is accepted. This is correct, not a replay.
- Crash after the write: the window holds the message and a replay is refused.

Group messages and invites bump the same generation, so their replay state gets the same protection.

**Failed write on receive: deliver and log.** The window has already moved, so withholding the message would lose it
for good, because the retransmission would be refused as a replay. A replay then needs a failed write, a crash and an
attacker holding the packet. Losing a distress reply on a full disk is the worse outcome. X-102 should check this call.

### Migration

The vault version moves to 2, with its own AAD. Version 1 blobs still open. Their counters cannot be trusted, because
those builds released ciphertext before saving, so a migrated session resumes `2^20` counters ahead. Version 2 blobs
cannot be read by older builds, which discard them and re-handshake. This is a one-way update, not a wire change.

## Alternatives rejected

- **Fresh sessions after every restart.** No persisted counter at all, but every launch costs a three-message handshake
  with every peer over a lossy radio, which `session.rs` exists to avoid, and needs explicit session ids on the wire.
- **Random nonces (XChaCha20).** Removes the counter hazard, but changes the wire format and still needs a separate
  replay identifier. A bigger change, and receive rollback is untouched.
- **A write before every send.** Correct, but one fsync per message for no safety gain over a reservation.
- **Reserving a receive range too** (after a restore, refuse everything below a stored ceiling). No per-message write,
  but the peer's next counters fall inside the ceiling, so up to a block of new messages would be silently refused
  after every crash. Unacceptable here.

## Remaining limits

- **Restoring an old copy of the file.** Anything that puts an older blob back under the same Keystore key defeats the
  reservation. `mesh_secure_state.bin` is not excluded from Android backup today, though the Keystore key does not leave
  the device. A-RFC-001 asks Lane B to exclude it.
- **Power loss.** `writeDurably` syncs the file before the rename and the directory after it. The directory sync is
  best effort; if a filesystem refuses it, a process kill is still safe, but power loss might not be. Needs a look on a
  real device (X-102 / X-101).
- **Group sending counters** are not reserved. Group messages use random 192-bit nonces, so there is no nonce reuse.
  A rollback can make members refuse a sender's reused counters until they pass them. That is a delivery bug, not a
  confidentiality one, and it is not fixed here.
- **Forward secrecy is unchanged**: session keys are still at rest in the vault.
- **Repeated crashes without sends** can advance the counter by a block each time. After about 8 in a row, traffic
  still in flight from before them could fall out of the receiver's window and be lost. It fails safe.

## What Lane B must do (B-104)

The bridge now enforces both orderings, so `StoreAndForwardManager` needs no ordering logic to be safe:

1. `createChat`, `inviteToGroup`, `removeFromGroup`, `rekeyGroup` and `processIncoming` **may write to disk**. Call them
   off the main thread.
2. **Null from `createChat` now also means the reservation could not be written.** Keep treating null as "not sent".
   Never retry through another path.
3. A member reported as unreachable by `removeFromGroup` or `rekeyGroup` may now mean no reservation, not only no
   session. Keep showing it as not reached.
4. `persistNow` and `schedulePersist` stay as they are, and they are no longer what protects nonces. Do not call
   `MeshCoreBridge.exportState` or `commitState` directly; only `SecureStateStore` may.
5. Keep the outbox separate: if a process dies holding unsent ciphertext, resending that same ciphertext is safe, since
   it has the same counter and the same plaintext.
6. Exclude `mesh_secure_state.bin` from backup (A-RFC-001).
