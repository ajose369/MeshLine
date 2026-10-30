//! Authenticated session establishment for MeshLine.
//!
//! This is a real Noise_XX handshake driven by the `snow` crate rather than a
//! hand-rolled Diffie-Hellman ladder. Noise gives us mutual authentication,
//! forward secrecy, and a hashed transcript that binds every handshake message.
//!
//! Noise on its own authenticates the *Noise static key*, which says nothing
//! about which mesh node you are talking to. We close that gap by carrying an
//! [`IdentityProof`] in the handshake payload: each side signs its own Noise
//! static public key with its long-lived Ed25519 mesh identity. A peer is only
//! accepted once that signature checks out, which is what ties a live session to
//! the `sender_id` seen on signed broadcast packets.
//!
//! What none of this can establish is that the mesh identity on the other end
//! belongs to the person you think it does. That is what [`super::trust`] and an
//! out-of-band safety number are for.

use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use snow::{Builder, HandshakeState};
use std::collections::HashMap;

use super::session::TransportSession;
use super::CryptoError;
use crate::packet::schema::node_id_from_pubkey;

/// Noise pattern. XX is the right choice here: neither side knows the other's
/// static key in advance, which is exactly the situation for strangers meeting
/// over a disaster mesh or a crowd.
pub const NOISE_PARAMS: &str = "Noise_XX_25519_ChaChaPoly_SHA256";

/// Domain separator for the identity binding signature, so a signature minted
/// here can never be replayed as a packet signature.
const BINDING_CONTEXT: &[u8] = b"meshline/noise-identity-binding/v2";

/// Ed25519 public key (32) + signature over the bound Noise static key (64).
pub const IDENTITY_PROOF_LEN: usize = 96;

/// Largest handshake message we will process.
pub const MAX_NOISE_MESSAGE: usize = 4096;

/// Cap on simultaneous half-open handshakes.
///
/// Every inbound message 1 from an unknown peer costs a `HandshakeState` and a
/// Diffie-Hellman. Without a ceiling, anyone with a radio could pin a phone's
/// memory and CPU by opening handshakes it never intends to finish.
pub const MAX_PENDING_HANDSHAKES: usize = 32;

/// Proof that the holder of a mesh identity key also holds a given Noise static
/// key. Serialized as a fixed 96-byte blob and carried in the handshake payload.
#[derive(Debug, Clone)]
pub struct IdentityProof {
    pub identity_pubkey: [u8; 32],
    pub signature: [u8; 64],
}

impl IdentityProof {
    /// Signs `noise_static_pub` with the node's long-lived identity key.
    pub fn create(signing_key: &SigningKey, noise_static_pub: &[u8]) -> Self {
        let signature = signing_key.sign(&binding_message(noise_static_pub));
        Self {
            identity_pubkey: signing_key.verifying_key().to_bytes(),
            signature: signature.to_bytes(),
        }
    }

    pub fn to_bytes(&self) -> [u8; IDENTITY_PROOF_LEN] {
        let mut out = [0u8; IDENTITY_PROOF_LEN];
        out[..32].copy_from_slice(&self.identity_pubkey);
        out[32..].copy_from_slice(&self.signature);
        out
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, CryptoError> {
        if bytes.len() != IDENTITY_PROOF_LEN {
            return Err(CryptoError::MalformedIdentityProof);
        }
        let mut identity_pubkey = [0u8; 32];
        let mut signature = [0u8; 64];
        identity_pubkey.copy_from_slice(&bytes[..32]);
        signature.copy_from_slice(&bytes[32..]);
        Ok(Self {
            identity_pubkey,
            signature,
        })
    }

    /// Checks the proof against the Noise static key the handshake actually
    /// authenticated. Passing the remote static from `snow` (rather than one
    /// the peer claims) is what makes this binding meaningful.
    pub fn verify(&self, authenticated_noise_static: &[u8]) -> Result<(), CryptoError> {
        let vk = VerifyingKey::from_bytes(&self.identity_pubkey)
            .map_err(|_| CryptoError::UnauthenticatedPeer)?;
        let sig = Signature::from_bytes(&self.signature);
        vk.verify_strict(&binding_message(authenticated_noise_static), &sig)
            .map_err(|_| CryptoError::UnauthenticatedPeer)
    }

    pub fn node_id(&self) -> [u8; 16] {
        node_id_from_pubkey(&self.identity_pubkey)
    }
}

fn binding_message(noise_static_pub: &[u8]) -> Vec<u8> {
    let mut msg = Vec::with_capacity(BINDING_CONTEXT.len() + noise_static_pub.len());
    msg.extend_from_slice(BINDING_CONTEXT);
    msg.extend_from_slice(noise_static_pub);
    msg
}

/// An in-progress Noise_XX handshake.
///
/// XX is three messages: `-> e`, `<- e, ee, s, es`, `-> s, se`. Drive it by
/// alternating [`write_message`](Self::write_message) and
/// [`read_message`](Self::read_message) until [`is_finished`](Self::is_finished),
/// then call [`into_transport`](Self::into_transport).
pub struct HandshakeSession {
    state: Option<HandshakeState>,
    is_initiator: bool,
    identity_proof: [u8; IDENTITY_PROOF_LEN],
    peer_identity: Option<[u8; 32]>,
    writes_done: usize,
    /// Unix seconds when this handshake started, so stale half-open attempts can
    /// be evicted rather than held forever.
    started_at: u64,
}

impl HandshakeSession {
    pub fn new(
        signing_key: &SigningKey,
        is_initiator: bool,
        now: u64,
    ) -> Result<Self, CryptoError> {
        let params = NOISE_PARAMS
            .parse()
            .map_err(|e: snow::Error| CryptoError::Noise(e.to_string()))?;
        let builder = Builder::new(params);
        let keypair = builder
            .generate_keypair()
            .map_err(|e| CryptoError::Noise(e.to_string()))?;
        let proof = IdentityProof::create(signing_key, &keypair.public).to_bytes();

        let builder = Builder::new(
            NOISE_PARAMS
                .parse()
                .map_err(|e: snow::Error| CryptoError::Noise(e.to_string()))?,
        )
        .local_private_key(&keypair.private);

        let state = if is_initiator {
            builder.build_initiator()
        } else {
            builder.build_responder()
        }
        .map_err(|e| CryptoError::Noise(e.to_string()))?;

        Ok(Self {
            state: Some(state),
            is_initiator,
            identity_proof: proof,
            peer_identity: None,
            writes_done: 0,
            started_at: now,
        })
    }

    pub fn initiator(signing_key: &SigningKey, now: u64) -> Result<Self, CryptoError> {
        Self::new(signing_key, true, now)
    }

    pub fn responder(signing_key: &SigningKey, now: u64) -> Result<Self, CryptoError> {
        Self::new(signing_key, false, now)
    }

    pub fn is_initiator(&self) -> bool {
        self.is_initiator
    }

    pub fn is_finished(&self) -> bool {
        self.state
            .as_ref()
            .map(|s| s.is_handshake_finished())
            .unwrap_or(false)
    }

    /// Produces the next handshake message, carrying our identity proof as the
    /// Noise payload. In XX the payload is only encrypted from message 2
    /// onward; message 1 carries none, which is why we skip it there.
    pub fn write_message(&mut self) -> Result<Vec<u8>, CryptoError> {
        let state = self
            .state
            .as_mut()
            .ok_or(CryptoError::HandshakeAlreadyComplete)?;

        // The initiator's first message (`-> e`) has no key material yet, so an
        // identity proof there would travel in the clear and announce to any
        // listener exactly who is calling out. Every later message is encrypted.
        let payload: &[u8] = if self.is_initiator && self.writes_done == 0 {
            &[]
        } else {
            &self.identity_proof
        };

        let mut buf = vec![0u8; MAX_NOISE_MESSAGE];
        let len = state
            .write_message(payload, &mut buf)
            .map_err(|e| CryptoError::Noise(e.to_string()))?;
        buf.truncate(len);
        self.writes_done += 1;
        Ok(buf)
    }

    /// Consumes a handshake message from the peer, verifying the identity proof
    /// as soon as one is present.
    pub fn read_message(&mut self, message: &[u8]) -> Result<(), CryptoError> {
        if message.len() > MAX_NOISE_MESSAGE {
            return Err(CryptoError::MessageTooLarge);
        }
        let state = self
            .state
            .as_mut()
            .ok_or(CryptoError::HandshakeAlreadyComplete)?;

        let mut buf = vec![0u8; MAX_NOISE_MESSAGE];
        let len = state
            .read_message(message, &mut buf)
            .map_err(|e| CryptoError::Noise(e.to_string()))?;
        buf.truncate(len);

        if !buf.is_empty() {
            let proof = IdentityProof::from_bytes(&buf)?;
            let remote_static = state
                .get_remote_static()
                .ok_or(CryptoError::UnauthenticatedPeer)?;
            proof.verify(remote_static)?;
            self.peer_identity = Some(proof.identity_pubkey);
        }
        Ok(())
    }

    /// Finishes the handshake. Fails closed if the peer never presented a
    /// verified identity proof, so an anonymous session can never be used.
    ///
    /// The split keys are taken out of `snow` rather than using its own
    /// transport state, because ours can be persisted; see
    /// [`super::session`] for why that matters and what it costs.
    pub fn into_transport(mut self, now: u64) -> Result<TransportSession, CryptoError> {
        let mut state = self.state.take().ok_or(CryptoError::HandshakeIncomplete)?;
        if !state.is_handshake_finished() {
            return Err(CryptoError::HandshakeIncomplete);
        }
        let peer_identity = self.peer_identity.ok_or(CryptoError::UnauthenticatedPeer)?;
        let split = state.dangerously_get_raw_split();

        Ok(TransportSession::from_split(
            split,
            self.is_initiator,
            peer_identity,
            now,
        ))
    }

    pub fn peer_identity(&self) -> Option<[u8; 32]> {
        self.peer_identity
    }

    pub fn started_at(&self) -> u64 {
        self.started_at
    }
}

/// Tracks handshakes and live sessions per peer.
#[derive(Default)]
pub struct SessionManager {
    pending: HashMap<[u8; 16], HandshakeSession>,
    established: HashMap<[u8; 16], TransportSession>,
}

impl SessionManager {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn has_session(&self, peer: &[u8; 16]) -> bool {
        self.established.contains_key(peer)
    }

    pub fn established_peers(&self) -> Vec<[u8; 16]> {
        self.established.keys().copied().collect()
    }

    pub fn session(&self, peer: &[u8; 16]) -> Option<&TransportSession> {
        self.established.get(peer)
    }

    /// The verified identity key behind an established session, for safety
    /// number derivation.
    pub fn peer_identity_pubkey(&self, peer: &[u8; 16]) -> Option<[u8; 32]> {
        self.established.get(peer).map(|s| s.peer_identity_pubkey())
    }

    /// Starts an outbound handshake, returning Noise message 1.
    pub fn begin_handshake(
        &mut self,
        peer: [u8; 16],
        signing_key: &SigningKey,
        now: u64,
    ) -> Result<Vec<u8>, CryptoError> {
        self.evict_stale_pending(now);
        let mut session = HandshakeSession::initiator(signing_key, now)?;
        let msg = session.write_message()?;
        self.pending.insert(peer, session);
        Ok(msg)
    }

    /// Feeds an inbound handshake message, returning our reply if the pattern
    /// calls for one. Promotes the session to established on completion.
    pub fn accept_handshake(
        &mut self,
        peer: [u8; 16],
        message: &[u8],
        signing_key: &SigningKey,
        now: u64,
    ) -> Result<Option<Vec<u8>>, CryptoError> {
        let mut session = match self.pending.remove(&peer) {
            Some(s) => s,
            None => {
                self.evict_stale_pending(now);
                if self.pending.len() >= MAX_PENDING_HANDSHAKES {
                    return Err(CryptoError::HandshakeIncomplete);
                }
                HandshakeSession::responder(signing_key, now)?
            }
        };

        session.read_message(message)?;

        if session.is_finished() {
            self.promote(session, now)?;
            return Ok(None);
        }

        let reply = session.write_message()?;

        if session.is_finished() {
            self.promote(session, now)?;
        } else {
            self.pending.insert(peer, session);
        }
        Ok(Some(reply))
    }

    /// Keys the established session by the *verified* peer identity rather than
    /// the transport-supplied peer hint, so a lying transport cannot bind a
    /// session to somebody else's node id.
    fn promote(&mut self, session: HandshakeSession, now: u64) -> Result<(), CryptoError> {
        let transport = session.into_transport(now)?;
        self.established.insert(transport.peer_node_id(), transport);
        Ok(())
    }

    /// Drops half-open handshakes that will never complete. Ten minutes is far
    /// longer than three radio round trips and short enough that an attacker
    /// cannot accumulate state.
    fn evict_stale_pending(&mut self, now: u64) {
        const PENDING_TTL_SECS: u64 = 600;
        self.pending
            .retain(|_, s| now.saturating_sub(s.started_at()) < PENDING_TTL_SECS);
    }

    pub fn encrypt_for(
        &mut self,
        peer: &[u8; 16],
        plaintext: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        self.established
            .get_mut(peer)
            .ok_or(CryptoError::NoSession)?
            .encrypt(plaintext)
    }

    pub fn decrypt_from(
        &mut self,
        peer: &[u8; 16],
        framed: &[u8],
    ) -> Result<Vec<u8>, CryptoError> {
        self.established
            .get_mut(peer)
            .ok_or(CryptoError::NoSession)?
            .decrypt(framed)
    }

    pub fn drop_session(&mut self, peer: &[u8; 16]) {
        self.pending.remove(peer);
        self.established.remove(peer);
    }

    /// Forgets everything. Used by the panic wipe and when the vault key is
    /// rotated.
    pub fn clear(&mut self) {
        self.pending.clear();
        self.established.clear();
    }

    /// Borrowed established sessions, for sealing into the vault without
    /// copying key material into a second buffer first.
    pub fn sessions_for_export(&self) -> Vec<&TransportSession> {
        self.established.values().collect()
    }

    /// Reinstalls sessions read back from the vault, keyed by the identity that
    /// was verified when each handshake completed.
    pub fn install_sessions(&mut self, sessions: Vec<TransportSession>) {
        for session in sessions {
            self.established.insert(session.peer_node_id(), session);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rand::rngs::OsRng;

    const NOW: u64 = 1_700_000_000;

    fn keypair() -> SigningKey {
        SigningKey::generate(&mut OsRng)
    }

    /// Drives the full XX exchange between two managers.
    fn connect(
        alice_key: &SigningKey,
        bob_key: &SigningKey,
    ) -> (SessionManager, SessionManager, [u8; 16], [u8; 16]) {
        let alice_id = node_id_from_pubkey(&alice_key.verifying_key().to_bytes());
        let bob_id = node_id_from_pubkey(&bob_key.verifying_key().to_bytes());

        let mut alice = SessionManager::new();
        let mut bob = SessionManager::new();

        let msg1 = alice.begin_handshake(bob_id, alice_key, NOW).unwrap();
        let msg2 = bob
            .accept_handshake(alice_id, &msg1, bob_key, NOW)
            .unwrap()
            .unwrap();
        let msg3 = alice
            .accept_handshake(bob_id, &msg2, alice_key, NOW)
            .unwrap()
            .unwrap();
        assert!(bob
            .accept_handshake(alice_id, &msg3, bob_key, NOW)
            .unwrap()
            .is_none());

        (alice, bob, alice_id, bob_id)
    }

    #[test]
    fn xx_handshake_establishes_both_directions() {
        let (a_key, b_key) = (keypair(), keypair());
        let (mut alice, mut bob, alice_id, bob_id) = connect(&a_key, &b_key);

        assert!(alice.has_session(&bob_id));
        assert!(bob.has_session(&alice_id));

        let ct = alice.encrypt_for(&bob_id, b"meet at the school gate").unwrap();
        let pt = bob.decrypt_from(&alice_id, &ct).unwrap();
        assert_eq!(pt, b"meet at the school gate");

        let ct = bob.encrypt_for(&alice_id, b"on my way").unwrap();
        let pt = alice.decrypt_from(&bob_id, &ct).unwrap();
        assert_eq!(pt, b"on my way");
    }

    #[test]
    fn ciphertext_does_not_leak_plaintext() {
        let (a_key, b_key) = (keypair(), keypair());
        let (mut alice, _bob, _alice_id, bob_id) = connect(&a_key, &b_key);
        let secret = b"trapped in basement 3";
        let ct = alice.encrypt_for(&bob_id, secret).unwrap();
        assert!(!ct.windows(secret.len()).any(|w| w == secret));
    }

    #[test]
    fn identity_is_bound_to_the_noise_static_key() {
        let key = keypair();
        let proof = IdentityProof::create(&key, &[9u8; 32]);
        proof.verify(&[9u8; 32]).unwrap();
        // Same proof replayed against a different Noise key must not verify.
        assert!(matches!(
            proof.verify(&[8u8; 32]),
            Err(CryptoError::UnauthenticatedPeer)
        ));
    }

    #[test]
    fn proof_from_one_identity_cannot_be_reused_by_another() {
        let (honest, attacker) = (keypair(), keypair());
        let mut proof = IdentityProof::create(&honest, &[1u8; 32]);
        proof.identity_pubkey = attacker.verifying_key().to_bytes();
        assert!(proof.verify(&[1u8; 32]).is_err());
    }

    #[test]
    fn tampered_ciphertext_is_rejected() {
        let (a_key, b_key) = (keypair(), keypair());
        let (mut alice, mut bob, alice_id, bob_id) = connect(&a_key, &b_key);
        let mut ct = alice.encrypt_for(&bob_id, b"water at the depot").unwrap();
        let last = ct.len() - 1;
        ct[last] ^= 0x01;
        assert!(bob.decrypt_from(&alice_id, &ct).is_err());
    }

    #[test]
    fn replayed_message_is_rejected() {
        let (a_key, b_key) = (keypair(), keypair());
        let (mut alice, mut bob, alice_id, bob_id) = connect(&a_key, &b_key);
        let ct = alice.encrypt_for(&bob_id, b"send help").unwrap();
        bob.decrypt_from(&alice_id, &ct).unwrap();
        assert!(matches!(
            bob.decrypt_from(&alice_id, &ct),
            Err(CryptoError::ReplayedNonce)
        ));
    }

    #[test]
    fn out_of_order_delivery_still_decrypts() {
        let (a_key, b_key) = (keypair(), keypair());
        let (mut alice, mut bob, alice_id, bob_id) = connect(&a_key, &b_key);
        let first = alice.encrypt_for(&bob_id, b"first").unwrap();
        let second = alice.encrypt_for(&bob_id, b"second").unwrap();
        let third = alice.encrypt_for(&bob_id, b"third").unwrap();

        assert_eq!(bob.decrypt_from(&alice_id, &third).unwrap(), b"third");
        assert_eq!(bob.decrypt_from(&alice_id, &first).unwrap(), b"first");
        assert_eq!(bob.decrypt_from(&alice_id, &second).unwrap(), b"second");
    }

    #[test]
    fn no_session_means_no_plaintext_fallback() {
        let mut alice = SessionManager::new();
        assert!(matches!(
            alice.encrypt_for(&[0u8; 16], b"hello"),
            Err(CryptoError::NoSession)
        ));
    }

    #[test]
    fn session_is_keyed_by_verified_identity_not_transport_hint() {
        let (a_key, b_key) = (keypair(), keypair());
        let alice_id = node_id_from_pubkey(&a_key.verifying_key().to_bytes());
        let bob_id = node_id_from_pubkey(&b_key.verifying_key().to_bytes());

        let mut alice = SessionManager::new();
        let mut bob = SessionManager::new();

        // Bob is told the peer is some bogus id; the session must still land
        // under Alice's real, cryptographically verified node id.
        let lie = [0xEE; 16];
        let msg1 = alice.begin_handshake(bob_id, &a_key, NOW).unwrap();
        let msg2 = bob.accept_handshake(lie, &msg1, &b_key, NOW).unwrap().unwrap();
        let msg3 = alice
            .accept_handshake(bob_id, &msg2, &a_key, NOW)
            .unwrap()
            .unwrap();
        bob.accept_handshake(lie, &msg3, &b_key, NOW).unwrap();

        assert!(bob.has_session(&alice_id));
        assert!(!bob.has_session(&lie));
    }

    #[test]
    fn sessions_survive_export_and_reinstall() {
        let (a_key, b_key) = (keypair(), keypair());
        let (mut alice, bob, alice_id, bob_id) = connect(&a_key, &b_key);
        let ct = alice.encrypt_for(&bob_id, b"still connected").unwrap();

        // Exactly the round trip the vault performs across an app restart.
        let blob = bincode::serialize(&bob.sessions_for_export()).unwrap();
        let restored: Vec<TransportSession> = bincode::deserialize(&blob).unwrap();

        let mut bob_after_restart = SessionManager::new();
        bob_after_restart.install_sessions(restored);

        assert!(bob_after_restart.has_session(&alice_id));
        assert_eq!(
            bob_after_restart.decrypt_from(&alice_id, &ct).unwrap(),
            b"still connected"
        );
    }

    #[test]
    fn the_identity_key_behind_a_session_is_recoverable() {
        let (a_key, b_key) = (keypair(), keypair());
        let (_alice, bob, alice_id, _bob_id) = connect(&a_key, &b_key);
        assert_eq!(
            bob.peer_identity_pubkey(&alice_id),
            Some(a_key.verifying_key().to_bytes()),
            "the safety number is derived from this, so it must be the real key"
        );
    }

    #[test]
    fn half_open_handshakes_are_bounded() {
        let bob_key = keypair();
        let mut bob = SessionManager::new();

        // A flood of message-1s from distinct fake peers must not grow forever.
        for i in 0..(MAX_PENDING_HANDSHAKES + 8) {
            let attacker = keypair();
            let mut attacker_mgr = SessionManager::new();
            let peer = node_id_from_pubkey(&attacker.verifying_key().to_bytes());
            let msg1 = attacker_mgr.begin_handshake([i as u8; 16], &attacker, NOW).unwrap();
            let _ = bob.accept_handshake(peer, &msg1, &bob_key, NOW);
        }
        assert!(bob.pending.len() <= MAX_PENDING_HANDSHAKES);
    }

    #[test]
    fn stale_half_open_handshakes_are_evicted() {
        let bob_key = keypair();
        let attacker = keypair();
        let mut attacker_mgr = SessionManager::new();
        let mut bob = SessionManager::new();

        let peer = node_id_from_pubkey(&attacker.verifying_key().to_bytes());
        let msg1 = attacker_mgr.begin_handshake([1u8; 16], &attacker, NOW).unwrap();
        bob.accept_handshake(peer, &msg1, &bob_key, NOW).unwrap();
        assert_eq!(bob.pending.len(), 1);

        // An hour later a fresh handshake sweeps the abandoned one away.
        let other = keypair();
        let mut other_mgr = SessionManager::new();
        let other_peer = node_id_from_pubkey(&other.verifying_key().to_bytes());
        let msg1 = other_mgr.begin_handshake([2u8; 16], &other, NOW + 3600).unwrap();
        bob.accept_handshake(other_peer, &msg1, &bob_key, NOW + 3600)
            .unwrap();

        assert_eq!(bob.pending.len(), 1, "the abandoned handshake must be gone");
    }

    #[test]
    fn clearing_removes_every_session() {
        let (a_key, b_key) = (keypair(), keypair());
        let (mut alice, _bob, _alice_id, bob_id) = connect(&a_key, &b_key);
        alice.clear();
        assert!(!alice.has_session(&bob_id));
        assert!(matches!(
            alice.encrypt_for(&bob_id, b"after wipe"),
            Err(CryptoError::NoSession)
        ));
    }
}
