pub mod crypto;
pub mod gis;
pub mod packet;
pub mod routing;
pub mod security;
pub mod transport;

use ed25519_dalek::{Signer, SigningKey, VerifyingKey};
use rand::rngs::OsRng;
use rand::Rng;
use std::sync::{Arc, Mutex};
use thiserror::Error;

pub use crypto::noise::{
    CryptoError, HandshakeSession, IdentityProof, SessionManager, TransportSession,
};
pub use gis::pins::{GisPinStore, PinError, ResourcePin, ResourcePinType};
pub use packet::schema::{
    node_id_from_pubkey, LocationTag, Packet, PacketError, PacketHeader, PacketType,
    MAX_PAYLOAD_BYTES, PROTOCOL_VERSION,
};
pub use routing::flood::{BatteryPowerState, RoutingEngine, RoutingError};
pub use security::rate_limit::{MeshRateLimiter, ProofOfWork};
pub use transport::lora::{LoraFrameHeader, MeshtasticBridgeFrame};

/// Proof-of-work difficulty required of public SOS broadcasts. Low enough that
/// a phone solves it in well under a second, high enough to blunt trivial
/// flooding. Anything that costs a distressed user real time is the wrong trade.
pub const SOS_POW_BITS: u8 = 12;

/// Default hop limit for originated traffic.
pub const DEFAULT_TTL: u8 = 8;

#[derive(Error, Debug)]
pub enum MeshError {
    #[error("packet error: {0}")]
    Packet(#[from] PacketError),
    #[error("routing: {0}")]
    Routing(#[from] RoutingError),
    #[error("crypto: {0}")]
    Crypto(#[from] CryptoError),
    #[error("pin: {0}")]
    Pin(#[from] PinError),
    #[error("rate limit exceeded for sender")]
    RateLimited,
    #[error("invalid proof of work")]
    InvalidProofOfWork,
    #[error("proof of work could not be solved within the attempt budget")]
    ProofOfWorkExhausted,
    #[error("payload exceeds maximum size")]
    PayloadTooLarge,
    #[error("no encrypted session with recipient; complete a handshake first")]
    NoSession,
    #[error("serialization: {0}")]
    Serialization(String),
}

/// What a receiver should do with a packet that passed verification.
#[derive(Debug, Clone)]
pub struct ReceiveOutcome {
    /// The verified packet, with TTL already decremented for onward relay.
    pub packet: Packet,
    /// True when this node should rebroadcast it.
    pub should_relay: bool,
    /// Set when the packet was addressed to us and warrants an ACK.
    pub ack: Option<Packet>,
    /// Next Noise handshake message to send back, when the exchange calls for
    /// one. Produced here rather than by a second call, because a handshake
    /// message may only be consumed once.
    pub handshake_reply: Option<Packet>,
    /// Decrypted chat text, present only for Chat packets addressed to us on an
    /// established session.
    pub plaintext: Option<Vec<u8>>,
    /// True when the packet was addressed to this node specifically.
    pub addressed_to_us: bool,
}

pub struct MeshNode {
    signing_key: SigningKey,
    pub verifying_key: VerifyingKey,
    pub node_id: [u8; 16],
    pub routing: Arc<RoutingEngine>,
    pub pin_store: Arc<GisPinStore>,
    pub rate_limiter: Arc<MeshRateLimiter>,
    pub sessions: Arc<Mutex<SessionManager>>,
}

impl MeshNode {
    /// Creates a node with a freshly generated identity.
    ///
    /// Callers that want a stable mesh identity across restarts must persist
    /// [`secret_key_bytes`](Self::secret_key_bytes) and reload it through
    /// [`from_secret_key`](Self::from_secret_key).
    pub fn new() -> Self {
        Self::from_signing_key(SigningKey::generate(&mut OsRng))
    }

    /// Restores a node from a previously persisted identity secret.
    pub fn from_secret_key(secret: &[u8; 32]) -> Self {
        Self::from_signing_key(SigningKey::from_bytes(secret))
    }

    fn from_signing_key(signing_key: SigningKey) -> Self {
        let verifying_key = signing_key.verifying_key();
        let node_id = node_id_from_pubkey(&verifying_key.to_bytes());

        Self {
            signing_key,
            verifying_key,
            node_id,
            routing: Arc::new(RoutingEngine::new(5000)),
            pin_store: Arc::new(GisPinStore::new()),
            rate_limiter: Arc::new(MeshRateLimiter::new(10.0, 2.0)),
            sessions: Arc::new(Mutex::new(SessionManager::new())),
        }
    }

    /// The raw identity secret, for persisting to platform-protected storage.
    /// Treat the returned bytes as key material and wipe them after use.
    pub fn secret_key_bytes(&self) -> [u8; 32] {
        self.signing_key.to_bytes()
    }

    pub fn public_key_bytes(&self) -> [u8; 32] {
        self.verifying_key.to_bytes()
    }

    fn now() -> u64 {
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
    }

    /// Builds and signs a packet originating at this node.
    fn sign_packet(
        &self,
        packet_type: PacketType,
        msg_id: [u8; 16],
        recipient_id: [u8; 16],
        location: Option<LocationTag>,
        pow_nonce: u32,
        payload: Vec<u8>,
    ) -> Result<Packet, MeshError> {
        if payload.len() > MAX_PAYLOAD_BYTES {
            return Err(MeshError::PayloadTooLarge);
        }
        let mut packet = Packet {
            header: PacketHeader {
                version: PROTOCOL_VERSION,
                packet_type,
                ttl: DEFAULT_TTL,
                flags: 0,
                msg_id,
                sender_id: self.node_id,
                sender_pubkey: self.public_key_bytes(),
                recipient_id,
                timestamp: Self::now(),
                location,
                pow_nonce,
            },
            payload,
            signature: [0u8; 64],
        };
        let signing_payload = packet.compute_signing_payload()?;
        packet.signature = self.signing_key.sign(&signing_payload).to_bytes();
        Ok(packet)
    }

    fn random_msg_id() -> [u8; 16] {
        let mut msg_id = [0u8; 16];
        rand::thread_rng().fill(&mut msg_id);
        msg_id
    }

    pub fn create_public_sos(
        &self,
        sos_message: &str,
        lat: f32,
        lon: f32,
    ) -> Result<Packet, MeshError> {
        let location = LocationTag {
            latitude: lat,
            longitude: lon,
            accuracy_meters: 5,
        };
        if !location.is_valid() {
            return Err(MeshError::Packet(PacketError::InvalidLocation));
        }

        let payload_bytes = sos_message.as_bytes().to_vec();
        if payload_bytes.len() > MAX_PAYLOAD_BYTES {
            return Err(MeshError::PayloadTooLarge);
        }
        let pow_nonce = ProofOfWork::solve(&payload_bytes, SOS_POW_BITS)
            .ok_or(MeshError::ProofOfWorkExhausted)?;

        self.sign_packet(
            PacketType::PublicSos,
            Self::random_msg_id(),
            [0u8; 16],
            Some(location),
            pow_nonce,
            payload_bytes,
        )
    }

    pub fn create_signed_pin_packet(
        &self,
        pin_id: [u8; 16],
        pin_type: ResourcePinType,
        lat: f32,
        lon: f32,
        label: &str,
        expires_in_secs: u64,
    ) -> Result<Packet, MeshError> {
        let now = Self::now();
        let location = LocationTag {
            latitude: lat,
            longitude: lon,
            accuracy_meters: 5,
        };
        if !location.is_valid() {
            return Err(MeshError::Packet(PacketError::InvalidLocation));
        }

        let mut pin = ResourcePin {
            pin_id,
            pin_type,
            latitude: lat,
            longitude: lon,
            label: label.to_string(),
            created_at: now,
            expires_at: now.saturating_add(expires_in_secs),
            creator_pubkey: self.public_key_bytes(),
            signature: [0u8; 64],
        };
        pin.signature = self
            .signing_key
            .sign(&pin.compute_signing_payload())
            .to_bytes();

        self.pin_store.upsert_pin(pin.clone(), now)?;

        let payload_bytes =
            bincode::serialize(&pin).map_err(|e| MeshError::Serialization(e.to_string()))?;

        self.sign_packet(
            PacketType::ResourcePin,
            pin_id,
            [0u8; 16],
            Some(location),
            0,
            payload_bytes,
        )
    }

    /// Builds an end-to-end encrypted chat packet.
    ///
    /// Fails with [`MeshError::NoSession`] when no Noise session exists for the
    /// recipient. There is deliberately no plaintext fallback: a chat app that
    /// silently downgrades is worse than one that refuses to send.
    pub fn create_chat_packet(
        &self,
        recipient_id: [u8; 16],
        message: &str,
    ) -> Result<Packet, MeshError> {
        let ciphertext = {
            let mut sessions = self.sessions.lock().expect("session mutex poisoned");
            sessions
                .encrypt_for(&recipient_id, message.as_bytes())
                .map_err(|e| match e {
                    CryptoError::NoSession => MeshError::NoSession,
                    other => MeshError::Crypto(other),
                })?
        };

        self.sign_packet(
            PacketType::Chat,
            Self::random_msg_id(),
            recipient_id,
            None,
            0,
            ciphertext,
        )
    }

    /// Starts a Noise-XX handshake with a peer, returning the packet to send.
    pub fn begin_handshake(&self, peer_id: [u8; 16]) -> Result<Packet, MeshError> {
        let msg = {
            let mut sessions = self.sessions.lock().expect("session mutex poisoned");
            sessions.begin_handshake(peer_id, &self.signing_key)?
        };
        self.sign_packet(
            PacketType::NoiseHandshake,
            Self::random_msg_id(),
            peer_id,
            None,
            0,
            msg,
        )
    }

    pub fn has_session_with(&self, peer_id: &[u8; 16]) -> bool {
        self.sessions
            .lock()
            .expect("session mutex poisoned")
            .has_session(peer_id)
    }

    /// Validates and processes an inbound frame from any transport.
    ///
    /// Order matters and is deliberate: authenticity is established *before*
    /// any work is attributed to the sender, so an attacker cannot exhaust
    /// another node's rate-limit bucket by forging its `sender_id`, and cannot
    /// occupy dedup-cache entries with packets it never had the key to sign.
    pub fn process_incoming(&self, raw_bytes: &[u8]) -> Result<ReceiveOutcome, MeshError> {
        let mut packet = Packet::from_bytes(raw_bytes)?;

        // 1. Authenticity first. Everything downstream trusts sender_id.
        packet.verify()?;

        // 2. Freshness, to bound replay of long-dead traffic.
        let now = Self::now();
        packet.check_freshness(now)?;

        // 3. Proof-of-work on unsolicited broadcasts, before any storage cost.
        if packet.header.packet_type == PacketType::PublicSos
            && !ProofOfWork::verify(&packet.payload, packet.header.pow_nonce, SOS_POW_BITS)
        {
            return Err(MeshError::InvalidProofOfWork);
        }

        // 4. Per-sender rate limiting, now that sender_id is proven.
        if !self.rate_limiter.allow_packet(&packet.header.sender_id) {
            return Err(MeshError::RateLimited);
        }

        // 5. Dedup and relay decision.
        let relay_result = self.routing.should_relay(&mut packet);
        let is_duplicate = matches!(relay_result, Err(RoutingError::DuplicatePacket));

        let addressed_to_us = packet.header.recipient_id == self.node_id;

        // 6. Type-specific handling, skipped for packets we have already seen.
        let mut plaintext = None;
        let mut handshake_reply = None;
        if !is_duplicate {
            match packet.header.packet_type {
                PacketType::ResourcePin => {
                    // The pin carries its own creator signature, independent of
                    // the relaying packet, so a relay cannot rewrite a pin.
                    if let Ok(pin) = bincode::deserialize::<ResourcePin>(&packet.payload) {
                        let _ = self.pin_store.upsert_pin(pin, now);
                    }
                }
                PacketType::NoiseHandshake if addressed_to_us => {
                    // The peer id comes from the verified sender_id, and the
                    // session manager re-keys on the identity proven inside the
                    // handshake itself.
                    let reply_bytes = {
                        let mut sessions =
                            self.sessions.lock().expect("session mutex poisoned");
                        sessions
                            .accept_handshake(
                                packet.header.sender_id,
                                &packet.payload,
                                &self.signing_key,
                            )
                            .ok()
                            .flatten()
                    };
                    if let Some(bytes) = reply_bytes {
                        handshake_reply = self
                            .sign_packet(
                                PacketType::NoiseHandshake,
                                Self::random_msg_id(),
                                packet.header.sender_id,
                                None,
                                0,
                                bytes,
                            )
                            .ok();
                    }
                }
                PacketType::Chat if addressed_to_us => {
                    let mut sessions = self.sessions.lock().expect("session mutex poisoned");
                    plaintext = sessions
                        .decrypt_from(&packet.header.sender_id, &packet.payload)
                        .ok();
                }
                _ => {}
            }
        }

        let ack = if addressed_to_us && !is_duplicate {
            match packet.header.packet_type {
                PacketType::Chat | PacketType::PrivateSos => Some(self.create_ack(&packet)?),
                _ => None,
            }
        } else {
            None
        };

        let should_relay = match relay_result {
            Ok(relay) => relay,
            // A duplicate or dampened packet is not an error for the caller;
            // it simply must not be forwarded again.
            Err(RoutingError::DuplicatePacket) | Err(RoutingError::Dampened) => false,
            Err(RoutingError::TtlZero) => false,
        };

        Ok(ReceiveOutcome {
            packet,
            should_relay,
            ack,
            handshake_reply,
            plaintext,
            addressed_to_us,
        })
    }

    fn create_ack(&self, original: &Packet) -> Result<Packet, MeshError> {
        self.sign_packet(
            PacketType::Ack,
            Self::random_msg_id(),
            original.header.sender_id,
            None,
            0,
            original.header.msg_id.to_vec(),
        )
    }

}

impl Default for MeshNode {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wire(packet: &Packet) -> Vec<u8> {
        packet.to_bytes().unwrap()
    }

    #[test]
    fn identity_survives_a_restart() {
        let node = MeshNode::new();
        let secret = node.secret_key_bytes();
        let restored = MeshNode::from_secret_key(&secret);
        assert_eq!(node.node_id, restored.node_id);
        assert_eq!(node.public_key_bytes(), restored.public_key_bytes());
    }

    #[test]
    fn fresh_nodes_have_distinct_identities() {
        assert_ne!(MeshNode::new().node_id, MeshNode::new().node_id);
    }

    #[test]
    fn accepts_a_genuine_sos() {
        let sender = MeshNode::new();
        let receiver = MeshNode::new();
        let sos = sender.create_public_sos("trapped, third floor", 12.9, 77.5).unwrap();

        let outcome = receiver.process_incoming(&wire(&sos)).unwrap();
        assert!(outcome.should_relay);
        assert_eq!(outcome.packet.header.sender_id, sender.node_id);
        // TTL must be decremented exactly once on the way through.
        assert_eq!(outcome.packet.header.ttl, DEFAULT_TTL - 1);
    }

    #[test]
    fn rejects_forged_sos() {
        let sender = MeshNode::new();
        let receiver = MeshNode::new();
        let sos = sender.create_public_sos("help", 12.9, 77.5).unwrap();

        let mut bytes = wire(&sos);
        // Flip a byte inside the signed payload region.
        let idx = bytes.len() - 70;
        bytes[idx] ^= 0xFF;

        assert!(receiver.process_incoming(&bytes).is_err());
    }

    #[test]
    fn rejects_sos_with_rewritten_location() {
        let sender = MeshNode::new();
        let receiver = MeshNode::new();
        let mut sos = sender.create_public_sos("help", 12.9, 77.5).unwrap();
        sos.header.location = Some(LocationTag {
            latitude: -33.0,
            longitude: 151.0,
            accuracy_meters: 5,
        });
        assert!(receiver.process_incoming(&wire(&sos)).is_err());
    }

    #[test]
    fn rejects_sos_without_proof_of_work() {
        let sender = MeshNode::new();
        let receiver = MeshNode::new();
        let mut sos = sender.create_public_sos("help", 12.9, 77.5).unwrap();
        sos.header.pow_nonce = 0;
        // Re-sign so the failure is attributable to PoW, not the signature.
        sos.signature = sender
            .signing_key
            .sign(&sos.compute_signing_payload().unwrap())
            .to_bytes();

        assert!(matches!(
            receiver.process_incoming(&wire(&sos)),
            Err(MeshError::InvalidProofOfWork)
        ));
    }

    #[test]
    fn duplicate_packets_are_not_relayed_twice() {
        let sender = MeshNode::new();
        let receiver = MeshNode::new();
        let sos = sender.create_public_sos("help", 12.9, 77.5).unwrap();
        let bytes = wire(&sos);

        assert!(receiver.process_incoming(&bytes).unwrap().should_relay);
        assert!(!receiver.process_incoming(&bytes).unwrap().should_relay);
    }

    #[test]
    fn a_relayed_packet_still_verifies_downstream() {
        let sender = MeshNode::new();
        let relay = MeshNode::new();
        let far = MeshNode::new();

        let sos = sender.create_public_sos("help", 12.9, 77.5).unwrap();
        let hop1 = relay.process_incoming(&wire(&sos)).unwrap();
        assert!(hop1.should_relay);

        // The far node must accept what the relay forwarded, TTL decrement and all.
        let hop2 = far.process_incoming(&wire(&hop1.packet)).unwrap();
        assert_eq!(hop2.packet.header.sender_id, sender.node_id);
        assert_eq!(hop2.packet.header.ttl, DEFAULT_TTL - 2);
    }

    #[test]
    fn pins_propagate_and_are_queryable() {
        let sender = MeshNode::new();
        let receiver = MeshNode::new();
        let pin = sender
            .create_signed_pin_packet(
                [3u8; 16],
                ResourcePinType::WaterPoint,
                12.9,
                77.5,
                "tanker at gate 4",
                3600,
            )
            .unwrap();

        receiver.process_incoming(&wire(&pin)).unwrap();
        let pins = receiver.pin_store.get_active_pins(MeshNode::now());
        assert_eq!(pins.len(), 1);
        assert_eq!(pins[0].label, "tanker at gate 4");
    }

    #[test]
    fn a_relay_cannot_rewrite_a_pin_it_forwards() {
        let creator = MeshNode::new();
        let malicious_relay = MeshNode::new();
        let victim = MeshNode::new();

        let pin_packet = creator
            .create_signed_pin_packet(
                [4u8; 16],
                ResourcePinType::Shelter,
                12.9,
                77.5,
                "school shelter",
                3600,
            )
            .unwrap();

        // Relay rewrites the pin body and re-signs the outer packet with its
        // own key. The inner creator signature must still fail.
        let mut tampered: ResourcePin = bincode::deserialize(&pin_packet.payload).unwrap();
        tampered.label = "shelter closed, go north".to_string();
        let payload = bincode::serialize(&tampered).unwrap();

        let forwarded = malicious_relay
            .sign_packet(
                PacketType::ResourcePin,
                [4u8; 16],
                [0u8; 16],
                pin_packet.header.location.clone(),
                0,
                payload,
            )
            .unwrap();

        victim.process_incoming(&wire(&forwarded)).unwrap();
        assert!(
            victim.pin_store.get_active_pins(MeshNode::now()).is_empty(),
            "a pin with an invalid creator signature must never be stored"
        );
    }

    #[test]
    fn chat_requires_a_session_and_never_falls_back_to_plaintext() {
        let alice = MeshNode::new();
        let bob = MeshNode::new();
        assert!(matches!(
            alice.create_chat_packet(bob.node_id, "are you safe?"),
            Err(MeshError::NoSession)
        ));
    }

    /// Drives the three-message XX handshake between two nodes, carried end to
    /// end as ordinary signed mesh packets.
    fn handshake(alice: &MeshNode, bob: &MeshNode) {
        let m1 = alice.begin_handshake(bob.node_id).unwrap();
        let m2 = bob
            .process_incoming(&wire(&m1))
            .unwrap()
            .handshake_reply
            .expect("responder must answer message 1");
        let m3 = alice
            .process_incoming(&wire(&m2))
            .unwrap()
            .handshake_reply
            .expect("initiator must answer message 2");
        assert!(
            bob.process_incoming(&wire(&m3))
                .unwrap()
                .handshake_reply
                .is_none(),
            "XX completes in exactly three messages"
        );
    }

    #[test]
    fn end_to_end_encrypted_chat_round_trip() {
        let alice = MeshNode::new();
        let bob = MeshNode::new();

        handshake(&alice, &bob);

        assert!(alice.has_session_with(&bob.node_id));
        assert!(bob.has_session_with(&alice.node_id));

        let chat = alice.create_chat_packet(bob.node_id, "are you safe?").unwrap();

        // The message must not be readable on the wire.
        let on_wire = wire(&chat);
        assert!(!on_wire
            .windows(b"are you safe?".len())
            .any(|w| w == b"are you safe?"));

        let received = bob.process_incoming(&on_wire).unwrap();
        assert_eq!(
            received.plaintext.as_deref(),
            Some(&b"are you safe?"[..])
        );
        assert!(received.ack.is_some());
    }

    #[test]
    fn a_relay_in_the_middle_cannot_read_chat() {
        let alice = MeshNode::new();
        let bob = MeshNode::new();
        let nosy_relay = MeshNode::new();

        handshake(&alice, &bob);

        let chat = alice.create_chat_packet(bob.node_id, "meet at gate 4").unwrap();
        let relayed = nosy_relay.process_incoming(&wire(&chat)).unwrap();

        assert!(relayed.plaintext.is_none(), "relay must not recover plaintext");
        assert!(relayed.should_relay, "but it must still forward the packet");
    }

    #[test]
    fn rate_limiter_is_charged_only_to_proven_senders() {
        let attacker = MeshNode::new();
        let victim_identity = MeshNode::new();
        let receiver = MeshNode::new();

        // Attacker forges the victim's sender_id onto its own signed packet.
        let mut forged = attacker.create_public_sos("fake", 1.0, 1.0).unwrap();
        forged.header.sender_id = victim_identity.node_id;
        forged.signature = attacker
            .signing_key
            .sign(&forged.compute_signing_payload().unwrap())
            .to_bytes();

        // It must be rejected outright, not counted against the victim.
        assert!(matches!(
            receiver.process_incoming(&wire(&forged)),
            Err(MeshError::Packet(PacketError::SenderIdMismatch))
        ));

        // The victim's own traffic must be unaffected.
        let genuine = victim_identity.create_public_sos("real", 1.0, 1.0).unwrap();
        assert!(receiver.process_incoming(&wire(&genuine)).unwrap().should_relay);
    }

    #[test]
    fn oversized_payloads_are_refused_at_creation() {
        let node = MeshNode::new();
        let huge = "x".repeat(MAX_PAYLOAD_BYTES + 1);
        assert!(matches!(
            node.create_public_sos(&huge, 1.0, 1.0),
            Err(MeshError::PayloadTooLarge)
        ));
    }

    #[test]
    fn garbage_from_the_radio_never_panics() {
        let node = MeshNode::new();
        for len in [0usize, 1, 7, 64, 512, 9000] {
            let junk = vec![0xA5u8; len];
            assert!(node.process_incoming(&junk).is_err());
        }
    }

    #[test]
    fn invalid_coordinates_are_refused() {
        let node = MeshNode::new();
        assert!(node.create_public_sos("help", f32::NAN, 0.0).is_err());
        assert!(node.create_public_sos("help", 200.0, 0.0).is_err());
    }
}
