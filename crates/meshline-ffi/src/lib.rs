use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use meshline_core::{BatteryPowerState, MeshNode, ResourcePinType};
use ed25519_dalek::Signer;
use std::sync::Mutex;

static NODE_INSTANCE: Mutex<Option<MeshNode>> = Mutex::new(None);

fn to_hex(bytes: &[u8]) -> String {
    let mut s = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        s.push_str(&format!("{:02x}", b));
    }
    s
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_initNode(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    let mut instance = NODE_INSTANCE.lock().unwrap();
    if instance.is_none() {
        *instance = Some(MeshNode::new());
        1
    } else {
        1
    }
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_createPublicSos(
    mut env: JNIEnv,
    _class: JClass,
    message: JString,
    lat: f32,
    lon: f32,
) -> jbyteArray {
    let msg_str: String = match env.get_string(&message) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let instance = NODE_INSTANCE.lock().unwrap();
    if let Some(ref node) = *instance {
        match node.create_public_sos(&msg_str, lat, lon) {
            Ok(packet) => match packet.to_bytes() {
                Ok(bytes) => match env.byte_array_from_slice(&bytes) {
                    Ok(arr) => arr.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                },
                Err(_) => std::ptr::null_mut(),
            },
            Err(_) => std::ptr::null_mut(),
        }
    } else {
        std::ptr::null_mut()
    }
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_createSignedPinPacket(
    mut env: JNIEnv,
    _class: JClass,
    pin_id_bytes: jbyteArray,
    pin_type_val: jint,
    lat: f32,
    lon: f32,
    label: JString,
    expires_in_secs: jlong,
) -> jbyteArray {
    if pin_id_bytes.is_null() {
        return std::ptr::null_mut();
    }
    let array_obj = unsafe { JByteArray::from_raw(pin_id_bytes) };
    let pin_id_vec = match env.convert_byte_array(&array_obj) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut pin_id = [0u8; 16];
    if pin_id_vec.len() == 16 {
        pin_id.copy_from_slice(&pin_id_vec);
    } else {
        return std::ptr::null_mut();
    }

    let pin_type = match pin_type_val {
        1 => ResourcePinType::WaterPoint,
        2 => ResourcePinType::Shelter,
        3 => ResourcePinType::MedicalStation,
        4 => ResourcePinType::Hazard,
        5 => ResourcePinType::Roadblock,
        _ => ResourcePinType::WaterPoint,
    };

    let label_str: String = match env.get_string(&label) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let instance = NODE_INSTANCE.lock().unwrap();
    if let Some(ref node) = *instance {
        match node.create_signed_pin_packet(
            pin_id,
            pin_type,
            lat,
            lon,
            &label_str,
            expires_in_secs as u64,
        ) {
            Ok(packet) => match packet.to_bytes() {
                Ok(bytes) => match env.byte_array_from_slice(&bytes) {
                    Ok(arr) => arr.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                },
                Err(_) => std::ptr::null_mut(),
            },
            Err(_) => std::ptr::null_mut(),
        }
    } else {
        std::ptr::null_mut()
    }
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_getActivePinsJson(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let instance = NODE_INSTANCE.lock().unwrap();
    if let Some(ref node) = *instance {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();
        let pins = node.pin_store.get_active_pins(now);

        #[derive(serde::Serialize)]
        struct JsonResourcePin {
            pin_id: String,
            pin_type: String,
            latitude: f32,
            longitude: f32,
            label: String,
            created_at: u64,
            expires_at: u64,
            creator_pubkey: String,
            signature_hex: String,
            verified_count: i32,
        }

        let json_pins: Vec<JsonResourcePin> = pins
            .into_iter()
            .map(|p| {
                let type_str = match p.pin_type {
                    ResourcePinType::WaterPoint => "WaterPoint",
                    ResourcePinType::Shelter => "Shelter",
                    ResourcePinType::MedicalStation => "MedicalStation",
                    ResourcePinType::Hazard => "Hazard",
                    ResourcePinType::Roadblock => "Roadblock",
                };
                JsonResourcePin {
                    pin_id: to_hex(&p.pin_id),
                    pin_type: type_str.to_string(),
                    latitude: p.latitude,
                    longitude: p.longitude,
                    label: p.label,
                    created_at: p.created_at * 1000,
                    expires_at: p.expires_at * 1000,
                    creator_pubkey: to_hex(&p.creator_pubkey),
                    signature_hex: to_hex(&p.signature),
                    verified_count: 1,
                }
            })
            .collect();

        match serde_json::to_string(&json_pins) {
            Ok(json_str) => match env.new_string(json_str) {
                Ok(jstr) => jstr.into_raw(),
                Err(_) => std::ptr::null_mut(),
            },
            Err(_) => std::ptr::null_mut(),
        }
    } else {
        std::ptr::null_mut()
    }
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_processIncomingPacket(
    env: JNIEnv,
    _class: JClass,
    raw_packet: jbyteArray,
) -> jbyteArray {
    if raw_packet.is_null() {
        return std::ptr::null_mut();
    }
    let array_obj = unsafe { JByteArray::from_raw(raw_packet) };
    let bytes = match env.convert_byte_array(&array_obj) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    let instance = NODE_INSTANCE.lock().unwrap();
    if let Some(ref node) = *instance {
        match node.process_incoming(&bytes) {
            Ok((forward_packet, _ack)) => match forward_packet.to_bytes() {
                Ok(out_bytes) => match env.byte_array_from_slice(&out_bytes) {
                    Ok(arr) => arr.into_raw(),
                    Err(_) => std::ptr::null_mut(),
                },
                Err(_) => std::ptr::null_mut(),
            },
            Err(_) => std::ptr::null_mut(),
        }
    } else {
        std::ptr::null_mut()
    }
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_updateBatteryState(
    _env: JNIEnv,
    _class: JClass,
    battery_level: jint,
    is_charging: jboolean,
) {
    let instance = NODE_INSTANCE.lock().unwrap();
    if let Some(ref node) = *instance {
        let state = if is_charging != 0 {
            BatteryPowerState::Charging
        } else if battery_level > 20 {
            BatteryPowerState::Normal
        } else {
            BatteryPowerState::LowPowerSaver
        };
        node.routing.set_battery_state(state);
    }
}

fn from_hex(s: &str) -> Option<Vec<u8>> {
    if s.len() % 2 != 0 {
        return None;
    }
    let mut res = Vec::with_capacity(s.len() / 2);
    for i in (0..s.len()).step_by(2) {
        let byte = u8::from_str_radix(&s[i..i + 2], 16).ok()?;
        res.push(byte);
    }
    Some(res)
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_createChatPacket(
    mut env: JNIEnv,
    _class: JClass,
    message: JString,
    recipient_id_hex: JString,
) -> jbyteArray {
    let msg_str: String = match env.get_string(&message) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };
    let recipient_hex: String = match env.get_string(&recipient_id_hex) {
        Ok(s) => s.into(),
        Err(_) => return std::ptr::null_mut(),
    };

    let mut recipient_id = [0u8; 16];
    if recipient_hex != "All" {
        if let Some(bytes) = from_hex(&recipient_hex) {
            if bytes.len() == 16 {
                recipient_id.copy_from_slice(&bytes);
            }
        }
    }

    let instance = NODE_INSTANCE.lock().unwrap();
    if let Some(ref node) = *instance {
        let mut msg_id = [0u8; 16];
        rand::Rng::fill(&mut rand::thread_rng(), &mut msg_id);

        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        let payload_bytes = msg_str.as_bytes().to_vec();
        let mut packet = meshline_core::Packet {
            header: meshline_core::PacketHeader {
                version: 1,
                packet_type: meshline_core::PacketType::Chat,
                ttl: 8,
                flags: 0,
                msg_id,
                sender_id: node.node_id,
                recipient_id,
                timestamp: now,
                location: None,
                pow_nonce: 0,
            },
            payload: payload_bytes,
            signature: [0u8; 64],
        };

        if let Ok(signing_payload) = packet.compute_signing_payload() {
            let signature = node.signing_key.sign(&signing_payload);
            packet.signature = signature.to_bytes();
            if let Ok(bytes) = packet.to_bytes() {
                if let Ok(arr) = env.byte_array_from_slice(&bytes) {
                    return arr.into_raw();
                }
            }
        }
    }
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_parsePacketJson(
    mut env: JNIEnv,
    _class: JClass,
    raw_packet: jbyteArray,
) -> jstring {
    if raw_packet.is_null() {
        return std::ptr::null_mut();
    }
    let array_obj = unsafe { JByteArray::from_raw(raw_packet) };
    let bytes = match env.convert_byte_array(&array_obj) {
        Ok(b) => b,
        Err(_) => return std::ptr::null_mut(),
    };

    if let Ok(packet) = meshline_core::Packet::from_bytes(&bytes) {
        #[derive(serde::Serialize)]
        struct ParsedPacket {
            msg_id: String,
            sender_id: String,
            recipient_id: String,
            packet_type: String,
            ttl: u8,
            timestamp: u64,
            payload_text: String,
        }

        let type_str = match packet.header.packet_type {
            meshline_core::PacketType::PublicSos => "PublicSos",
            meshline_core::PacketType::PrivateSos => "PrivateSos",
            meshline_core::PacketType::Chat => "Chat",
            meshline_core::PacketType::Ack => "Ack",
            meshline_core::PacketType::ResourcePin => "ResourcePin",
            meshline_core::PacketType::NoiseHandshake => "NoiseHandshake",
        };

        let payload_text = String::from_utf8(packet.payload.clone()).unwrap_or_default();
        let parsed = ParsedPacket {
            msg_id: to_hex(&packet.header.msg_id),
            sender_id: to_hex(&packet.header.sender_id),
            recipient_id: to_hex(&packet.header.recipient_id),
            packet_type: type_str.to_string(),
            ttl: packet.header.ttl,
            timestamp: packet.header.timestamp,
            payload_text,
        };

        if let Ok(json_str) = serde_json::to_string(&parsed) {
            if let Ok(jstr) = env.new_string(json_str) {
                return jstr.into_raw();
            }
        }
    }
    std::ptr::null_mut()
}
