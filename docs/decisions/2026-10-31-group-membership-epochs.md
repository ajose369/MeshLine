# Every group membership change is a new epoch sent to every member

- **Status:** accepted
- **Task:** A-103 (REVIEW H5, core half of H6). App half: B-111, A-RFC-002.

## Failure scenarios

1. **Stranded members (H5).** Alice adds Bob and delivers his invite, then adds Carol. `add_member` changes the list
   without changing the epoch and only Carol gets the new list. Bob's list has no Carol, so Bob rejects every message
   from her with `NotAGroupMember`. Re-sending to Bob does not help: same-epoch invites are `Stale`.
2. **New members read old traffic.** Carol receives the key Alice and Bob have been using. Anyone who recorded their
   earlier packets can hand them to Carol, and she can read them.
3. **"Rekeyed" claims too much (H6).** `invites` means packets were built, and `unreachable` means no pairwise session.
   Neither says a member holds the key. After a member's phone is taken, a plain rekey also sends that phone the new key.

## Decision

- `add_to_group` rotates the key, bumps the epoch and returns one invite per member, old and new. The rotation is the
  version: receivers already apply a higher epoch and ignore a lower one, and every invite carries the full list, so a
  member who missed an epoch catches up from the next. Authentication is unchanged: inside the pairwise session, from
  the fixed admin, in a packet signed by that admin.
- It fails with `NoSession` **before** rotating, so an add that cannot happen does not cost everyone a key update.
- Adding an existing member re-sends the current epoch to that member and does not rotate.
- **Epoch acknowledgement.** A member that applied an invite, or already has that epoch or a later one (`Stale`), sends
  an ordinary `Ack` naming the invite's packet id. The admin records which invite went to whom. An ACK counts only if
  it is signed by that member and names such an invite. A member told it was `Removed`, or who could not open or
  refused the invite, sends nothing.
- `group_epoch_status` reports each member as `Acknowledged`, `Queued` (packet built, nothing heard) or `NotSent`
  (no packet exists for this epoch). The table lives only on the admin's device.
- `resend_group_updates` rebuilds invites for everyone not yet acknowledged, for use after a handshake or a lost packet.
- `removeAndRotate` on the bridge is the recovery workflow. It sends the removed member nothing.

## Compatibility

- **Wire:** none changed. `schema.rs` is untouched. An add looks like a rekey, and the ACK uses the existing format
  (`payload = original msg_id`). A build without this change applies the invites and simply sends no epoch ACK, so that
  member stays `Queued`, which is true as far as the admin can know.
- **Sealed state:** the ACK table follows the state as a second bincode value. Older blobs have none and open with an
  empty table. Those members show as `NotSent`, and `resend_group_updates` settles them.
- **JNI:** additive. `addToGroup`, `resendGroupUpdates`, `groupEpochStatusJson`, `removeAndRotate`, and a new
  `group_epoch_ack` field in the receive JSON. `inviteToGroup` is deprecated but unchanged, because it returns a single
  packet: rotating there would lock out every other member. **H5 stays live in the app until Lane B moves to
  `addToGroup` (A-RFC-002).**

## Known limits

- **Messages in flight across a rotation are lost.** A member who has not yet got the new epoch sends under the old
  tag, and members already on the new epoch cannot read that. Rotating on add makes this more frequent. A-109 tracks a
  bounded, receive-only grace for the previous epoch, which has its own security trade-off to design.
- **Large groups can hit relay rate limits.** A rotation is one packet per member from one sender, and relays allow a
  burst of 10 and then 2 per second per sender. Lost invites show as `Queued`; the app must pace them and resend.
- **A compromised member can read until removed**, and a member can falsely acknowledge an epoch. An ACK means "the
  holder of that identity says so", not proof of an honest device.
- **Admin loss.** The admin is fixed at creation, so losing that device freezes membership. Out of scope here.
