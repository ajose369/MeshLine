use crate::packet::schema::{Packet, PacketError};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LoraFrameHeader {
    pub frequency_mhz: u16,  // e.g., 915, 868, 433
    pub spreading_factor: u8, // e.g., SF7 to SF12
    pub bandwidth_khz: u16,   // e.g., 125, 250, 500
    pub coding_rate: u8,     // e.g., 4/5, 4/8
    pub rssi: i16,
    pub snr: i8,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeshtasticBridgeFrame {
    pub header: LoraFrameHeader,
    pub payload: Vec<u8>,
}

impl MeshtasticBridgeFrame {
    pub fn wrap_packet(packet: &Packet, freq: u16, sf: u8) -> Result<Self, PacketError> {
        let raw_bytes = packet.to_bytes()?;
        Ok(Self {
            header: LoraFrameHeader {
                frequency_mhz: freq,
                spreading_factor: sf,
                bandwidth_khz: 125,
                coding_rate: 5,
                rssi: 0,
                snr: 0,
            },
            payload: raw_bytes,
        })
    }

    pub fn unwrap_packet(&self) -> Result<Packet, PacketError> {
        Packet::from_bytes(&self.payload)
    }
}
