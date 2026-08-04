use serde::{Deserialize, Serialize};
use thiserror::Error;

#[derive(Error, Debug)]
pub enum PacketError {
    #[error("Serialization error: {0}")]
    Serialization(String),
    #[error("Invalid signature")]
    InvalidSignature,
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

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PacketHeader {
    pub version: u8,
    pub packet_type: PacketType,
    pub ttl: u8,
    pub flags: u8,
    pub msg_id: [u8; 16],
    pub sender_id: [u8; 16],   // SHA-256 hash of sender public key (first 16 bytes)
    pub recipient_id: [u8; 16], // 0x00...00 for broadcast
    pub timestamp: u64,
    pub location: Option<LocationTag>,
    pub pow_nonce: u32,
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
            Err(D::Error::custom(format!("expected 64 bytes, got {}", bytes.len())))
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

impl Packet {
    pub fn compute_signing_payload(&self) -> Result<Vec<u8>, PacketError> {
        let mut data = Vec::new();
        data.push(self.header.version);
        data.push(self.header.packet_type as u8);
        data.push(self.header.ttl);
        data.push(self.header.flags);
        data.extend_from_slice(&self.header.msg_id);
        data.extend_from_slice(&self.header.sender_id);
        data.extend_from_slice(&self.header.recipient_id);
        data.extend_from_slice(&self.header.timestamp.to_le_bytes());
        if let Some(ref loc) = self.header.location {
            data.extend_from_slice(&loc.latitude.to_le_bytes());
            data.extend_from_slice(&loc.longitude.to_le_bytes());
            data.extend_from_slice(&loc.accuracy_meters.to_le_bytes());
        }
        data.extend_from_slice(&self.header.pow_nonce.to_le_bytes());
        data.extend_from_slice(&self.payload);
        Ok(data)
    }

    pub fn to_bytes(&self) -> Result<Vec<u8>, PacketError> {
        bincode::serialize(self).map_err(|e| PacketError::Serialization(e.to_string()))
    }

    pub fn from_bytes(bytes: &[u8]) -> Result<Self, PacketError> {
        bincode::deserialize(bytes).map_err(|e| PacketError::Serialization(e.to_string()))
    }

    pub fn verify_signature(&self, sender_pubkey: &[u8; 32]) -> Result<(), PacketError> {
        use ed25519_dalek::{Signature, VerifyingKey};
        let vk = VerifyingKey::from_bytes(sender_pubkey)
            .map_err(|_| PacketError::InvalidSignature)?;
        let sig_payload = self.compute_signing_payload()?;
        let sig = Signature::from_bytes(&self.signature);
        vk.verify_strict(&sig_payload, &sig)
            .map_err(|_| PacketError::InvalidSignature)
    }
}
