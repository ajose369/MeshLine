//! Established session transport, in a form that survives a process restart.
//!
//! `snow`'s own transport state cannot be serialized, which meant every app
//! launch began with a fresh Noise handshake against every peer. On a mesh that
//! is not a minor cost: a handshake is three round trips over a lossy radio, and
//! until it completes there is no way to send anything at all. So once the
//! handshake finishes we take the two split keys out of `snow` and run the
//! transport ourselves.
//!
//! The construction is exactly what Noise specifies for the transport phase
//! (`ChaChaPoly` keyed by the split output, 12-byte nonce of four zero bytes
//! followed by a 64-bit little-endian counter), with one deliberate difference:
//! the counter travels explicitly in front of the ciphertext instead of being
//! implicit. A mesh reorders and duplicates freely, so an implicit counter would
//! discard perfectly good traffic that merely arrived late.
//!
//! Persisting session keys is a real trade-off and worth naming: it costs some
//! forward secrecy against an adversary who seizes an unlocked device, since the
//! keys are on disk rather than only in RAM. That is why the vault they are
//! written to is encrypted under a hardware-backed key and why
//! [`crate::MeshNode::wipe_secure_state`] exists.
//!
//! Persisting a counter is dangerous in a different way: a restored snapshot
//! must never hand out a counter that was already used under the same key. So
//! counters are spent only from a range already reserved on disk, and a restore
//! resumes from the end of that range. See
//! `docs/decisions/2026-10-31-session-nonce-safety.md`.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    ChaCha20Poly1305, Key, Nonce,
};
use serde::{Deserialize, Serialize};
use zeroize::Zeroize;

use super::CryptoError;
use crate::packet::schema::node_id_from_pubkey;

/// Largest transport message we will produce or accept.
pub const MAX_TRANSPORT_MESSAGE: usize = 4096;

/// How far a received counter may lag the highest one seen before it is treated
/// as a replay. Sized for a mesh that reorders aggressively.
pub const REPLAY_WINDOW: u64 = 512;

/// Number of 64-bit words backing the replay bitmap.
const WINDOW_WORDS: usize = (REPLAY_WINDOW / 64) as usize;

/// Bytes of framing overhead added to every ciphertext: 8-byte counter + tag.
pub const TRANSPORT_OVERHEAD: usize = 8 + 16;

/// How many counters one durable write reserves ahead.
///
/// A crash abandons at most this many, and a receiver only tolerates the jump
/// while older in-flight traffic stays inside [`REPLAY_WINDOW`], so this is kept
/// well below it. Larger would mean fewer writes but more lost traffic per crash.
pub const NONCE_RESERVATION_BLOCK: u64 = 64;

/// Below this many spendable counters, the next durable write should happen
/// before sending rather than after running out.
pub const NONCE_RESERVATION_LOW_WATER: u64 = 16;

/// Added to a counter read from a pre-reservation (v1) vault. Those builds could
/// have spent an unknown number of counters past their last save; no phone sends
/// a million messages between two saves.
const LEGACY_COUNTER_SKIP: u64 = 1 << 20;

/// A sliding replay window.
///
/// Bit `d` of the bitmap records "the counter `highest - d` has been accepted",
/// so the whole window moves with a shift rather than a scan. The previous
/// implementation kept a `Vec<u64>` and did a linear `contains` on every inbound
/// packet, which is a needless per-packet cost on a device that is already
/// battery-constrained.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ReplayWindow {
    /// Highest counter accepted so far. Meaningless until `started`.
    highest: u64,
    /// `bitmap[0]` holds distances 0..63, `bitmap[1]` 64..127, and so on.
    bitmap: [u64; WINDOW_WORDS],
    started: bool,
}

impl ReplayWindow {
    pub fn new() -> Self {
        Self::default()
    }

    /// Checks a counter without recording it.
    pub fn check(&self, counter: u64) -> Result<(), CryptoError> {
        if !self.started {
            return Ok(());
        }
        if counter > self.highest {
            return Ok(());
        }
        let distance = self.highest - counter;
        if distance >= REPLAY_WINDOW {
            return Err(CryptoError::ReplayedNonce);
        }
        if self.is_set(distance) {
            return Err(CryptoError::ReplayedNonce);
        }
        Ok(())
    }

    /// Records a counter as seen. Call only after the tag has verified, so a
    /// forged frame cannot burn a counter the real peer still needs.
    pub fn record(&mut self, counter: u64) {
        if !self.started {
            self.started = true;
            self.highest = counter;
            self.set(0);
            return;
        }
        if counter > self.highest {
            self.shift(counter - self.highest);
            self.highest = counter;
            self.set(0);
        } else {
            let distance = self.highest - counter;
            if distance < REPLAY_WINDOW {
                self.set(distance);
            }
        }
    }

    fn is_set(&self, distance: u64) -> bool {
        let word = (distance / 64) as usize;
        let bit = distance % 64;
        word < WINDOW_WORDS && self.bitmap[word] & (1u64 << bit) != 0
    }

    fn set(&mut self, distance: u64) {
        let word = (distance / 64) as usize;
        let bit = distance % 64;
        if word < WINDOW_WORDS {
            self.bitmap[word] |= 1u64 << bit;
        }
    }

    /// Ages every recorded counter by `n` positions, dropping anything that
    /// falls out of the window.
    fn shift(&mut self, n: u64) {
        if n >= REPLAY_WINDOW {
            self.bitmap = [0u64; WINDOW_WORDS];
            return;
        }
        let words = (n / 64) as usize;
        let bits = (n % 64) as u32;

        if words > 0 {
            for i in (0..WINDOW_WORDS).rev() {
                self.bitmap[i] = if i >= words {
                    self.bitmap[i - words]
                } else {
                    0
                };
            }
        }
        if bits > 0 {
            let mut carry = 0u64;
            for word in self.bitmap.iter_mut() {
                let next_carry = *word >> (64 - bits);
                *word = (*word << bits) | carry;
                carry = next_carry;
            }
        }
    }
}

/// An established, authenticated, bidirectional session with one peer.
#[derive(Serialize, Deserialize)]
pub struct TransportSession {
    sending_key: [u8; 32],
    receiving_key: [u8; 32],
    peer_identity_pubkey: [u8; 32],
    peer_node_id: [u8; 16],
    sending_counter: u64,
    /// End of the range written (or about to be written) to the vault. A restore
    /// resumes sending here, whatever `sending_counter` said.
    reserved_until: u64,
    replay: ReplayWindow,
    /// Unix seconds at which the session was established, for staleness policy
    /// and for showing the user how old a secure link is.
    pub established_at: u64,
    /// Counters below this are known to be covered by a durable write. Never
    /// persisted, so a session cannot send until a write has committed.
    #[serde(skip)]
    durable_limit: u64,
}

/// A session as v1 vaults stored it, before reserved ranges existed.
#[derive(Deserialize)]
pub(crate) struct LegacyTransportSession {
    sending_key: [u8; 32],
    receiving_key: [u8; 32],
    peer_identity_pubkey: [u8; 32],
    peer_node_id: [u8; 16],
    sending_counter: u64,
    replay: ReplayWindow,
    established_at: u64,
}

impl Drop for LegacyTransportSession {
    fn drop(&mut self) {
        self.sending_key.zeroize();
        self.receiving_key.zeroize();
    }
}

impl From<LegacyTransportSession> for TransportSession {
    /// Its counter cannot be trusted, since v1 released ciphertext before saving,
    /// so the migrated session resumes far enough ahead to clear anything spent.
    fn from(legacy: LegacyTransportSession) -> Self {
        Self {
            sending_key: legacy.sending_key,
            receiving_key: legacy.receiving_key,
            peer_identity_pubkey: legacy.peer_identity_pubkey,
            peer_node_id: legacy.peer_node_id,
            sending_counter: legacy.sending_counter,
            reserved_until: legacy.sending_counter.saturating_add(LEGACY_COUNTER_SKIP),
            replay: legacy.replay.clone(),
            established_at: legacy.established_at,
            durable_limit: 0,
        }
    }
}

impl Drop for TransportSession {
    fn drop(&mut self) {
        self.sending_key.zeroize();
        self.receiving_key.zeroize();
    }
}

impl TransportSession {
    /// Builds a session from the Noise split output.
    ///
    /// Noise defines `split()` as returning the initiator's sending key first,
    /// so the responder swaps them.
    pub(crate) fn from_split(
        split: ([u8; 32], [u8; 32]),
        is_initiator: bool,
        peer_identity_pubkey: [u8; 32],
        established_at: u64,
    ) -> Self {
        let (sending_key, receiving_key) = if is_initiator {
            (split.0, split.1)
        } else {
            (split.1, split.0)
        };

        Self {
            sending_key,
            receiving_key,
            peer_identity_pubkey,
            peer_node_id: node_id_from_pubkey(&peer_identity_pubkey),
            sending_counter: 0,
            reserved_until: 0,
            replay: ReplayWindow::new(),
            established_at,
            durable_limit: 0,
        }
    }

    pub fn peer_node_id(&self) -> [u8; 16] {
        self.peer_node_id
    }

    pub fn peer_identity_pubkey(&self) -> [u8; 32] {
        self.peer_identity_pubkey
    }

    /// Extends the reservation, ahead of sealing this session into the vault.
    /// Nothing becomes spendable until [`commit_reservation`](Self::commit_reservation).
    pub(crate) fn reserve_for_export(&mut self) {
        self.reserved_until = self
            .reserved_until
            .max(self.sending_counter.saturating_add(NONCE_RESERVATION_BLOCK));
    }

    /// The sealed state holding the reservation is durable, so it may be spent.
    pub(crate) fn commit_reservation(&mut self) {
        self.durable_limit = self.reserved_until;
    }

    /// Brings a session read from the vault back to life: everything below the
    /// reservation may already have been used by the process that wrote it, so
    /// none of it can be spent again.
    pub(crate) fn resume_after_restore(&mut self) {
        self.sending_counter = self.sending_counter.max(self.reserved_until);
        self.reserved_until = self.sending_counter;
        self.durable_limit = self.sending_counter;
    }

    /// Counters that can be spent without another durable write.
    pub fn spendable_counters(&self) -> u64 {
        self.durable_limit.saturating_sub(self.sending_counter)
    }

    /// Encrypts one message, spending a counter from the durable reservation.
    ///
    /// Fails with [`CryptoError::NonceReservationExhausted`] rather than use a
    /// counter a crash could hand out again.
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        if plaintext.len() + TRANSPORT_OVERHEAD > MAX_TRANSPORT_MESSAGE {
            return Err(CryptoError::MessageTooLarge);
        }
        // A wrapped counter would reuse a nonce, which is catastrophic for
        // ChaChaPoly. Refuse to send instead; the caller can re-handshake.
        let counter = self.sending_counter;
        if counter == u64::MAX {
            return Err(CryptoError::SessionExhausted);
        }
        if counter >= self.durable_limit {
            return Err(CryptoError::NonceReservationExhausted);
        }

        let cipher = ChaCha20Poly1305::new(Key::from_slice(&self.sending_key));
        let ciphertext = cipher
            .encrypt(
                Nonce::from_slice(&nonce_bytes(counter)),
                Payload {
                    msg: plaintext,
                    aad: &[],
                },
            )
            .map_err(|_| CryptoError::Aead)?;

        self.sending_counter += 1;

        let mut out = Vec::with_capacity(8 + ciphertext.len());
        out.extend_from_slice(&counter.to_le_bytes());
        out.extend_from_slice(&ciphertext);
        Ok(out)
    }

    pub fn decrypt(&mut self, framed: &[u8]) -> Result<Vec<u8>, CryptoError> {
        if framed.len() < 8 + 16 || framed.len() > MAX_TRANSPORT_MESSAGE {
            return Err(CryptoError::MessageTooLarge);
        }
        let mut counter_bytes = [0u8; 8];
        counter_bytes.copy_from_slice(&framed[..8]);
        let counter = u64::from_le_bytes(counter_bytes);

        // Cheap check first, so a flood of replays costs no AEAD work.
        self.replay.check(counter)?;

        let cipher = ChaCha20Poly1305::new(Key::from_slice(&self.receiving_key));
        let plaintext = cipher
            .decrypt(
                Nonce::from_slice(&nonce_bytes(counter)),
                Payload {
                    msg: &framed[8..],
                    aad: &[],
                },
            )
            .map_err(|_| CryptoError::Aead)?;

        self.replay.record(counter);
        Ok(plaintext)
    }
}

/// Noise's ChaChaPoly nonce: four zero bytes, then the counter little-endian.
fn nonce_bytes(counter: u64) -> [u8; 12] {
    let mut nonce = [0u8; 12];
    nonce[4..].copy_from_slice(&counter.to_le_bytes());
    nonce
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair() -> (TransportSession, TransportSession) {
        let split = ([1u8; 32], [2u8; 32]);
        let mut a = TransportSession::from_split(split, true, [9u8; 32], 0);
        let mut b = TransportSession::from_split(split, false, [8u8; 32], 0);
        for session in [&mut a, &mut b] {
            session.reserve_for_export();
            session.commit_reservation();
        }
        (a, b)
    }

    /// Exactly what a restart does: read the bytes back and resume.
    fn restore(bytes: &[u8]) -> TransportSession {
        let mut restored: TransportSession = bincode::deserialize(bytes).unwrap();
        restored.resume_after_restore();
        restored
    }

    fn counter_of(frame: &[u8]) -> u64 {
        u64::from_le_bytes(frame[..8].try_into().unwrap())
    }

    #[test]
    fn round_trips_in_both_directions() {
        let (mut a, mut b) = pair();
        let ct = a.encrypt(b"regroup at the north gate").unwrap();
        assert_eq!(b.decrypt(&ct).unwrap(), b"regroup at the north gate");

        let ct = b.encrypt(b"understood").unwrap();
        assert_eq!(a.decrypt(&ct).unwrap(), b"understood");
    }

    #[test]
    fn a_session_survives_serialization() {
        let (mut a, b) = pair();
        let ct = a.encrypt(b"still here").unwrap();

        // Exactly what persisting to the vault and reloading does.
        let bytes = bincode::serialize(&b).unwrap();
        let mut restored: TransportSession = bincode::deserialize(&bytes).unwrap();

        assert_eq!(restored.decrypt(&ct).unwrap(), b"still here");
        assert_eq!(restored.peer_node_id(), b.peer_node_id());
    }

    #[test]
    fn a_restored_session_still_rejects_replays() {
        let (mut a, mut b) = pair();
        let ct = a.encrypt(b"one").unwrap();
        b.decrypt(&ct).unwrap();

        let bytes = bincode::serialize(&b).unwrap();
        let mut restored: TransportSession = bincode::deserialize(&bytes).unwrap();

        // The replay window must be part of what is persisted, or a restart
        // would reopen every counter the peer has already used.
        assert!(matches!(
            restored.decrypt(&ct),
            Err(CryptoError::ReplayedNonce)
        ));
    }

    /// REVIEW C1: persist n, encrypt with n, lose the process, restore, encrypt.
    #[test]
    fn a_restored_snapshot_cannot_repeat_a_counter() {
        let (mut a, mut b) = pair();
        a.reserve_for_export();
        let snapshot = bincode::serialize(&a).unwrap();
        a.commit_reservation();

        let first = a.encrypt(b"message A").unwrap();

        // The process dies here, and only the snapshot survives.
        let mut restored = restore(&snapshot);
        assert!(matches!(
            restored.encrypt(b"message B"),
            Err(CryptoError::NonceReservationExhausted)
        ));

        restored.reserve_for_export();
        restored.commit_reservation();
        let second = restored.encrypt(b"message B").unwrap();

        assert!(counter_of(&second) > counter_of(&first));
        assert_eq!(b.decrypt(&first).unwrap(), b"message A");
        assert_eq!(b.decrypt(&second).unwrap(), b"message B");
    }

    #[test]
    fn a_new_session_cannot_send_before_a_durable_write() {
        let split = ([1u8; 32], [2u8; 32]);
        let mut a = TransportSession::from_split(split, true, [9u8; 32], 0);
        assert!(matches!(
            a.encrypt(b"too soon"),
            Err(CryptoError::NonceReservationExhausted)
        ));

        // Reserving is not enough: the write may still fail.
        a.reserve_for_export();
        assert!(matches!(
            a.encrypt(b"too soon"),
            Err(CryptoError::NonceReservationExhausted)
        ));

        a.commit_reservation();
        a.encrypt(b"now it is safe").unwrap();
    }

    #[test]
    fn a_reservation_runs_out_rather_than_outrunning_the_disk() {
        let (mut a, _b) = pair();
        for _ in 0..NONCE_RESERVATION_BLOCK {
            a.encrypt(b"x").unwrap();
        }
        assert_eq!(a.spendable_counters(), 0);
        assert!(matches!(
            a.encrypt(b"x"),
            Err(CryptoError::NonceReservationExhausted)
        ));
    }

    /// The REVIEW section 5 gate: no repeated key/nonce across 10,000 injected
    /// crash schedules, with the crash landing before the write, after the write
    /// but before the commit, or after the commit. The generator is seeded so a
    /// failure reproduces.
    #[test]
    fn no_counter_repeats_across_ten_thousand_crash_schedules() {
        let mut rng = 0x9E37_79B9_7F4A_7C15u64;
        let mut next = move |bound: u64| {
            rng ^= rng << 13;
            rng ^= rng >> 7;
            rng ^= rng << 17;
            rng % bound
        };

        let mut crashes = 0u64;
        for _ in 0..10_000 {
            let split = ([1u8; 32], [2u8; 32]);
            let mut live = TransportSession::from_split(split, true, [9u8; 32], 0);
            let mut disk = bincode::serialize(&live).unwrap();
            let mut used = std::collections::HashSet::new();

            for _ in 0..next(4) + 1 {
                live.reserve_for_export();
                let exported = bincode::serialize(&live).unwrap();
                let wrote = next(4) != 0;
                if wrote {
                    disk = exported;
                    if next(4) == 0 {
                        crashes += 1;
                        live = restore(&disk);
                        continue;
                    }
                    live.commit_reservation();
                }

                // Mostly short bursts, with enough long ones to hit the limit.
                let burst = if next(8) == 0 {
                    NONCE_RESERVATION_BLOCK * 2
                } else {
                    next(4)
                };
                for _ in 0..burst {
                    match live.encrypt(b"x") {
                        Ok(frame) => assert!(
                            used.insert(counter_of(&frame)),
                            "counter {} was used twice",
                            counter_of(&frame)
                        ),
                        Err(CryptoError::NonceReservationExhausted) => break,
                        Err(other) => panic!("unexpected failure: {other}"),
                    }
                }
                if next(2) == 0 {
                    crashes += 1;
                    live = restore(&disk);
                }
            }
        }
        assert!(crashes > 10_000, "the schedules must really crash");
    }

    #[test]
    fn a_crash_stays_inside_what_the_replay_window_tolerates() {
        let (mut a, mut b) = pair();
        let delayed = a.encrypt(b"late, but still valid").unwrap();

        a.reserve_for_export();
        let mut restored = restore(&bincode::serialize(&a).unwrap());
        restored.reserve_for_export();
        restored.commit_reservation();
        let after_crash = restored.encrypt(b"after the crash").unwrap();

        b.decrypt(&after_crash).unwrap();
        assert_eq!(b.decrypt(&delayed).unwrap(), b"late, but still valid");
    }

    #[test]
    fn tampered_ciphertext_is_rejected() {
        let (mut a, mut b) = pair();
        let mut ct = a.encrypt(b"water at the depot").unwrap();
        let last = ct.len() - 1;
        ct[last] ^= 0x01;
        assert!(matches!(b.decrypt(&ct), Err(CryptoError::Aead)));
    }

    #[test]
    fn out_of_order_delivery_still_decrypts() {
        let (mut a, mut b) = pair();
        let first = a.encrypt(b"first").unwrap();
        let second = a.encrypt(b"second").unwrap();
        let third = a.encrypt(b"third").unwrap();

        assert_eq!(b.decrypt(&third).unwrap(), b"third");
        assert_eq!(b.decrypt(&first).unwrap(), b"first");
        assert_eq!(b.decrypt(&second).unwrap(), b"second");
    }

    #[test]
    fn replaying_any_of_them_fails() {
        let (mut a, mut b) = pair();
        let msgs: Vec<_> = (0..5).map(|_| a.encrypt(b"x").unwrap()).collect();
        for m in &msgs {
            b.decrypt(m).unwrap();
        }
        for m in &msgs {
            assert!(matches!(b.decrypt(m), Err(CryptoError::ReplayedNonce)));
        }
    }

    #[test]
    fn window_accepts_lag_up_to_the_limit_and_rejects_beyond() {
        let mut window = ReplayWindow::new();
        window.record(1000);

        // Just inside the window.
        window.check(1000 - (REPLAY_WINDOW - 1)).unwrap();
        // One step too far back.
        assert!(window.check(1000 - REPLAY_WINDOW).is_err());
    }

    #[test]
    fn window_shift_across_word_boundaries_keeps_recent_history() {
        let mut window = ReplayWindow::new();
        window.record(0);
        window.record(100);
        window.record(200);

        // A big jump that leaves 200 inside the window but drops 0 out of it.
        window.record(400);
        assert!(window.check(200).is_err(), "200 must still be remembered");
        assert!(window.check(100).is_err(), "100 must still be remembered");
        assert!(window.check(0).is_err(), "0 is now older than the window");

        // A counter never seen, inside the window, must be accepted.
        window.check(300).unwrap();
    }

    #[test]
    fn a_huge_jump_clears_the_window_without_panicking() {
        let mut window = ReplayWindow::new();
        window.record(5);
        window.record(u64::MAX / 2);
        window.check(u64::MAX / 2 - 1).unwrap();
        assert!(window.check(5).is_err());
    }

    #[test]
    fn keys_are_directional() {
        // Feeding a session its own ciphertext must fail: sending and receiving
        // keys are distinct, which is what stops reflection attacks.
        let (mut a, _b) = pair();
        let ct = a.encrypt(b"echo").unwrap();
        assert!(a.decrypt(&ct).is_err());
    }

    #[test]
    fn oversized_plaintext_is_refused() {
        let (mut a, _b) = pair();
        let huge = vec![0u8; MAX_TRANSPORT_MESSAGE];
        assert!(matches!(
            a.encrypt(&huge),
            Err(CryptoError::MessageTooLarge)
        ));
    }
}
