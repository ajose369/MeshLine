use lru::LruCache;
use rand::Rng;
use std::num::NonZeroUsize;
use std::sync::Mutex;
use thiserror::Error;

use crate::packet::schema::{Packet, PacketType};

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

}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::packet::schema::{LocationTag, PacketHeader, PROTOCOL_VERSION};

    fn packet(msg_id: [u8; 16], packet_type: PacketType, ttl: u8) -> Packet {
        Packet {
            header: PacketHeader {
                version: PROTOCOL_VERSION,
                packet_type,
                ttl,
                flags: 0,
                msg_id,
                sender_id: [1u8; 16],
                sender_pubkey: [2u8; 32],
                recipient_id: [0u8; 16],
                timestamp: 1_700_000_000,
                location: None::<LocationTag>,
                pow_nonce: 0,
            },
            payload: Vec::new(),
            signature: [0u8; 64],
        }
    }

    #[test]
    fn first_sighting_relays_and_decrements_ttl() {
        let engine = RoutingEngine::new(64);
        engine.set_battery_state(BatteryPowerState::Charging);
        let mut p = packet([1u8; 16], PacketType::Chat, 5);
        assert!(engine.should_relay(&mut p).unwrap());
        assert_eq!(p.header.ttl, 4);
    }

    #[test]
    fn second_sighting_is_a_duplicate() {
        let engine = RoutingEngine::new(64);
        engine.set_battery_state(BatteryPowerState::Charging);
        let mut p = packet([9u8; 16], PacketType::Chat, 5);
        engine.should_relay(&mut p.clone()).unwrap();
        assert!(matches!(
            engine.should_relay(&mut p),
            Err(RoutingError::DuplicatePacket)
        ));
    }

    #[test]
    fn exhausted_ttl_stops_the_flood() {
        let engine = RoutingEngine::new(64);
        let mut p = packet([2u8; 16], PacketType::Chat, 1);
        assert!(matches!(
            engine.should_relay(&mut p),
            Err(RoutingError::TtlZero)
        ));
    }

    #[test]
    fn sos_is_never_dampened_even_on_a_dying_battery() {
        let engine = RoutingEngine::new(4096);
        engine.set_battery_state(BatteryPowerState::LowPowerSaver);
        // Across many distinct SOS packets, not one may be dropped.
        for i in 0..200u8 {
            let mut p = packet([i; 16], PacketType::PublicSos, 5);
            assert!(
                engine.should_relay(&mut p).unwrap(),
                "SOS must bypass duty-cycle dampening"
            );
        }
    }

    #[test]
    fn low_power_dampens_ordinary_chat() {
        let engine = RoutingEngine::new(4096);
        engine.set_battery_state(BatteryPowerState::LowPowerSaver);
        let mut dampened = 0;
        for i in 0..200u8 {
            let mut p = packet([i; 16], PacketType::Chat, 5);
            if matches!(engine.should_relay(&mut p), Err(RoutingError::Dampened)) {
                dampened += 1;
            }
        }
        // Nominal rate is 30% relay, so ~70% dropped. Bounds are loose enough
        // not to flake while still catching a dampener that does nothing.
        assert!(
            (100..=200).contains(&dampened),
            "expected substantial dampening, saw {dampened}/200"
        );
    }

    #[test]
    fn dedup_cache_evicts_oldest_entries() {
        let engine = RoutingEngine::new(2);
        engine.set_battery_state(BatteryPowerState::Charging);
        engine.mark_seen(&[1u8; 16], 0);
        engine.mark_seen(&[2u8; 16], 0);
        engine.mark_seen(&[3u8; 16], 0);
        assert!(!engine.is_seen(&[1u8; 16]), "oldest entry should be evicted");
        assert!(engine.is_seen(&[3u8; 16]));
    }
}
