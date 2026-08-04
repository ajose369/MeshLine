use lru::LruCache;
use rand::Rng;
use std::num::NonZeroUsize;
use std::sync::Mutex;
use thiserror::Error;

use crate::packet::schema::{Packet, PacketHeader, PacketType};

#[derive(Error, Debug)]
pub enum RoutingError {
    #[error("Packet already seen (duplicate drop)")]
    DuplicatePacket,
    #[error("Packet TTL zero")]
    TtlZero,
    #[error("Dampened by battery duty cycle")]
    Dampened,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BatteryPowerState {
    Charging,
    Normal,
    LowPowerSaver,
}

pub struct RoutingEngine {
    seen_cache: Mutex<LruCache<[u8; 16], u64>>,
    battery_state: Mutex<BatteryPowerState>,
}

impl RoutingEngine {
    pub fn new(capacity: usize) -> Self {
        Self {
            seen_cache: Mutex::new(LruCache::new(NonZeroUsize::new(capacity).unwrap())),
            battery_state: Mutex::new(BatteryPowerState::Normal),
        }
    }

    pub fn set_battery_state(&self, state: BatteryPowerState) {
        let mut b = self.battery_state.lock().unwrap();
        *b = state;
    }

    pub fn get_battery_state(&self) -> BatteryPowerState {
        *self.battery_state.lock().unwrap()
    }

    pub fn should_relay(&self, packet: &mut Packet) -> Result<bool, RoutingError> {
        // 1. Check deduplication cache
        let mut cache = self.seen_cache.lock().unwrap();
        if cache.contains(&packet.header.msg_id) {
            return Err(RoutingError::DuplicatePacket);
        }
        cache.put(packet.header.msg_id, packet.header.timestamp);

        // 2. Check TTL
        if packet.header.ttl <= 1 {
            return Err(RoutingError::TtlZero);
        }

        // 3. Decrement TTL for forwarding
        packet.header.ttl -= 1;

        // 4. Probabilistic Flood Dampening based on Battery State & SOS priority
        let current_battery = self.get_battery_state();
        let relay_probability = match (packet.header.packet_type, current_battery) {
            (PacketType::PublicSos, _) | (PacketType::PrivateSos, _) => 1.0, // Always relay SOS
            (_, BatteryPowerState::Charging) => 1.0,
            (_, BatteryPowerState::Normal) => 0.85,
            (_, BatteryPowerState::LowPowerSaver) => 0.30,
        };

        let random_roll: f32 = rand::thread_rng().gen();
        if random_roll > relay_probability {
            return Err(RoutingError::Dampened);
        }

        Ok(true)
    }

    pub fn mark_seen(&self, msg_id: &[u8; 16], timestamp: u64) {
        let mut cache = self.seen_cache.lock().unwrap();
        cache.put(*msg_id, timestamp);
    }

    pub fn is_seen(&self, msg_id: &[u8; 16]) -> bool {
        let cache = self.seen_cache.lock().unwrap();
        cache.contains(msg_id)
    }

    pub fn create_ack(&self, original_header: &PacketHeader, node_id: &[u8; 16]) -> Packet {
        let mut ack_msg_id = [0u8; 16];
        rand::thread_rng().fill(&mut ack_msg_id);

        Packet {
            header: PacketHeader {
                version: original_header.version,
                packet_type: PacketType::Ack,
                ttl: 8,
                flags: 0,
                msg_id: ack_msg_id,
                sender_id: *node_id,
                recipient_id: original_header.sender_id,
                timestamp: std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap_or_default()
                    .as_secs(),
                location: None,
                pow_nonce: 0,
            },
            payload: original_header.msg_id.to_vec(),
            signature: [0u8; 64],
        }
    }
}
