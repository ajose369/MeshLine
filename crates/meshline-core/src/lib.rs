pub mod crypto;
pub mod gis;
pub mod packet;
pub mod routing;
pub mod security;
pub mod transport;

use ed25519_dalek::{SigningKey, VerifyingKey};
use rand::rngs::OsRng;
use rand::Rng;
use sha2::{Digest, Sha256};
use std::sync::Arc;

pub use crypto::noise::{NoiseHandshakeState, SymmetricCipher};
pub use gis::pins::{GisPinStore, ResourcePin, ResourcePinType};
pub use packet::schema::{LocationTag, Packet, PacketHeader, PacketType};
pub use routing::flood::{BatteryPowerState, RoutingEngine};
pub use security::rate_limit::{MeshRateLimiter, ProofOfWork};
pub use transport::lora::{LoraFrameHeader, MeshtasticBridgeFrame};


pub struct MeshNode {
    pub signing_key: SigningKey,
    pub verifying_key: VerifyingKey,
    pub node_id: [u8; 16],
    pub routing: Arc<RoutingEngine>,
    pub pin_store: Arc<GisPinStore>,
    pub rate_limiter: Arc<MeshRateLimiter>,
}

impl MeshNode {
    pub fn new() -> Self {
        let signing_key = SigningKey::generate(&mut OsRng);
        let verifying_key = signing_key.verifying_key();
        let pubkey_bytes = verifying_key.as_bytes();

        let mut hasher = Sha256::new();
        hasher.update(pubkey_bytes);
        let hash_result = hasher.finalize();
        let mut node_id = [0u8; 16];
        node_id.copy_from_slice(&hash_result[..16]);

        Self {
            signing_key,
            verifying_key,
            node_id,
            routing: Arc::new(RoutingEngine::new(5000)),
            pin_store: Arc::new(GisPinStore::new()),
            rate_limiter: Arc::new(MeshRateLimiter::new(10.0, 2.0)),
        }
    }

    pub fn create_public_sos(
        &self,
        sos_message: &str,
        lat: f32,
        lon: f32,
    ) -> Result<Packet, packet::schema::PacketError> {
        let mut msg_id = [0u8; 16];
        rand::thread_rng().fill(&mut msg_id);

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        let location = Some(LocationTag {
            latitude: lat,
            longitude: lon,
            accuracy_meters: 5,
        });

        let payload_bytes = sos_message.as_bytes().to_vec();
        let pow_nonce = ProofOfWork::solve(&payload_bytes, 4);

        let mut packet = Packet {
            header: PacketHeader {
                version: 1,
                packet_type: PacketType::PublicSos,
                ttl: 8,
                flags: 0,
                msg_id,
                sender_id: self.node_id,
                recipient_id: [0u8; 16],
                timestamp: now,
                location,
                pow_nonce,
            },
            payload: payload_bytes,
            signature: [0u8; 64],
        };

        let signing_payload = packet.compute_signing_payload()?;
        let signature = self.signing_key.sign(&signing_payload);
        packet.signature = signature.to_bytes();

        Ok(packet)
    }

    pub fn process_incoming(&self, raw_bytes: &[u8]) -> Result<(Packet, Option<Packet>), String> {
        let mut packet = Packet::from_bytes(raw_bytes).map_err(|e| e.to_string())?;

        // 1. Enforce rate limiting
        if !self.rate_limiter.allow_packet(&packet.header.sender_id) {
            return Err("Rate limit exceeded for sender".to_string());
        }

        // 2. Validate Proof-of-Work for SOS / Broadcasts
        if packet.header.packet_type == PacketType::PublicSos {
            if !ProofOfWork::verify(&packet.payload, packet.header.pow_nonce, 4) {
                return Err("Invalid Proof-of-Work in SOS packet".to_string());
            }
        }

        // 3. Routing evaluation
        let should_forward = self.routing.should_relay(&mut packet).map_err(|e| e.to_string())?;

        // 4. Generate ACK if recipient matches local node
        let ack_packet = if packet.header.recipient_id == self.node_id {
            Some(self.routing.create_ack(&packet.header, &self.node_id))
        } else {
            None
        };

        if should_forward {
            Ok((packet, ack_packet))
        } else {
            Err("Packet consumed local-only".to_string())
        }
    }
}
