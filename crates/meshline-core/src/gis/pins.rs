use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Mutex;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum PinError {
    #[error("Pin signature invalid")]
    InvalidSignature,
    #[error("Pin expired")]
    Expired,
    #[error("Older version discarded")]
    StaleVersion,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[repr(u8)]
pub enum ResourcePinType {
    WaterPoint = 1,
    Shelter = 2,
    MedicalStation = 3,
    Hazard = 4,
    Roadblock = 5,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ResourcePin {
    pub pin_id: [u8; 16],
    pub pin_type: ResourcePinType,
    pub latitude: f32,
    pub longitude: f32,
    pub label: String,
    pub created_at: u64,
    pub expires_at: u64,
    pub creator_pubkey: [u8; 32],
    pub signature: [u8; 64],
}

pub struct GisPinStore {
    pins: Mutex<HashMap<[u8; 16], ResourcePin>>,
}

impl GisPinStore {
    pub fn new() -> Self {
        Self {
            pins: Mutex::new(HashMap::new()),
        }
    }

    pub fn upsert_pin(&self, pin: ResourcePin, current_time: u64) -> Result<(), PinError> {
        if pin.expires_at <= current_time {
            return Err(PinError::Expired);
        }

        let mut store = self.pins.lock().unwrap();
        if let Some(existing) = store.get(&pin.pin_id) {
            if pin.created_at <= existing.created_at {
                return Err(PinError::StaleVersion);
            }
        }

        store.insert(pin.pin_id, pin);
        Ok(())
    }

    pub fn get_active_pins(&self, current_time: u64) -> Vec<ResourcePin> {
        let mut store = self.pins.lock().unwrap();
        store.retain(|_, pin| pin.expires_at > current_time);
        store.values().cloned().collect()
    }

    pub fn get_nearby_pins(&self, lat: f32, lon: f32, radius_km: f32, current_time: u64) -> Vec<ResourcePin> {
        let active = self.get_active_pins(current_time);
        active
            .into_iter()
            .filter(|p| haversine_distance_km(lat, lon, p.latitude, p.longitude) <= radius_km)
            .collect()
    }
}

fn haversine_distance_km(lat1: f32, lon1: f32, lat2: f32, lon2: f32) -> f32 {
    let r = 6371.0f32; // Earth radius in km
    let d_lat = (lat2 - lat1).to_radians();
    let d_lon = (lon2 - lon1).to_radians();
    let a = (d_lat / 2.0).sin().powi(2)
        + lat1.to_radians().cos() * lat2.to_radians().cos() * (d_lon / 2.0).sin().powi(2);
    let c = 2.0 * a.sqrt().atan2((1.0 - a).sqrt());
    r * c
}
