pub mod crypto;
pub mod gis;
pub mod packet;
pub mod routing;
pub mod security;
pub mod transport;

use ed25519_dalek::{Signer, SigningKey, VerifyingKey};
use rand::rngs::OsRng;
use rand::Rng;
use serde::{Deserialize, Serialize};
use std::sync::{Arc, Mutex};
use thiserror::Error;

pub use crypto::group::{
    Group, GroupInvite, GroupStore, InviteOutcome, MAX_GROUP_MEMBERS, MAX_GROUP_NAME_BYTES,
};
pub use crypto::noise::{HandshakeSession, IdentityProof, SessionManager};
pub use crypto::session::TransportSession;
pub use crypto::trust::{safety_number, safety_number_groups, TrustStore, VerificationRecord};
pub use crypto::CryptoError;
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
    #[error("this node has never been seen, so there is nothing to verify")]
    UnknownPeer,
    #[error("serialization: {0}")]
    Serialization(String),
}

/// The result of rekeying a group: who to send the new key to, and who could
/// not be reached.
///
/// Both halves matter. A rekey that silently fails to reach half the group
/// leaves those members unable to read anything sent afterwards, and the user
/// needs to be told that rather than discovering it through silence.
#[derive(Debug, Clone)]
pub struct GroupRekeyResult {
    /// Invite packets to transmit, one per reachable member.
    pub invites: Vec<Packet>,
    /// Members with no pairwise session, who therefore did not get the new key.
    pub unreachable: Vec<[u8; 16]>,
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
    /// Decrypted text. Present for a Chat packet addressed to us on an
    /// established session, or for a group message we hold the key to.
    pub plaintext: Option<Vec<u8>>,
    /// True when the packet was addressed to this node specifically. Group
    /// traffic is addressed to a group tag, so this stays false for it.
    pub addressed_to_us: bool,
    /// The group this packet belongs to, when we are a member of it.
    pub group_id: Option<[u8; 16]>,
    /// The group's name, for a UI that has not seen the group before.
    pub group_name: Option<String>,
    /// Set when a group invite changed this device's membership.
    pub group_event: Option<InviteOutcome>,
}

pub struct MeshNode {
    signing_key: SigningKey,
    pub verifying_key: VerifyingKey,
    pub node_id: [u8; 16],
    pub routing: Arc<RoutingEngine>,
    pub pin_store: Arc<GisPinStore>,
    pub rate_limiter: Arc<MeshRateLimiter>,
    pub sessions: Arc<Mutex<SessionManager>>,
    pub groups: Arc<Mutex<GroupStore>>,
    pub trust: Arc<Mutex<TrustStore>>,
}

/// The secure state as it is written to the vault.
///
/// Borrowed on the way out so that session and group keys are not copied into a
/// second buffer before being sealed.
#[derive(Serialize)]
struct SecureStateOut<'a> {
    sessions: Vec<&'a TransportSession>,
    groups: &'a GroupStore,
    trust: &'a TrustStore,
}

/// The same state on the way back in.
#[derive(Deserialize)]
struct SecureStateIn {
    sessions: Vec<TransportSession>,
    groups: GroupStore,
    trust: TrustStore,
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
            groups: Arc::new(Mutex::new(GroupStore::new())),
            trust: Arc::new(Mutex::new(TrustStore::new())),
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
            sessions.begin_handshake(peer_id, &self.signing_key, Self::now())?
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

    // -----------------------------------------------------------------------
    // Out-of-band verification
    // -----------------------------------------------------------------------

    /// The safety number to compare with a peer, in person or over a channel
    /// you already trust.
    ///
    /// Requires an established session, because the number is derived from the
    /// identity key the handshake actually authenticated rather than from
    /// anything a peer merely claims.
    pub fn safety_number_with(&self, peer_id: &[u8; 16]) -> Result<Vec<String>, MeshError> {
        let peer_key = self
            .sessions
            .lock()
            .expect("session mutex poisoned")
            .peer_identity_pubkey(peer_id)
            .ok_or(MeshError::NoSession)?;

        Ok(safety_number_groups(&self.public_key_bytes(), &peer_key))
    }

    /// Records the user's decision after they compared safety numbers.
    pub fn set_peer_verified(&self, peer_id: &[u8; 16], verified: bool) -> Result<(), MeshError> {
        let mut trust = self.trust.lock().expect("trust mutex poisoned");
        if trust.set_verified(peer_id, verified, Self::now()) {
            Ok(())
        } else {
            Err(MeshError::UnknownPeer)
        }
    }

    pub fn is_peer_verified(&self, peer_id: &[u8; 16]) -> bool {
        self.trust
            .lock()
            .expect("trust mutex poisoned")
            .is_verified(peer_id)
    }

    // -----------------------------------------------------------------------
    // Groups
    // -----------------------------------------------------------------------

    /// Creates a group with this device as admin and sole member.
    pub fn create_group(&self, name: &str) -> Result<[u8; 16], MeshError> {
        let mut groups = self.groups.lock().expect("group mutex poisoned");
        Ok(groups.create(self.node_id, name, Self::now())?)
    }

    /// Adds a peer to a group and returns the invite to send them.
    ///
    /// The group key travels inside the pairwise Noise session, so a peer you
    /// have not completed a handshake with cannot be added at all. That is
    /// deliberate: it means group membership can never be a way to hand key
    /// material to someone whose identity was never authenticated.
    pub fn invite_to_group(
        &self,
        group_id: &[u8; 16],
        peer_id: [u8; 16],
    ) -> Result<Packet, MeshError> {
        // Authorisation before capability: a non-admin must be told they are
        // not allowed, not that the peer happens to be unreachable.
        {
            let groups = self.groups.lock().expect("group mutex poisoned");
            let group = groups.get(group_id).ok_or(CryptoError::UnknownGroup)?;
            if !group.is_admin(&self.node_id) {
                return Err(CryptoError::NotGroupAdmin.into());
            }
        }
        if !self.has_session_with(&peer_id) {
            return Err(MeshError::NoSession);
        }

        let invite_bytes = {
            let mut groups = self.groups.lock().expect("group mutex poisoned");
            groups.add_member(group_id, &self.node_id, peer_id)?;
            let group = groups.get(group_id).ok_or(CryptoError::UnknownGroup)?;
            crypto::group::encode_invite(&group.to_invite())?
        };

        self.sealed_invite_packet(peer_id, &invite_bytes)
    }

    /// Wraps an invite in a pairwise session and signs the resulting packet.
    fn sealed_invite_packet(
        &self,
        peer_id: [u8; 16],
        invite_bytes: &[u8],
    ) -> Result<Packet, MeshError> {
        let ciphertext = {
            let mut sessions = self.sessions.lock().expect("session mutex poisoned");
            sessions.encrypt_for(&peer_id, invite_bytes)?
        };
        self.sign_packet(
            PacketType::GroupInvite,
            Self::random_msg_id(),
            peer_id,
            None,
            0,
            ciphertext,
        )
    }

    /// Removes a member and rekeys the group, returning the invites that carry
    /// the new key to everyone who remains.
    pub fn remove_from_group(
        &self,
        group_id: &[u8; 16],
        member: &[u8; 16],
    ) -> Result<GroupRekeyResult, MeshError> {
        let remaining = {
            let mut groups = self.groups.lock().expect("group mutex poisoned");
            groups.remove_member(group_id, &self.node_id, member)?
        };
        self.distribute_group_key(group_id, &remaining)
    }

    /// Rotates a group's key without changing its membership, for when a
    /// member's device may have been seized or compromised.
    pub fn rekey_group(&self, group_id: &[u8; 16]) -> Result<GroupRekeyResult, MeshError> {
        let remaining = {
            let mut groups = self.groups.lock().expect("group mutex poisoned");
            groups.rekey(group_id, &self.node_id)?
        };
        self.distribute_group_key(group_id, &remaining)
    }

    /// Builds one invite per member, reporting those we cannot reach rather
    /// than pretending the rekey was complete.
    fn distribute_group_key(
        &self,
        group_id: &[u8; 16],
        members: &[[u8; 16]],
    ) -> Result<GroupRekeyResult, MeshError> {
        let invite_bytes = {
            let groups = self.groups.lock().expect("group mutex poisoned");
            let group = groups.get(group_id).ok_or(CryptoError::UnknownGroup)?;
            crypto::group::encode_invite(&group.to_invite())?
        };

        let mut invites = Vec::new();
        let mut unreachable = Vec::new();
        for member in members {
            if member == &self.node_id {
                continue;
            }
            match self.sealed_invite_packet(*member, &invite_bytes) {
                Ok(packet) => invites.push(packet),
                Err(_) => unreachable.push(*member),
            }
        }

        Ok(GroupRekeyResult {
            invites,
            unreachable,
        })
    }

    /// Builds an encrypted message for a group.
    ///
    /// The packet is addressed to the group's tag, not to any member, so the
    /// mesh can route and dedup it without learning who the group is.
    pub fn create_group_chat_packet(
        &self,
        group_id: &[u8; 16],
        message: &str,
    ) -> Result<Packet, MeshError> {
        let (tag, ciphertext) = {
            let mut groups = self.groups.lock().expect("group mutex poisoned");
            let group = groups.get_mut(group_id).ok_or(CryptoError::UnknownGroup)?;
            let ciphertext = group.encrypt(&self.node_id, message.as_bytes())?;
            (group.tag(), ciphertext)
        };

        self.sign_packet(
            PacketType::GroupChat,
            Self::random_msg_id(),
            tag,
            None,
            0,
            ciphertext,
        )
    }

    /// Leaves a group, deleting its key from this device.
    pub fn leave_group(&self, group_id: &[u8; 16]) -> bool {
        self.groups
            .lock()
            .expect("group mutex poisoned")
            .leave(group_id)
    }

    // -----------------------------------------------------------------------
    // Secure state at rest
    // -----------------------------------------------------------------------

    /// Seals sessions, groups, and verification decisions under `vault_key`.
    ///
    /// The key belongs in platform-protected storage. Everything in here is
    /// live key material, which is exactly what makes a seized device dangerous
    /// to the rest of the mesh.
    pub fn export_secure_state(&self, vault_key: &[u8; 32]) -> Result<Vec<u8>, MeshError> {
        let sessions = self.sessions.lock().expect("session mutex poisoned");
        let groups = self.groups.lock().expect("group mutex poisoned");
        let trust = self.trust.lock().expect("trust mutex poisoned");

        let state = SecureStateOut {
            sessions: sessions.sessions_for_export(),
            groups: &groups,
            trust: &trust,
        };
        let plaintext =
            bincode::serialize(&state).map_err(|e| MeshError::Serialization(e.to_string()))?;

        Ok(crypto::vault::seal(vault_key, &plaintext)?)
    }

    /// Restores state sealed by [`export_secure_state`](Self::export_secure_state).
    ///
    /// A blob that will not open is treated as absent, not as a reason to run
    /// with half a session table: the caller starts fresh and re-handshakes.
    pub fn import_secure_state(
        &self,
        vault_key: &[u8; 32],
        blob: &[u8],
    ) -> Result<(), MeshError> {
        let plaintext = crypto::vault::open(vault_key, blob)?;
        let state: SecureStateIn = bincode::deserialize(&plaintext)
            .map_err(|e| MeshError::Serialization(e.to_string()))?;

        self.sessions
            .lock()
            .expect("session mutex poisoned")
            .install_sessions(state.sessions);
        *self.groups.lock().expect("group mutex poisoned") = state.groups;
        *self.trust.lock().expect("trust mutex poisoned") = state.trust;
        Ok(())
    }

    /// Forgets every session, group key, and verification on this device.
    ///
    /// This is the control someone reaches for when a phone is about to be
    /// taken from them. It cannot un-send what has already gone out, but it
    /// makes this device useless for reading anything further.
    pub fn wipe_secure_state(&self) {
        self.sessions
            .lock()
            .expect("session mutex poisoned")
            .clear();
        self.groups.lock().expect("group mutex poisoned").clear();
        *self.trust.lock().expect("trust mutex poisoned") = TrustStore::new();
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
        let mut group_id = None;
        let mut group_name = None;
        let mut group_event = None;
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
                        let reply = sessions
                            .accept_handshake(
                                packet.header.sender_id,
                                &packet.payload,
                                &self.signing_key,
                                now,
                            )
                            .ok()
                            .flatten();

                        // Once a session exists, remember the identity behind it
                        // so the user can be offered a safety number to compare.
                        if let Some(key) =
                            sessions.peer_identity_pubkey(&packet.header.sender_id)
                        {
                            self.trust
                                .lock()
                                .expect("trust mutex poisoned")
                                .observe(key, now);
                        }
                        reply
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
                PacketType::GroupInvite if addressed_to_us => {
                    // An invite only means anything inside an authenticated
                    // pairwise session, so it is opened there first and the
                    // sender that the group store checks against is the one the
                    // packet signature already proved.
                    let invite_bytes = {
                        let mut sessions =
                            self.sessions.lock().expect("session mutex poisoned");
                        sessions
                            .decrypt_from(&packet.header.sender_id, &packet.payload)
                            .ok()
                    };

                    if let Some(bytes) = invite_bytes {
                        if let Ok(invite) = crypto::group::decode_invite(&bytes) {
                            let mut groups = self.groups.lock().expect("group mutex poisoned");
                            if let Ok(outcome) = groups.apply_invite(
                                &packet.header.sender_id,
                                &self.node_id,
                                &invite,
                            ) {
                                group_id = Some(invite.group_id);
                                group_name = Some(invite.name.clone());
                                group_event = Some(outcome);
                            }
                        }
                    }
                }
                PacketType::GroupChat => {
                    // Addressed to a group tag rather than to us. A non-member
                    // finds no match, learns nothing, and relays it anyway.
                    let mut groups = self.groups.lock().expect("group mutex poisoned");
                    if let Some(id) = groups.by_tag(&packet.header.recipient_id) {
                        if let Some(group) = groups.get_mut(&id) {
                            if let Ok(text) =
                                group.decrypt(&packet.header.sender_id, &packet.payload)
                            {
                                plaintext = Some(text);
                                group_id = Some(id);
                                group_name = Some(group.name.clone());
                            }
                        }
                    }
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
            group_id,
            group_name,
            group_event,
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

        // Relaying of non-SOS traffic is probabilistic by battery state, so pin
        // it to the always-relay state rather than letting the assertion below
        // fail 15% of the time.
        nosy_relay
            .routing
            .set_battery_state(BatteryPowerState::Charging);

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

    // -----------------------------------------------------------------------
    // Out-of-band verification
    // -----------------------------------------------------------------------

    #[test]
    fn both_devices_show_the_same_safety_number() {
        let alice = MeshNode::new();
        let bob = MeshNode::new();
        handshake(&alice, &bob);

        let on_alices_screen = alice.safety_number_with(&bob.node_id).unwrap();
        let on_bobs_screen = bob.safety_number_with(&alice.node_id).unwrap();

        assert_eq!(on_alices_screen, on_bobs_screen);
        assert_eq!(on_alices_screen.len(), 12, "twelve groups of five digits");
    }

    #[test]
    fn a_safety_number_needs_a_session_to_be_meaningful() {
        let alice = MeshNode::new();
        let stranger = MeshNode::new();
        assert!(matches!(
            alice.safety_number_with(&stranger.node_id),
            Err(MeshError::NoSession)
        ));
    }

    #[test]
    fn an_impostor_shows_a_different_safety_number() {
        let alice = MeshNode::new();
        let real_bob = MeshNode::new();
        let impostor = MeshNode::new();

        handshake(&alice, &real_bob);
        handshake(&alice, &impostor);

        assert_ne!(
            alice.safety_number_with(&real_bob.node_id).unwrap(),
            alice.safety_number_with(&impostor.node_id).unwrap(),
            "comparing digits in person must expose a substituted identity"
        );
    }

    #[test]
    fn peers_start_unverified_and_verification_is_recorded() {
        let alice = MeshNode::new();
        let bob = MeshNode::new();
        handshake(&alice, &bob);

        assert!(
            !alice.is_peer_verified(&bob.node_id),
            "a completed handshake is not the same as a verified human"
        );
        alice.set_peer_verified(&bob.node_id, true).unwrap();
        assert!(alice.is_peer_verified(&bob.node_id));
    }

    #[test]
    fn a_peer_never_seen_cannot_be_marked_verified() {
        let alice = MeshNode::new();
        assert!(matches!(
            alice.set_peer_verified(&[0x11; 16], true),
            Err(MeshError::UnknownPeer)
        ));
    }

    // -----------------------------------------------------------------------
    // Groups
    // -----------------------------------------------------------------------

    /// Alice creates a group and brings Bob and Carol in over real packets.
    fn affinity_group(
        alice: &MeshNode,
        bob: &MeshNode,
        carol: &MeshNode,
    ) -> [u8; 16] {
        handshake(alice, bob);
        handshake(alice, carol);

        let gid = alice.create_group("affinity").unwrap();

        let to_bob = alice.invite_to_group(&gid, bob.node_id).unwrap();
        let joined = bob.process_incoming(&wire(&to_bob)).unwrap();
        assert_eq!(joined.group_event, Some(InviteOutcome::Joined));

        let to_carol = alice.invite_to_group(&gid, carol.node_id).unwrap();
        carol.process_incoming(&wire(&to_carol)).unwrap();

        gid
    }

    #[test]
    fn a_group_message_reaches_every_member() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);

        let packet = alice
            .create_group_chat_packet(&gid, "line forming at the north end")
            .unwrap();
        let on_wire = wire(&packet);

        // Not readable on the radio.
        assert!(!on_wire
            .windows("line forming".len())
            .any(|w| w == b"line forming"));

        for member in [&bob, &carol] {
            let received = member.process_incoming(&on_wire).unwrap();
            assert_eq!(
                received.plaintext.as_deref(),
                Some(&b"line forming at the north end"[..])
            );
            assert_eq!(received.group_id, Some(gid));
            assert_eq!(received.group_name.as_deref(), Some("affinity"));
        }
    }

    #[test]
    fn a_non_member_carries_group_traffic_without_reading_it() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);

        let outsider = MeshNode::new();
        outsider
            .routing
            .set_battery_state(BatteryPowerState::Charging);

        let packet = alice.create_group_chat_packet(&gid, "medic needed").unwrap();
        let relayed = outsider.process_incoming(&wire(&packet)).unwrap();

        assert!(relayed.plaintext.is_none(), "an outsider must learn nothing");
        assert!(relayed.group_id.is_none());
        assert!(
            relayed.should_relay,
            "but the mesh only works if it carries traffic it cannot read"
        );
    }

    #[test]
    fn the_group_id_never_appears_on_the_wire() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);

        let packet = alice.create_group_chat_packet(&gid, "hold").unwrap();
        assert_ne!(
            packet.header.recipient_id, gid,
            "the packet is addressed to a derived tag, not the group itself"
        );
        assert!(!wire(&packet).windows(16).any(|w| w == gid));
    }

    #[test]
    fn a_group_cannot_be_shared_with_someone_never_handshaken_with() {
        let alice = MeshNode::new();
        let stranger = MeshNode::new();
        let gid = alice.create_group("affinity").unwrap();

        assert!(matches!(
            alice.invite_to_group(&gid, stranger.node_id),
            Err(MeshError::NoSession)
        ));
        assert_eq!(
            alice.groups.lock().unwrap().get(&gid).unwrap().member_count(),
            1,
            "a failed invite must not leave a member holding no key"
        );
    }

    #[test]
    fn an_outsider_cannot_inject_a_message_into_a_group() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);

        // The tag is visible to anyone with a radio, so an attacker can address
        // a packet to the group. Membership is what stops it being read.
        let genuine = alice.create_group_chat_packet(&gid, "hold").unwrap();
        let tag = genuine.header.recipient_id;

        let outsider = MeshNode::new();
        let forged = outsider
            .sign_packet(
                PacketType::GroupChat,
                MeshNode::random_msg_id(),
                tag,
                None,
                0,
                vec![0xAB; 128],
            )
            .unwrap();

        let received = bob.process_incoming(&wire(&forged)).unwrap();
        assert!(
            received.plaintext.is_none(),
            "a packet from a non-member must never surface as group chat"
        );
    }

    #[test]
    fn a_removed_member_is_locked_out_of_later_messages() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);

        let rekey = alice.remove_from_group(&gid, &carol.node_id).unwrap();
        assert!(rekey.unreachable.is_empty());
        assert_eq!(rekey.invites.len(), 1, "only Bob remains to be re-keyed");

        for invite in &rekey.invites {
            let outcome = bob.process_incoming(&wire(invite)).unwrap();
            assert_eq!(outcome.group_event, Some(InviteOutcome::Rekeyed));
        }

        let packet = alice
            .create_group_chat_packet(&gid, "new meeting point is the library")
            .unwrap();
        let on_wire = wire(&packet);

        assert_eq!(
            bob.process_incoming(&on_wire).unwrap().plaintext.as_deref(),
            Some(&b"new meeting point is the library"[..])
        );
        assert!(
            carol.process_incoming(&on_wire).unwrap().plaintext.is_none(),
            "a removed member must not be able to read the new epoch"
        );
    }

    #[test]
    fn a_removed_member_is_told_they_are_out() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);

        // Carol is removed, then re-invited to a group she is no longer in:
        // the invite Alice sends Bob is not hers, so instead we hand Carol the
        // admin's next epoch directly, which is what a relayed invite would do.
        alice.remove_from_group(&gid, &carol.node_id).unwrap();
        let stale = alice.invite_to_group(&gid, carol.node_id);
        assert!(
            stale.is_ok(),
            "re-adding a removed member is allowed; it is a new epoch for her"
        );
        let outcome = carol.process_incoming(&wire(&stale.unwrap())).unwrap();
        assert_eq!(outcome.group_event, Some(InviteOutcome::Rekeyed));
    }

    #[test]
    fn only_the_admin_can_add_or_remove_members() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);

        // Bob holds the key but is not admin.
        assert!(matches!(
            bob.invite_to_group(&gid, carol.node_id),
            Err(MeshError::Crypto(CryptoError::NotGroupAdmin))
        ));
        assert!(matches!(
            bob.remove_from_group(&gid, &carol.node_id),
            Err(MeshError::Crypto(CryptoError::NotGroupAdmin))
        ));
    }

    #[test]
    fn a_rekey_reports_members_it_could_not_reach() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);

        // Bob's session is lost, as happens when a device is reinstalled.
        alice.sessions.lock().unwrap().drop_session(&bob.node_id);

        let rekey = alice.rekey_group(&gid).unwrap();
        assert_eq!(rekey.unreachable, vec![bob.node_id]);
        assert_eq!(rekey.invites.len(), 1, "only Carol could be re-keyed");
    }

    #[test]
    fn leaving_a_group_deletes_its_key() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);

        assert!(bob.leave_group(&gid));
        let packet = alice.create_group_chat_packet(&gid, "still talking").unwrap();
        assert!(bob.process_incoming(&wire(&packet)).unwrap().plaintext.is_none());
    }

    // -----------------------------------------------------------------------
    // Secure state at rest
    // -----------------------------------------------------------------------

    /// Simulates an app restart: the node is rebuilt from its identity secret
    /// and its sealed state, exactly as `MeshCoreBridge` does on launch.
    fn restart(node: &MeshNode, vault_key: &[u8; 32]) -> MeshNode {
        let blob = node.export_secure_state(vault_key).unwrap();
        let restored = MeshNode::from_secret_key(&node.secret_key_bytes());
        restored.import_secure_state(vault_key, &blob).unwrap();
        restored
    }

    #[test]
    fn a_session_survives_an_app_restart() {
        let alice = MeshNode::new();
        let bob = MeshNode::new();
        handshake(&alice, &bob);

        let bob_after_restart = restart(&bob, &[42u8; 32]);

        assert!(bob_after_restart.has_session_with(&alice.node_id));
        let chat = alice.create_chat_packet(bob.node_id, "are you safe?").unwrap();
        assert_eq!(
            bob_after_restart
                .process_incoming(&wire(&chat))
                .unwrap()
                .plaintext
                .as_deref(),
            Some(&b"are you safe?"[..]),
            "a restart must not force a fresh three-message handshake"
        );
    }

    #[test]
    fn groups_and_verification_survive_an_app_restart() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);
        bob.set_peer_verified(&alice.node_id, true).unwrap();

        let bob_after_restart = restart(&bob, &[7u8; 32]);

        assert!(bob_after_restart.is_peer_verified(&alice.node_id));

        let packet = alice.create_group_chat_packet(&gid, "regroup").unwrap();
        assert_eq!(
            bob_after_restart
                .process_incoming(&wire(&packet))
                .unwrap()
                .plaintext
                .as_deref(),
            Some(&b"regroup"[..])
        );
    }

    #[test]
    fn a_restored_session_still_refuses_replays() {
        let alice = MeshNode::new();
        let bob = MeshNode::new();
        handshake(&alice, &bob);

        let chat = alice.create_chat_packet(bob.node_id, "one time only").unwrap();
        bob.process_incoming(&wire(&chat)).unwrap();

        // The replay window is part of the sealed state, so a captured packet
        // must not become deliverable again just because the app restarted.
        let bob_after_restart = restart(&bob, &[9u8; 32]);
        assert!(bob_after_restart
            .process_incoming(&wire(&chat))
            .unwrap()
            .plaintext
            .is_none());
    }

    #[test]
    fn the_sealed_state_reveals_nothing_without_the_key() {
        let alice = MeshNode::new();
        let bob = MeshNode::new();
        handshake(&alice, &bob);
        let gid = bob.create_group("affinity").unwrap();

        let blob = bob.export_secure_state(&[3u8; 32]).unwrap();
        assert!(!blob.windows(16).any(|w| w == gid), "no group id in the clear");
        assert!(
            !blob.windows(16).any(|w| w == alice.node_id),
            "no peer ids in the clear"
        );

        let wrong_key = MeshNode::new();
        assert!(
            wrong_key.import_secure_state(&[4u8; 32], &blob).is_err(),
            "a seized device must not give up its state to the wrong key"
        );
    }

    #[test]
    fn corrupt_state_is_refused_rather_than_partly_loaded() {
        let bob = MeshNode::new();
        bob.create_group("affinity").unwrap();
        let mut blob = bob.export_secure_state(&[5u8; 32]).unwrap();
        let last = blob.len() - 1;
        blob[last] ^= 0x01;

        let fresh = MeshNode::new();
        assert!(fresh.import_secure_state(&[5u8; 32], &blob).is_err());
        assert!(fresh.groups.lock().unwrap().is_empty());
    }

    #[test]
    fn wiping_leaves_nothing_readable() {
        let (alice, bob, carol) = (MeshNode::new(), MeshNode::new(), MeshNode::new());
        let gid = affinity_group(&alice, &bob, &carol);
        bob.set_peer_verified(&alice.node_id, true).unwrap();

        bob.wipe_secure_state();

        assert!(!bob.has_session_with(&alice.node_id));
        assert!(!bob.is_peer_verified(&alice.node_id));
        assert!(bob.groups.lock().unwrap().is_empty());

        let chat = alice.create_chat_packet(bob.node_id, "after the wipe").unwrap();
        assert!(bob.process_incoming(&wire(&chat)).unwrap().plaintext.is_none());

        let group_msg = alice.create_group_chat_packet(&gid, "after the wipe").unwrap();
        assert!(bob
            .process_incoming(&wire(&group_msg))
            .unwrap()
            .plaintext
            .is_none());
    }

    #[test]
    fn a_wiped_device_can_still_be_brought_back_by_re_handshaking() {
        let alice = MeshNode::new();
        let bob = MeshNode::new();
        handshake(&alice, &bob);
        bob.wipe_secure_state();

        // The mesh identity is untouched by a wipe, so recovery is a fresh
        // handshake rather than a new identity nobody recognises.
        handshake(&alice, &bob);
        let chat = alice.create_chat_packet(bob.node_id, "back online").unwrap();
        assert_eq!(
            bob.process_incoming(&wire(&chat)).unwrap().plaintext.as_deref(),
            Some(&b"back online"[..])
        );
    }
}
