use bincode::Options;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use thiserror::Error;

/// Wire format version. v1 was never released; v2 adds `sender_pubkey` to the
/// header, without which a receiver has no key to check the signature against.
pub const PROTOCOL_VERSION: u8 = 2;

/// Hard ceiling on a decoded packet. Every parser here is fed by a radio, so a
/// length prefix from the wire is never trusted to size an allocation.
pub const MAX_PACKET_BYTES: u64 = 8 * 1024;

/// Ceiling on the application payload inside a packet.
pub const MAX_PAYLOAD_BYTES: usize = 4 * 1024;

/// How far ahead of local time a packet may claim to be before it is rejected.
/// Mesh clocks drift and phones in a disaster zone lose NTP, so this is loose.
pub const MAX_CLOCK_SKEW_SECS: u64 = 24 * 60 * 60;

/// How old a packet may be and still be accepted for relay.
pub const MAX_PACKET_AGE_SECS: u64 = 7 * 24 * 60 * 60;

#[derive(Error, Debug)]
pub enum PacketError {
    #[error("Serialization error: {0}")]
    Serialization(String),
    #[error("Invalid signature")]
    InvalidSignature,
    #[error("Sender id does not match sender public key")]
    SenderIdMismatch,
    #[error("Unsupported protocol version {0}")]
    UnsupportedVersion(u8),
    #[error("Payload exceeds {MAX_PAYLOAD_BYTES} bytes")]
    PayloadTooLarge,
    #[error("Packet timestamp outside acceptable window")]
    TimestampOutOfRange,
    #[error("Location tag is not a finite coordinate on Earth")]
    InvalidLocation,
    #[error("Packet TTL expired")]
    TtlExpired,
    #[error("Proof of work invalid")]
    InvalidProofOfWork,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum PacketType {
    PublicSos = 1,
    PrivateSos = 2,
    Chat = 3,
    Ack = 4,
    ResourcePin = 5,
    NoiseHandshake = 6,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LocationTag {
    pub latitude: f32,
    pub longitude: f32,
    pub accuracy_meters: u32,
}

impl LocationTag {
    /// Rejects NaN/infinite and out-of-range coordinates. A forged pin at
    /// lat=NaN would otherwise sort and render unpredictably on the map.
    pub fn is_valid(&self) -> bool {
        self.latitude.is_finite()
            && self.longitude.is_finite()
            && (-90.0..=90.0).contains(&self.latitude)
            && (-180.0..=180.0).contains(&self.longitude)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PacketHeader {
    pub version: u8,
    pub packet_type: PacketType,
    /// Decremented by each relay. Deliberately excluded from the signature.
    pub ttl: u8,
    /// Relay-mutable flags. Deliberately excluded from the signature.
    pub flags: u8,
    pub msg_id: [u8; 16],
    /// SHA-256 of `sender_pubkey`, truncated to 16 bytes. Bound at verify time.
    pub sender_id: [u8; 16],
    /// Ed25519 public key of the originator. Required to check the signature.
    pub sender_pubkey: [u8; 32],
    /// All-zero for broadcast.
    pub recipient_id: [u8; 16],
    pub timestamp: u64,
    pub location: Option<LocationTag>,
    pub pow_nonce: u32,
}

/// Derives the 16-byte mesh node id from an Ed25519 public key.
pub fn node_id_from_pubkey(pubkey: &[u8; 32]) -> [u8; 16] {
    let digest = Sha256::digest(pubkey);
    let mut node_id = [0u8; 16];
    node_id.copy_from_slice(&digest[..16]);
    node_id
}

pub mod serde_bytes_64 {
    use serde::{de::Error, Deserialize, Deserializer, Serializer};

    pub fn serialize<S>(bytes: &[u8; 64], serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_bytes(bytes)
    }

    pub fn deserialize<'de, D>(deserializer: D) -> Result<[u8; 64], D::Error>
    where
        D: Deserializer<'de>,
    {
        let bytes = <Vec<u8>>::deserialize(deserializer)?;
        if bytes.len() == 64 {
            let mut arr = [0u8; 64];
            arr.copy_from_slice(&bytes);
            Ok(arr)
        } else {
            Err(D::Error::custom(format!(
                "expected 64 bytes, got {}",
                bytes.len()
            )))
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Packet {
    pub header: PacketHeader,
    pub payload: Vec<u8>,
    #[serde(with = "serde_bytes_64")]
    pub signature: [u8; 64],
}

/// Shared bincode configuration. Serialize and deserialize must agree, and the
/// size limit is what stops a hostile length prefix from allocating gigabytes.
fn codec() -> impl Options {
    bincode::DefaultOptions::new()
        .with_fixint_encoding()
        .with_limit(MAX_PACKET_BYTES)
}

/// Appends a length-prefixed field so that two different field splits can never
/// produce the same signing payload (e.g. a label containing digits shifting
/// into the timestamp).
fn push_field(buf: &mut Vec<u8>, bytes: &[u8]) {
    buf.extend_from_slice(&(bytes.len() as u32).to_le_bytes());
    buf.extend_from_slice(bytes);
}

impl Packet {
    /// The bytes covered by the originator's signature.
    ///
    /// `ttl` and `flags` are excluded on purpose: relays mutate them in flight,
    /// so including them would invalidate the signature after the first hop.
    pub fn compute_signing_payload(&self) -> Result<Vec<u8>, PacketError> {
        let mut data = Vec::with_capacity(160 + self.payload.len());
        data.extend_from_slice(b"meshline/packet/v2");
        data.push(self.header.version);
        data.push(self.header.packet_type as u8);
        data.extend_from_slice(&self.header.msg_id);
        data.extend_from_slice(&self.header.sender_id);
        data.extend_from_slice(&self.header.sender_pubkey);
        data.extend_from_slice(&self.header.recipient_id);
        data.extend_from_slice(&self.header.timestamp.to_le_bytes());
        match self.header.location {
            Some(ref loc) => {
                data.push(1);
                data.extend_from_slice(&loc.latitude.to_le_bytes());
                data.extend_from_slice(&loc.longitude.to_le_bytes());
                data.extend_from_slice(&loc.accuracy_meters.to_le_bytes());
            }
            None => data.push(0),
        }
        data.extend_from_slice(&self.header.pow_nonce.to_le_bytes());
        push_field(&mut data, &self.payload);
        Ok(data)
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, PacketError> {
        codec()
            .serialize(self)
            .map_err(|e| PacketError::Serialization(e.to_string()))
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, PacketError> {
        if bytes.len() as u64 > MAX_PACKET_BYTES {
            return Err(PacketError::Serialization("packet too large".into()));
        }
        codec()
            .deserialize(bytes)
            .map_err(|e| PacketError::Serialization(e.to_string()))
    }

    /// Full authenticity check: version, size, sender-id binding, and signature.
    ///
    /// The sender-id binding is what makes the embedded public key safe to use.
    /// Without it an attacker could attach their own key, sign correctly, and
    /// still claim any `sender_id` they liked.
    pub fn verify(&self) -> Result<(), PacketError> {
        use ed25519_dalek::{Signature, VerifyingKey};

        if self.header.version != PROTOCOL_VERSION {
            return Err(PacketError::UnsupportedVersion(self.header.version));
        }
        if self.payload.len() > MAX_PAYLOAD_BYTES {
            return Err(PacketError::PayloadTooLarge);
        }
        if let Some(ref loc) = self.header.location {
            if !loc.is_valid() {
                return Err(PacketError::InvalidLocation);
            }
        }
        if self.header.sender_id != node_id_from_pubkey(&self.header.sender_pubkey) {
            return Err(PacketError::SenderIdMismatch);
        }

        let vk = VerifyingKey::from_bytes(&self.header.sender_pubkey)
            .map_err(|_| PacketError::InvalidSignature)?;
        let sig_payload = self.compute_signing_payload()?;
        let sig = Signature::from_bytes(&self.signature);
        vk.verify_strict(&sig_payload, &sig)
            .map_err(|_| PacketError::InvalidSignature)
    }

    /// Rejects packets from the far future or the distant past. Bounding this
    /// keeps the dedup cache from being farmed with pre-dated replays.
    pub fn check_freshness(&self, now: u64) -> Result<(), PacketError> {
        if self.header.timestamp > now.saturating_add(MAX_CLOCK_SKEW_SECS) {
            return Err(PacketError::TimestampOutOfRange);
        }
        if self.header.timestamp.saturating_add(MAX_PACKET_AGE_SECS) < now {
            return Err(PacketError::TimestampOutOfRange);
        }
        Ok(())
    }

    pub fn is_broadcast(&self) -> bool {
        self.header.recipient_id == [0u8; 16]
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use ed25519_dalek::{Signer, SigningKey};
    use rand::rngs::OsRng;

    fn signed_packet() -> (SigningKey, Packet) {
        let sk = SigningKey::generate(&mut OsRng);
        let pubkey = sk.verifying_key().to_bytes();
        let mut packet = Packet {
            header: PacketHeader {
                version: PROTOCOL_VERSION,
                packet_type: PacketType::PublicSos,
                ttl: 8,
                flags: 0,
                msg_id: [7u8; 16],
                sender_id: node_id_from_pubkey(&pubkey),
                sender_pubkey: pubkey,
                recipient_id: [0u8; 16],
                timestamp: 1_700_000_000,
                location: Some(LocationTag {
                    latitude: 12.97,
                    longitude: 77.59,
                    accuracy_meters: 5,
                }),
                pow_nonce: 42,
            },
            payload: b"trapped on the second floor".to_vec(),
            signature: [0u8; 64],
        };
        let sig = sk.sign(&packet.compute_signing_payload().unwrap());
        packet.signature = sig.to_bytes();
        (sk, packet)
    }

    #[test]
    fn round_trips_through_the_wire_format() {
        let (_, packet) = signed_packet();
        let bytes = packet.to_bytes().unwrap();
        let decoded = Packet::from_bytes(&bytes).unwrap();
        assert_eq!(decoded.header.msg_id, packet.header.msg_id);
        assert_eq!(decoded.payload, packet.payload);
        decoded.verify().unwrap();
    }

    #[test]
    fn signature_survives_relay_ttl_decrement() {
        let (_, mut packet) = signed_packet();
        packet.header.ttl -= 1;
        packet.header.flags = 0xFF;
        packet
            .verify()
            .expect("relay-mutable fields must not be signed");
    }

    #[test]
    fn rejects_tampered_payload() {
        let (_, mut packet) = signed_packet();
        packet.payload = b"all clear, no rescue needed".to_vec();
        assert!(matches!(
            packet.verify(),
            Err(PacketError::InvalidSignature)
        ));
    }

    #[test]
    fn rejects_tampered_location() {
        let (_, mut packet) = signed_packet();
        packet.header.location = Some(LocationTag {
            latitude: 0.0,
            longitude: 0.0,
            accuracy_meters: 5,
        });
        assert!(matches!(
            packet.verify(),
            Err(PacketError::InvalidSignature)
        ));
    }

    #[test]
    fn rejects_spoofed_sender_id() {
        // Attacker signs correctly with their own key but claims another id.
        let (sk, mut packet) = signed_packet();
        packet.header.sender_id = [0xAB; 16];
        packet.signature = sk
            .sign(&packet.compute_signing_payload().unwrap())
            .to_bytes();
        assert!(matches!(
            packet.verify(),
            Err(PacketError::SenderIdMismatch)
        ));
    }

    #[test]
    fn rejects_substituted_sender_key() {
        let (_, mut packet) = signed_packet();
        let attacker = SigningKey::generate(&mut OsRng);
        packet.header.sender_pubkey = attacker.verifying_key().to_bytes();
        assert!(packet.verify().is_err());
    }

    #[test]
    fn rejects_unsupported_version() {
        let (sk, mut packet) = signed_packet();
        packet.header.version = 99;
        packet.signature = sk
            .sign(&packet.compute_signing_payload().unwrap())
            .to_bytes();
        assert!(matches!(
            packet.verify(),
            Err(PacketError::UnsupportedVersion(99))
        ));
    }

    #[test]
    fn rejects_oversized_input() {
        let huge = vec![0u8; (MAX_PACKET_BYTES + 1) as usize];
        assert!(Packet::from_bytes(&huge).is_err());
    }

    #[test]
    fn freshness_window_bounds_both_directions() {
        let (_, packet) = signed_packet();
        let ts = packet.header.timestamp;
        packet.check_freshness(ts).unwrap();
        assert!(packet.check_freshness(ts + MAX_PACKET_AGE_SECS + 1).is_err());
        assert!(packet
            .check_freshness(ts - MAX_CLOCK_SKEW_SECS - 1)
            .is_err());
    }

    #[test]
    fn length_prefixing_prevents_field_ambiguity() {
        let (_, mut a) = signed_packet();
        let mut b = a.clone();
        a.payload = b"ab".to_vec();
        b.payload = b"a".to_vec();
        b.header.msg_id = a.header.msg_id;
        assert_ne!(
            a.compute_signing_payload().unwrap(),
            b.compute_signing_payload().unwrap()
        );
    }

    #[test]
    fn location_validation_rejects_nan_and_out_of_range() {
        assert!(!LocationTag {
            latitude: f32::NAN,
            longitude: 0.0,
            accuracy_meters: 1
        }
        .is_valid());
        assert!(!LocationTag {
            latitude: 91.0,
            longitude: 0.0,
            accuracy_meters: 1
        }
        .is_valid());
        assert!(LocationTag {
            latitude: -33.8,
            longitude: 151.2,
            accuracy_meters: 1
        }
        .is_valid());
    }
}
