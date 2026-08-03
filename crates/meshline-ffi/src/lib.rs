use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring};
use jni::JNIEnv;
use meshline_core::{BatteryPowerState, MeshNode, ResourcePin, ResourcePinType};
use std::sync::Mutex;

static NODE_INSTANCE: Mutex<Option<MeshNode>> = Mutex::new(None);

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
    env: JNIEnv,
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
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_processIncomingPacket(
    env: JNIEnv,
    _class: JClass,
    raw_packet: jbyteArray,
) -> jbyteArray {
    let bytes = match env.convert_byte_array(raw_packet) {
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
        // Safety check to set battery state
        let mut engine_state = node.routing.battery_state;
        engine_state = state;
    }
}
