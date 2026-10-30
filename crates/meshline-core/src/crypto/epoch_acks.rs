//! Which group members have confirmed which epoch, as known to the group admin.
//!
//! Building an invite is not delivering it. A rekey that claims everyone "has the
//! new key" because the packets were constructed is how an admin ends up trusting
//! a group half of which cannot read them, so the only thing recorded as settled
//! is an ACK signed by the member the invite was sent to.

use serde::{Deserialize, Serialize};

/// Upper bound on outstanding invites. Reaching it forgets the oldest, whose
/// members then report as not sent, so the error is towards resending.
pub const MAX_SENT_UPDATES: usize = 1024;

/// Upper bound on remembered acknowledgements. Forgetting one makes a member look
/// unconfirmed, never confirmed.
pub const MAX_ACKED_MEMBERS: usize = 1024;

#[derive(Debug, Clone, Serialize, Deserialize)]
struct SentUpdate {
    msg_id: [u8; 16],
    group_id: [u8; 16],
    epoch: u32,
    member: [u8; 16],
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct AckedEpoch {
    group_id: [u8; 16],
    member: [u8; 16],
    epoch: u32,
}

/// A member's confirmation that they hold an epoch.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupEpochAck {
    pub group_id: [u8; 16],
    pub member: [u8; 16],
    pub epoch: u32,
}

/// What the admin honestly knows about one member and the current epoch.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MemberEpochState {
    /// The member signed an acknowledgement of this epoch or a later one.
    Acknowledged,
    /// An invite was built, and nothing has been heard back. Not a delivery.
    Queued,
    /// No invite exists for this epoch, usually for want of a pairwise session.
    NotSent,
}

/// One member's row in a group's epoch status.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemberEpochStatus {
    pub member: [u8; 16],
    /// The highest epoch this member has confirmed, if any.
    pub acked_epoch: Option<u32>,
    pub state: MemberEpochState,
}

/// Per-member view of a group's current epoch, for the admin's UI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GroupEpochStatus {
    pub group_id: [u8; 16],
    pub epoch: u32,
    pub members: Vec<MemberEpochStatus>,
}

/// Invites sent and epochs confirmed. Sealed with the rest of the secure state,
/// because the mesh can take days to bring an ACK back.
#[derive(Debug, Default, Serialize, Deserialize)]
pub struct GroupEpochAcks {
    sent: Vec<SentUpdate>,
    acked: Vec<AckedEpoch>,
}

impl GroupEpochAcks {
    /// Remembers an invite. Invites for epochs older than this one can no longer
    /// settle anything worth knowing, so they are dropped.
    pub fn record_sent(
        &mut self,
        msg_id: [u8; 16],
        group_id: [u8; 16],
        epoch: u32,
        member: [u8; 16],
    ) {
        self.sent
            .retain(|s| !(s.group_id == group_id && s.member == member && s.epoch < epoch));
        if self.sent.len() >= MAX_SENT_UPDATES {
            self.sent.remove(0);
        }
        self.sent.push(SentUpdate {
            msg_id,
            group_id,
            epoch,
            member,
        });
    }

    /// Settles the invite an ACK names, if `signer` is who it was sent to.
    /// `signer` must already be proven by the packet signature. Anything else,
    /// including a malformed payload, settles nothing.
    pub fn accept(&mut self, signer: &[u8; 16], payload: &[u8]) -> Option<GroupEpochAck> {
        let msg_id: [u8; 16] = payload.try_into().ok()?;
        let index = self
            .sent
            .iter()
            .position(|s| s.msg_id == msg_id && &s.member == signer)?;
        let sent = self.sent.remove(index);

        let held = match self
            .acked
            .iter_mut()
            .find(|a| a.group_id == sent.group_id && a.member == sent.member)
        {
            Some(row) => {
                row.epoch = row.epoch.max(sent.epoch);
                row.epoch
            }
            None => {
                if self.acked.len() >= MAX_ACKED_MEMBERS {
                    self.acked.remove(0);
                }
                self.acked.push(AckedEpoch {
                    group_id: sent.group_id,
                    member: sent.member,
                    epoch: sent.epoch,
                });
                sent.epoch
            }
        };
        self.sent.retain(|s| {
            !(s.group_id == sent.group_id && s.member == sent.member && s.epoch <= held)
        });

        Some(GroupEpochAck {
            group_id: sent.group_id,
            member: sent.member,
            epoch: sent.epoch,
        })
    }

    /// The highest epoch a member has confirmed.
    pub fn acked_epoch(&self, group_id: &[u8; 16], member: &[u8; 16]) -> Option<u32> {
        self.acked
            .iter()
            .find(|a| &a.group_id == group_id && &a.member == member)
            .map(|a| a.epoch)
    }

    /// Where a member stands against `epoch`.
    pub fn state(&self, group_id: &[u8; 16], member: &[u8; 16], epoch: u32) -> MemberEpochState {
        if self
            .acked_epoch(group_id, member)
            .is_some_and(|e| e >= epoch)
        {
            MemberEpochState::Acknowledged
        } else if self
            .sent
            .iter()
            .any(|s| &s.group_id == group_id && &s.member == member && s.epoch == epoch)
        {
            MemberEpochState::Queued
        } else {
            MemberEpochState::NotSent
        }
    }

    /// Drops a removed member, so a late ACK from them cannot count for anything.
    pub fn forget_member(&mut self, group_id: &[u8; 16], member: &[u8; 16]) {
        self.sent
            .retain(|s| !(&s.group_id == group_id && &s.member == member));
        self.acked
            .retain(|a| !(&a.group_id == group_id && &a.member == member));
    }

    pub fn forget_group(&mut self, group_id: &[u8; 16]) {
        self.sent.retain(|s| &s.group_id != group_id);
        self.acked.retain(|a| &a.group_id != group_id);
    }

    pub fn clear(&mut self) {
        self.sent.clear();
        self.acked.clear();
    }

    pub fn is_empty(&self) -> bool {
        self.sent.is_empty() && self.acked.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const GROUP: [u8; 16] = [1; 16];
    const BOB: [u8; 16] = [2; 16];
    const CAROL: [u8; 16] = [3; 16];

    #[test]
    fn a_member_moves_from_not_sent_to_queued_to_acknowledged() {
        let mut acks = GroupEpochAcks::default();
        assert_eq!(acks.state(&GROUP, &BOB, 1), MemberEpochState::NotSent);
        acks.record_sent([9; 16], GROUP, 1, BOB);
        assert_eq!(acks.state(&GROUP, &BOB, 1), MemberEpochState::Queued);
        assert!(acks.accept(&BOB, &[9; 16]).is_some());
        assert_eq!(acks.state(&GROUP, &BOB, 1), MemberEpochState::Acknowledged);
    }

    #[test]
    fn only_the_member_it_was_sent_to_can_settle_an_invite() {
        let mut acks = GroupEpochAcks::default();
        acks.record_sent([9; 16], GROUP, 1, BOB);
        assert_eq!(acks.accept(&CAROL, &[9; 16]), None);
        assert_eq!(acks.state(&GROUP, &BOB, 1), MemberEpochState::Queued);
    }

    #[test]
    fn malformed_payloads_settle_nothing() {
        let mut acks = GroupEpochAcks::default();
        acks.record_sent([9; 16], GROUP, 1, BOB);
        for payload in [&[][..], &[9; 15][..], &[9; 17][..], &[8; 16][..]] {
            assert_eq!(acks.accept(&BOB, payload), None);
        }
    }

    #[test]
    fn an_acknowledged_epoch_does_not_cover_a_later_one() {
        let mut acks = GroupEpochAcks::default();
        acks.record_sent([9; 16], GROUP, 1, BOB);
        acks.accept(&BOB, &[9; 16]);
        assert_eq!(acks.state(&GROUP, &BOB, 2), MemberEpochState::NotSent);
        assert_eq!(acks.acked_epoch(&GROUP, &BOB), Some(1));
    }

    #[test]
    fn a_late_ack_never_lowers_what_a_member_confirmed() {
        let mut acks = GroupEpochAcks::default();
        acks.record_sent([1; 16], GROUP, 1, BOB);
        acks.record_sent([2; 16], GROUP, 1, BOB);
        acks.accept(&BOB, &[1; 16]);
        acks.record_sent([3; 16], GROUP, 3, BOB);
        acks.accept(&BOB, &[3; 16]);
        assert_eq!(acks.accept(&BOB, &[2; 16]), None);
        assert_eq!(acks.acked_epoch(&GROUP, &BOB), Some(3));
    }

    #[test]
    fn a_removed_member_cannot_settle_anything_afterwards() {
        let mut acks = GroupEpochAcks::default();
        acks.record_sent([9; 16], GROUP, 1, BOB);
        acks.forget_member(&GROUP, &BOB);
        assert_eq!(acks.accept(&BOB, &[9; 16]), None);
        assert!(acks.is_empty());
    }

    #[test]
    fn the_table_is_bounded_and_forgets_towards_not_sent() {
        let mut acks = GroupEpochAcks::default();
        for i in 0..(MAX_SENT_UPDATES + 1) {
            let mut id = [0u8; 16];
            id[..8].copy_from_slice(&(i as u64).to_le_bytes());
            acks.record_sent(id, GROUP, 1, id);
        }
        assert_eq!(acks.sent.len(), MAX_SENT_UPDATES);
        assert_eq!(acks.state(&GROUP, &[0; 16], 1), MemberEpochState::NotSent);
    }
}
