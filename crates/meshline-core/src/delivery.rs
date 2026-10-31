//! Which outgoing messages may still be acknowledged, and by whom.
//!
//! "Delivered" is a claim about a specific person's phone, so it can only come
//! from a signature by that phone. The packet signature proves who signed an
//! ACK; this table supplies who was supposed to, and it has to outlive a restart
//! because an ACK can take days to cross a partitioned mesh.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Most messages awaiting an ACK at once. The table is sealed and rewritten with
/// the rest of the secure state, so it stays small. Losing the oldest entry only
/// means that message keeps showing "sent", which is true as far as we can prove.
pub const MAX_PENDING_ACKS: usize = 1024;

#[derive(Debug, Clone, Copy, Serialize, Deserialize)]
struct PendingAck {
    recipient: [u8; 16],
    sent_at: u64,
}

/// Outgoing message ids mapped to the node that must sign their ACK.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct PendingAcks {
    entries: HashMap<[u8; 16], PendingAck>,
}

impl PendingAcks {
    /// Remembers that `recipient`, and nobody else, may acknowledge `msg_id`.
    pub fn record(&mut self, msg_id: [u8; 16], recipient: [u8; 16], sent_at: u64) {
        if self.entries.len() >= MAX_PENDING_ACKS {
            let oldest = self
                .entries
                .iter()
                .min_by_key(|(_, pending)| pending.sent_at)
                .map(|(id, _)| *id);
            if let Some(id) = oldest {
                self.entries.remove(&id);
            }
        }
        self.entries
            .insert(msg_id, PendingAck { recipient, sent_at });
    }

    /// Returns the acknowledged message id when `signer` is the recipient that
    /// message was sent to.
    ///
    /// A rejected ACK leaves the entry alone, so a stranger's forgery cannot use
    /// up the slot and make the genuine ACK fail when it arrives.
    pub fn accept(&mut self, signer: &[u8; 16], payload: &[u8]) -> Option<[u8; 16]> {
        let msg_id: [u8; 16] = payload.try_into().ok()?;
        if &self.entries.get(&msg_id)?.recipient != signer {
            return None;
        }
        self.entries.remove(&msg_id);
        Some(msg_id)
    }

    pub fn clear(&mut self) {
        self.entries.clear();
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RECIPIENT: [u8; 16] = [1; 16];
    const STRANGER: [u8; 16] = [2; 16];
    const MSG: [u8; 16] = [9; 16];

    #[test]
    fn only_the_recipient_can_settle_an_entry() {
        let mut pending = PendingAcks::default();
        pending.record(MSG, RECIPIENT, 1);

        assert_eq!(pending.accept(&STRANGER, &MSG), None);
        assert_eq!(pending.len(), 1, "a forgery must not consume the entry");
        assert_eq!(pending.accept(&RECIPIENT, &MSG), Some(MSG));
        assert_eq!(pending.accept(&RECIPIENT, &MSG), None, "settled only once");
    }

    #[test]
    fn a_payload_that_is_not_one_message_id_is_refused() {
        let mut pending = PendingAcks::default();
        pending.record(MSG, RECIPIENT, 1);

        assert_eq!(pending.accept(&RECIPIENT, &[]), None);
        assert_eq!(pending.accept(&RECIPIENT, &MSG[..15]), None);
        assert_eq!(pending.accept(&RECIPIENT, &[9; 17]), None);
        assert_eq!(pending.len(), 1);
    }

    #[test]
    fn the_table_is_bounded_and_drops_the_oldest_entry() {
        let mut pending = PendingAcks::default();
        for i in 0..MAX_PENDING_ACKS as u64 {
            let mut id = [0u8; 16];
            id[..8].copy_from_slice(&i.to_le_bytes());
            pending.record(id, RECIPIENT, i + 1);
        }
        pending.record(MSG, RECIPIENT, u64::MAX);

        assert_eq!(pending.len(), MAX_PENDING_ACKS);
        assert_eq!(
            pending.accept(&RECIPIENT, &[0; 16]),
            None,
            "the oldest entry made room"
        );
        assert_eq!(pending.accept(&RECIPIENT, &MSG), Some(MSG));
    }
}
