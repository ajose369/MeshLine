//! Out-of-band identity verification.
//!
//! Noise_XX authenticates that both ends hold the static keys they used, and the
//! identity proof in the handshake binds those to long-lived mesh identities.
//! Neither says anything about *who the human on the other end is*. Two strangers
//! meeting for the first time have no prior key to compare against, so an active
//! attacker sitting between them at that first contact is authenticated to both
//! sides and invisible to both.
//!
//! The only fix is out-of-band comparison, so this module derives a **safety
//! number**: a 60-digit value computed from both identity keys that both devices
//! display identically. Reading it aloud, or comparing two screens side by side,
//! is what a mesh cannot do for you. Until that happens the session is
//! cryptographically sound but socially unverified, and the UI says so.
//!
//! The derivation follows the numeric-fingerprint construction popularised by
//! Signal: an iterated hash, deliberately slow enough that generating a key whose
//! fingerprint collides in the digits a human actually checks is impractical.

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha512};

use crate::packet::schema::node_id_from_pubkey;

/// Iteration count for fingerprint derivation. The cost is paid once per screen
/// render and makes brute-forcing a near-miss fingerprint expensive.
const FINGERPRINT_ITERATIONS: u32 = 5200;

/// Version prefix, so a future change to the scheme produces visibly different
/// numbers rather than silently comparable ones.
const FINGERPRINT_VERSION: [u8; 2] = [0x00, 0x01];

/// Digits contributed by each party. Two halves make a 60-digit safety number.
const DIGIT_GROUPS_PER_KEY: usize = 6;

/// What the user has told us about a peer's identity.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct VerificationRecord {
    pub identity_pubkey: [u8; 32],
    /// Unix seconds when the user confirmed the safety number, if they ever did.
    pub verified_at: Option<u64>,
    /// Unix seconds when this identity was first seen.
    pub first_seen: u64,
}

impl VerificationRecord {
    pub fn is_verified(&self) -> bool {
        self.verified_at.is_some()
    }
}

/// Per-peer verification state. Persisted with the rest of the secure vault.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct TrustStore {
    records: Vec<([u8; 16], VerificationRecord)>,
}

impl TrustStore {
    pub fn new() -> Self {
        Self::default()
    }

    fn index_of(&self, node_id: &[u8; 16]) -> Option<usize> {
        self.records.iter().position(|(id, _)| id == node_id)
    }

    /// Records that we have seen this identity. Does not imply verification.
    ///
    /// A node id is the truncated hash of the identity key, so a key that does
    /// not hash to the id it claims is either a bug here or a forgery; either
    /// way it is refused rather than stored.
    pub fn observe(&mut self, identity_pubkey: [u8; 32], now: u64) -> Option<[u8; 16]> {
        let node_id = node_id_from_pubkey(&identity_pubkey);
        match self.index_of(&node_id) {
            Some(idx) => {
                if self.records[idx].1.identity_pubkey != identity_pubkey {
                    return None;
                }
            }
            None => self.records.push((
                node_id,
                VerificationRecord {
                    identity_pubkey,
                    verified_at: None,
                    first_seen: now,
                },
            )),
        }
        Some(node_id)
    }

    pub fn get(&self, node_id: &[u8; 16]) -> Option<&VerificationRecord> {
        self.index_of(node_id).map(|i| &self.records[i].1)
    }

    pub fn is_verified(&self, node_id: &[u8; 16]) -> bool {
        self.get(node_id).map(|r| r.is_verified()).unwrap_or(false)
    }

    /// Marks a peer verified or un-verified. Returns false when the peer has
    /// never been seen, because verifying an identity we hold no key for would
    /// be a promise about nothing.
    pub fn set_verified(&mut self, node_id: &[u8; 16], verified: bool, now: u64) -> bool {
        match self.index_of(node_id) {
            Some(idx) => {
                self.records[idx].1.verified_at = if verified { Some(now) } else { None };
                true
            }
            None => false,
        }
    }

    pub fn forget(&mut self, node_id: &[u8; 16]) {
        self.records.retain(|(id, _)| id != node_id);
    }

    pub fn verified_peers(&self) -> Vec<[u8; 16]> {
        self.records
            .iter()
            .filter(|(_, r)| r.is_verified())
            .map(|(id, _)| *id)
            .collect()
    }
}

/// The 30 digits contributed by one identity key.
fn fingerprint_digits(identity_pubkey: &[u8; 32]) -> String {
    let node_id = node_id_from_pubkey(identity_pubkey);

    let mut hash = {
        let mut h = Sha512::new();
        h.update(FINGERPRINT_VERSION);
        h.update(identity_pubkey);
        h.update(node_id);
        h.finalize().to_vec()
    };

    for _ in 0..FINGERPRINT_ITERATIONS {
        let mut h = Sha512::new();
        h.update(&hash);
        h.update(identity_pubkey);
        hash = h.finalize().to_vec();
    }

    // Six groups of five digits, each from 40 bits of the digest.
    let mut out = String::with_capacity(DIGIT_GROUPS_PER_KEY * 5);
    for chunk in hash.chunks(5).take(DIGIT_GROUPS_PER_KEY) {
        let mut value: u64 = 0;
        for byte in chunk {
            value = (value << 8) | u64::from(*byte);
        }
        out.push_str(&format!("{:05}", value % 100_000));
    }
    out
}

/// The safety number two peers should compare out of band.
///
/// The halves are ordered by value rather than by role, so both devices render
/// the same string and neither has to know who initiated.
pub fn safety_number(local_pubkey: &[u8; 32], remote_pubkey: &[u8; 32]) -> String {
    let local = fingerprint_digits(local_pubkey);
    let remote = fingerprint_digits(remote_pubkey);

    if local <= remote {
        format!("{local}{remote}")
    } else {
        format!("{remote}{local}")
    }
}

/// The safety number split into readable groups of five digits.
pub fn safety_number_groups(local_pubkey: &[u8; 32], remote_pubkey: &[u8; 32]) -> Vec<String> {
    let digits = safety_number(local_pubkey, remote_pubkey);
    digits
        .as_bytes()
        .chunks(5)
        .map(|c| String::from_utf8_lossy(c).into_owned())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::SigningKey;
    use rand::rngs::OsRng;

    fn pubkey() -> [u8; 32] {
        SigningKey::generate(&mut OsRng).verifying_key().to_bytes()
    }

    #[test]
    fn both_sides_compute_the_same_safety_number() {
        let (a, b) = (pubkey(), pubkey());
        // Alice computes it as (local=a, remote=b); Bob as (local=b, remote=a).
        assert_eq!(safety_number(&a, &b), safety_number(&b, &a));
    }

    #[test]
    fn safety_number_is_sixty_digits() {
        let number = safety_number(&pubkey(), &pubkey());
        assert_eq!(number.len(), 60);
        assert!(number.chars().all(|c| c.is_ascii_digit()));
    }

    #[test]
    fn a_different_peer_gives_a_different_number() {
        let mine = pubkey();
        let real_peer = pubkey();
        let impostor = pubkey();
        assert_ne!(
            safety_number(&mine, &real_peer),
            safety_number(&mine, &impostor),
            "a man in the middle must not be able to show the same digits"
        );
    }

    #[test]
    fn groups_are_twelve_blocks_of_five() {
        let groups = safety_number_groups(&pubkey(), &pubkey());
        assert_eq!(groups.len(), 12);
        assert!(groups.iter().all(|g| g.len() == 5));
    }

    #[test]
    fn safety_number_is_stable_across_calls() {
        let (a, b) = (pubkey(), pubkey());
        assert_eq!(safety_number(&a, &b), safety_number(&a, &b));
    }

    #[test]
    fn peers_start_unverified() {
        let mut trust = TrustStore::new();
        let key = pubkey();
        let id = trust.observe(key, 100).unwrap();
        assert!(!trust.is_verified(&id));
        assert_eq!(trust.get(&id).unwrap().first_seen, 100);
    }

    #[test]
    fn verification_is_recorded_and_revocable() {
        let mut trust = TrustStore::new();
        let id = trust.observe(pubkey(), 100).unwrap();

        assert!(trust.set_verified(&id, true, 200));
        assert!(trust.is_verified(&id));
        assert_eq!(trust.get(&id).unwrap().verified_at, Some(200));

        // A user who realises they verified the wrong person must be able to
        // take it back.
        assert!(trust.set_verified(&id, false, 300));
        assert!(!trust.is_verified(&id));
    }

    #[test]
    fn an_unknown_peer_cannot_be_marked_verified() {
        let mut trust = TrustStore::new();
        assert!(!trust.set_verified(&[0xAB; 16], true, 100));
    }

    #[test]
    fn observing_the_same_identity_twice_keeps_the_first_seen_time() {
        let mut trust = TrustStore::new();
        let key = pubkey();
        trust.observe(key, 100);
        trust.observe(key, 900);
        let id = node_id_from_pubkey(&key);
        assert_eq!(trust.get(&id).unwrap().first_seen, 100);
    }

    #[test]
    fn verification_survives_serialization() {
        let mut trust = TrustStore::new();
        let id = trust.observe(pubkey(), 100).unwrap();
        trust.set_verified(&id, true, 200);

        let bytes = bincode::serialize(&trust).unwrap();
        let restored: TrustStore = bincode::deserialize(&bytes).unwrap();
        assert!(
            restored.is_verified(&id),
            "a verification the user performed must not be forgotten on restart"
        );
    }
}
