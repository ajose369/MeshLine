//! JNI bindings for `meshline-core`.
//!
//! Two rules govern everything in this file:
//!
//! 1. **Never unwind into the JVM.** A Rust panic crossing the FFI boundary is
//!    undefined behaviour, so every exported function wraps its body in
//!    `catch_unwind` and degrades to a null/false return instead.
//! 2. **Never invent a fallback.** If the node is uninitialised or a session is
//!    missing, these functions return null. The Kotlin side is expected to treat
//!    that as a hard failure, not to substitute plaintext.

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde::Serialize;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

use meshline_core::{
    node_id_from_pubkey, BatteryPowerState, GroupRekeyResult, InviteOutcome, MeshNode, PacketType,
    ResourcePinType,
};

static NODE_INSTANCE: Mutex<Option<MeshNode>> = Mutex::new(None);

/// Runs `body`, converting any panic into `fallback` so nothing unwinds into
/// the JVM.
fn guard<T>(fallback: T, body: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(body)).unwrap_or(fallback)
}

/// Borrows the initialised node, or returns `fallback` if there is none.
macro_rules! with_node {
    ($fallback:expr, |$node:ident| $body:expr) => {{
        let guard_lock = match NODE_INSTANCE.lock() {
            Ok(g) => g,
            Err(_) => return $fallback,
        };
        match guard_lock.as_ref() {
            Some($node) => $body,
            None => return $fallback,
        }
    }};
}

fn to_hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn bool_out(value: bool) -> jboolean {
    if value {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

fn from_hex(s: &str) -> Option<Vec<u8>> {
    if !s.len().is_multiple_of(2) {
        return None;
    }
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).ok())
        .collect()
}

fn node_id_from_hex(s: &str) -> Option<[u8; 16]> {
    let bytes = from_hex(s)?;
    if bytes.len() != 16 {
        return None;
    }
    let mut id = [0u8; 16];
    id.copy_from_slice(&bytes);
    Some(id)
}

fn read_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|v| v.into())
}

fn byte_array_out(env: &mut JNIEnv, bytes: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(bytes) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn string_out(env: &mut JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn json_out<T: Serialize>(env: &mut JNIEnv, value: &T) -> jstring {
    match serde_json::to_string(value) {
        Ok(s) => string_out(env, &s),
        Err(_) => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

/// Initialises the mesh node.
///
/// Pass the previously stored 32-byte identity secret to keep a stable mesh
/// identity, or an empty/null array on first run. Returns the secret actually in
/// use so the caller can persist it to platform-protected storage; returns null
/// on failure.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeInit(
    mut env: JNIEnv,
    _class: JClass,
    secret: JByteArray,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let existing: Option<[u8; 32]> = if secret.is_null() {
            None
        } else {
            match env.convert_byte_array(&secret) {
                Ok(bytes) if bytes.len() == 32 => {
                    let mut key = [0u8; 32];
                    key.copy_from_slice(&bytes);
                    Some(key)
                }
                _ => None,
            }
        };

        let node = match existing {
            Some(key) => MeshNode::from_secret_key(&key),
            None => MeshNode::new(),
        };
        let secret_bytes = node.secret_key_bytes();

        match NODE_INSTANCE.lock() {
            Ok(mut slot) => *slot = Some(node),
            Err(_) => return std::ptr::null_mut(),
        }

        byte_array_out(&mut env, &secret_bytes)
    })
}

/// True once [`nativeInit`] has succeeded.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeIsReady(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    guard(JNI_FALSE, || {
        match NODE_INSTANCE.lock() {
            Ok(slot) => {
                if slot.is_some() {
                    JNI_TRUE
                } else {
                    JNI_FALSE
                }
            }
            Err(_) => JNI_FALSE,
        }
    })
}

/// This node's 32-byte hex mesh id.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeNodeIdHex(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let hex = with_node!(std::ptr::null_mut(), |node| to_hex(&node.node_id));
        string_out(&mut env, &hex)
    })
}

/// This node's Ed25519 public key, for out-of-band contact exchange (QR code).
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativePublicKey(
    mut env: JNIEnv,
    _class: JClass,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let key = with_node!(std::ptr::null_mut(), |node| node.public_key_bytes());
        byte_array_out(&mut env, &key)
    })
}

// ---------------------------------------------------------------------------
// Outbound
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeCreateSos(
    mut env: JNIEnv,
    _class: JClass,
    message: JString,
    lat: f32,
    lon: f32,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let msg = match read_string(&mut env, &message) {
            Some(m) => m,
            None => return std::ptr::null_mut(),
        };
        let packet = with_node!(std::ptr::null_mut(), |node| node
            .create_public_sos(&msg, lat, lon)
            .and_then(|p| p.to_bytes().map_err(Into::into)));

        match packet {
            Ok(bytes) => byte_array_out(&mut env, &bytes),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeCreatePin(
    mut env: JNIEnv,
    _class: JClass,
    pin_id_bytes: JByteArray,
    pin_type_val: jint,
    lat: f32,
    lon: f32,
    label: JString,
    expires_in_secs: jlong,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let label_str = match read_string(&mut env, &label) {
            Some(l) => l,
            None => return std::ptr::null_mut(),
        };
        let raw_id = match env.convert_byte_array(&pin_id_bytes) {
            Ok(b) if b.len() == 16 => b,
            _ => return std::ptr::null_mut(),
        };
        let mut pin_id = [0u8; 16];
        pin_id.copy_from_slice(&raw_id);

        let pin_type = match pin_type_val {
            1 => ResourcePinType::WaterPoint,
            2 => ResourcePinType::Shelter,
            3 => ResourcePinType::MedicalStation,
            4 => ResourcePinType::Hazard,
            5 => ResourcePinType::Roadblock,
            _ => return std::ptr::null_mut(),
        };
        if expires_in_secs <= 0 {
            return std::ptr::null_mut();
        }

        let packet = with_node!(std::ptr::null_mut(), |node| node
            .create_signed_pin_packet(
                pin_id,
                pin_type,
                lat,
                lon,
                &label_str,
                expires_in_secs as u64,
            )
            .and_then(|p| p.to_bytes().map_err(Into::into)));

        match packet {
            Ok(bytes) => byte_array_out(&mut env, &bytes),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

/// Builds an encrypted chat packet. Returns null when no Noise session exists
/// with the recipient — the caller must run a handshake first. There is no
/// plaintext fallback by design.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeCreateChat(
    mut env: JNIEnv,
    _class: JClass,
    recipient_id_hex: JString,
    message: JString,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let recipient = match read_string(&mut env, &recipient_id_hex).as_deref().and_then(node_id_from_hex) {
            Some(r) => r,
            None => return std::ptr::null_mut(),
        };
        let msg = match read_string(&mut env, &message) {
            Some(m) => m,
            None => return std::ptr::null_mut(),
        };

        let packet = with_node!(std::ptr::null_mut(), |node| node
            .create_chat_packet(recipient, &msg)
            .and_then(|p| p.to_bytes().map_err(Into::into)));

        match packet {
            Ok(bytes) => byte_array_out(&mut env, &bytes),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

/// Starts a Noise-XX handshake, returning message 1 as a signed mesh packet.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeBeginHandshake(
    mut env: JNIEnv,
    _class: JClass,
    peer_id_hex: JString,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let peer = match read_string(&mut env, &peer_id_hex).as_deref().and_then(node_id_from_hex) {
            Some(p) => p,
            None => return std::ptr::null_mut(),
        };
        let packet = with_node!(std::ptr::null_mut(), |node| node
            .begin_handshake(peer)
            .and_then(|p| p.to_bytes().map_err(Into::into)));

        match packet {
            Ok(bytes) => byte_array_out(&mut env, &bytes),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeHasSession(
    mut env: JNIEnv,
    _class: JClass,
    peer_id_hex: JString,
) -> jboolean {
    guard(JNI_FALSE, || {
        let peer = match read_string(&mut env, &peer_id_hex).as_deref().and_then(node_id_from_hex) {
            Some(p) => p,
            None => return JNI_FALSE,
        };
        let has = with_node!(JNI_FALSE, |node| node.has_session_with(&peer));
        if has {
            JNI_TRUE
        } else {
            JNI_FALSE
        }
    })
}

// ---------------------------------------------------------------------------
// Out-of-band verification
// ---------------------------------------------------------------------------

/// The safety number to compare with a peer, as twelve space-separated groups
/// of five digits. Null when there is no session, because a safety number
/// without an authenticated key would be a number about nobody.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeSafetyNumber(
    mut env: JNIEnv,
    _class: JClass,
    peer_id_hex: JString,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let peer = match read_string(&mut env, &peer_id_hex)
            .as_deref()
            .and_then(node_id_from_hex)
        {
            Some(p) => p,
            None => return std::ptr::null_mut(),
        };
        let groups = with_node!(std::ptr::null_mut(), |node| node.safety_number_with(&peer));

        match groups {
            Ok(g) => string_out(&mut env, &g.join(" ")),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

/// Records the user's decision after comparing safety numbers in person.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeSetPeerVerified(
    mut env: JNIEnv,
    _class: JClass,
    peer_id_hex: JString,
    verified: jboolean,
) -> jboolean {
    guard(JNI_FALSE, || {
        let peer = match read_string(&mut env, &peer_id_hex)
            .as_deref()
            .and_then(node_id_from_hex)
        {
            Some(p) => p,
            None => return JNI_FALSE,
        };
        let result = with_node!(JNI_FALSE, |node| node
            .set_peer_verified(&peer, verified == JNI_TRUE));
        bool_out(result.is_ok())
    })
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeIsPeerVerified(
    mut env: JNIEnv,
    _class: JClass,
    peer_id_hex: JString,
) -> jboolean {
    guard(JNI_FALSE, || {
        let peer = match read_string(&mut env, &peer_id_hex)
            .as_deref()
            .and_then(node_id_from_hex)
        {
            Some(p) => p,
            None => return JNI_FALSE,
        };
        bool_out(with_node!(JNI_FALSE, |node| node.is_peer_verified(&peer)))
    })
}

// ---------------------------------------------------------------------------
// Groups
// ---------------------------------------------------------------------------

#[derive(Serialize)]
struct GroupJson {
    group_id: String,
    name: String,
    epoch: u32,
    admin_id: String,
    /// True when this device may add or remove members.
    is_admin: bool,
    members: Vec<String>,
    created_at: u64,
}

/// The packets a membership change produced, plus the members it could not
/// reach. The caller must surface `unreachable` rather than assuming a rekey
/// landed everywhere.
#[derive(Serialize)]
struct RekeyJson {
    invite_packets_hex: Vec<String>,
    unreachable: Vec<String>,
}

fn rekey_json(result: &GroupRekeyResult) -> RekeyJson {
    RekeyJson {
        invite_packets_hex: result
            .invites
            .iter()
            .filter_map(|p| p.to_bytes().ok())
            .map(|b| to_hex(&b))
            .collect(),
        unreachable: result.unreachable.iter().map(|m| to_hex(m)).collect(),
    }
}

/// Creates a group with this device as admin. Returns its id as hex.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeCreateGroup(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let name = match read_string(&mut env, &name) {
            Some(n) => n,
            None => return std::ptr::null_mut(),
        };
        let created = with_node!(std::ptr::null_mut(), |node| node.create_group(&name));

        match created {
            Ok(id) => string_out(&mut env, &to_hex(&id)),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

/// Adds a peer to a group, returning the invite packet to transmit. Null when
/// this device is not the group's admin or has no session with the peer.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeInviteToGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_id_hex: JString,
    peer_id_hex: JString,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let group = match read_string(&mut env, &group_id_hex)
            .as_deref()
            .and_then(node_id_from_hex)
        {
            Some(g) => g,
            None => return std::ptr::null_mut(),
        };
        let peer = match read_string(&mut env, &peer_id_hex)
            .as_deref()
            .and_then(node_id_from_hex)
        {
            Some(p) => p,
            None => return std::ptr::null_mut(),
        };

        let packet = with_node!(std::ptr::null_mut(), |node| node
            .invite_to_group(&group, peer)
            .and_then(|p| p.to_bytes().map_err(Into::into)));

        match packet {
            Ok(bytes) => byte_array_out(&mut env, &bytes),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

/// Builds an encrypted group message. Null when this device holds no key for
/// the group, which must be treated as "not sent".
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeCreateGroupChat(
    mut env: JNIEnv,
    _class: JClass,
    group_id_hex: JString,
    message: JString,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let group = match read_string(&mut env, &group_id_hex)
            .as_deref()
            .and_then(node_id_from_hex)
        {
            Some(g) => g,
            None => return std::ptr::null_mut(),
        };
        let msg = match read_string(&mut env, &message) {
            Some(m) => m,
            None => return std::ptr::null_mut(),
        };

        let packet = with_node!(std::ptr::null_mut(), |node| node
            .create_group_chat_packet(&group, &msg)
            .and_then(|p| p.to_bytes().map_err(Into::into)));

        match packet {
            Ok(bytes) => byte_array_out(&mut env, &bytes),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

/// Removes a member and rotates the group key. Returns the invites carrying the
/// new key, and the members that could not be reached.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeRemoveFromGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_id_hex: JString,
    peer_id_hex: JString,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let group = match read_string(&mut env, &group_id_hex)
            .as_deref()
            .and_then(node_id_from_hex)
        {
            Some(g) => g,
            None => return std::ptr::null_mut(),
        };
        let peer = match read_string(&mut env, &peer_id_hex)
            .as_deref()
            .and_then(node_id_from_hex)
        {
            Some(p) => p,
            None => return std::ptr::null_mut(),
        };

        let result = with_node!(std::ptr::null_mut(), |node| node
            .remove_from_group(&group, &peer));

        match result {
            Ok(r) => json_out(&mut env, &rekey_json(&r)),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

/// Rotates a group key without changing membership, for a suspected compromise.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeRekeyGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_id_hex: JString,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let group = match read_string(&mut env, &group_id_hex)
            .as_deref()
            .and_then(node_id_from_hex)
        {
            Some(g) => g,
            None => return std::ptr::null_mut(),
        };
        let result = with_node!(std::ptr::null_mut(), |node| node.rekey_group(&group));

        match result {
            Ok(r) => json_out(&mut env, &rekey_json(&r)),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeLeaveGroup(
    mut env: JNIEnv,
    _class: JClass,
    group_id_hex: JString,
) -> jboolean {
    guard(JNI_FALSE, || {
        let group = match read_string(&mut env, &group_id_hex)
            .as_deref()
            .and_then(node_id_from_hex)
        {
            Some(g) => g,
            None => return JNI_FALSE,
        };
        bool_out(with_node!(JNI_FALSE, |node| node.leave_group(&group)))
    })
}

/// Every group this device belongs to.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeGroupsJson(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let json = with_node!(std::ptr::null_mut(), |node| {
            let groups = match node.groups.lock() {
                Ok(g) => g,
                Err(_) => return std::ptr::null_mut(),
            };
            groups
                .all()
                .iter()
                .map(|g| GroupJson {
                    group_id: to_hex(&g.group_id),
                    name: g.name.clone(),
                    epoch: g.epoch,
                    admin_id: to_hex(&g.admin),
                    is_admin: g.is_admin(&node.node_id),
                    members: g.members.iter().map(|m| to_hex(m)).collect(),
                    created_at: g.created_at,
                })
                .collect::<Vec<_>>()
        });

        json_out(&mut env, &json)
    })
}

// ---------------------------------------------------------------------------
// Secure state at rest
// ---------------------------------------------------------------------------

/// Seals sessions, group keys, and verification decisions under a 32-byte key
/// the caller keeps in the Android Keystore.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeExportState(
    mut env: JNIEnv,
    _class: JClass,
    vault_key: JByteArray,
) -> jbyteArray {
    guard(std::ptr::null_mut(), || {
        let key = match read_vault_key(&mut env, &vault_key) {
            Some(k) => k,
            None => return std::ptr::null_mut(),
        };
        let blob = with_node!(std::ptr::null_mut(), |node| node.export_secure_state(&key));

        match blob {
            Ok(bytes) => byte_array_out(&mut env, &bytes),
            Err(_) => std::ptr::null_mut(),
        }
    })
}

/// Restores state sealed by `nativeExportState`. False means the blob could not
/// be opened, in which case the node keeps whatever it already had — a fresh
/// state — rather than a partially restored one.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeImportState(
    mut env: JNIEnv,
    _class: JClass,
    vault_key: JByteArray,
    blob: JByteArray,
) -> jboolean {
    guard(JNI_FALSE, || {
        let key = match read_vault_key(&mut env, &vault_key) {
            Some(k) => k,
            None => return JNI_FALSE,
        };
        let bytes = match env.convert_byte_array(&blob) {
            Ok(b) => b,
            Err(_) => return JNI_FALSE,
        };
        bool_out(with_node!(JNI_FALSE, |node| node
            .import_secure_state(&key, &bytes)
            .is_ok()))
    })
}

/// Forgets every session, group key, and verification on this device.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeWipeSecureState(
    _env: JNIEnv,
    _class: JClass,
) {
    guard((), || {
        if let Ok(slot) = NODE_INSTANCE.lock() {
            if let Some(node) = slot.as_ref() {
                node.wipe_secure_state();
            }
        }
    })
}

fn read_vault_key(env: &mut JNIEnv, key: &JByteArray) -> Option<[u8; 32]> {
    match env.convert_byte_array(key) {
        Ok(bytes) if bytes.len() == 32 => {
            let mut out = [0u8; 32];
            out.copy_from_slice(&bytes);
            Some(out)
        }
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Inbound
// ---------------------------------------------------------------------------

#[derive(Serialize)]
struct ReceiveResultJson {
    /// Always true when this object is returned; verification failures return null.
    verified: bool,
    packet_type: &'static str,
    sender_id: String,
    msg_id: String,
    timestamp: u64,
    ttl: u8,
    addressed_to_us: bool,
    should_relay: bool,
    latitude: Option<f32>,
    longitude: Option<f32>,
    /// Hex of the packet to rebroadcast, when `should_relay` is set.
    relay_packet_hex: Option<String>,
    /// Hex of an ACK to transmit back to the sender.
    ack_packet_hex: Option<String>,
    /// Hex of the next handshake message to transmit back.
    handshake_reply_hex: Option<String>,
    /// Decrypted text: private chat addressed to us, or a group message this
    /// device holds the key for.
    plaintext: Option<String>,
    /// Public SOS text. Broadcast SOS is intentionally unencrypted so any
    /// responder in range can read it.
    sos_text: Option<String>,
    /// Set when the packet belonged to a group this device is a member of.
    group_id: Option<String>,
    group_name: Option<String>,
    /// "Joined", "Rekeyed", "Removed", or "Stale" when an invite changed this
    /// device's membership.
    group_event: Option<&'static str>,
}

fn packet_type_name(t: PacketType) -> &'static str {
    match t {
        PacketType::PublicSos => "PublicSos",
        PacketType::PrivateSos => "PrivateSos",
        PacketType::Chat => "Chat",
        PacketType::Ack => "Ack",
        PacketType::ResourcePin => "ResourcePin",
        PacketType::NoiseHandshake => "NoiseHandshake",
        PacketType::GroupInvite => "GroupInvite",
        PacketType::GroupChat => "GroupChat",
    }
}

fn invite_outcome_name(outcome: &InviteOutcome) -> &'static str {
    match outcome {
        InviteOutcome::Joined => "Joined",
        InviteOutcome::Rekeyed => "Rekeyed",
        InviteOutcome::Removed => "Removed",
        InviteOutcome::Stale => "Stale",
    }
}

/// Verifies and processes an inbound frame.
///
/// Returns a JSON description of what to do next, or null if the packet failed
/// authentication. A null return means "drop it" — never "trust it anyway".
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeProcessIncoming(
    mut env: JNIEnv,
    _class: JClass,
    raw_packet: JByteArray,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let bytes = match env.convert_byte_array(&raw_packet) {
            Ok(b) => b,
            Err(_) => return std::ptr::null_mut(),
        };

        let outcome = with_node!(std::ptr::null_mut(), |node| node.process_incoming(&bytes));
        let outcome = match outcome {
            Ok(o) => o,
            Err(_) => return std::ptr::null_mut(),
        };

        let header = &outcome.packet.header;
        let sos_text = if header.packet_type == PacketType::PublicSos {
            String::from_utf8(outcome.packet.payload.clone()).ok()
        } else {
            None
        };

        let result = ReceiveResultJson {
            verified: true,
            packet_type: packet_type_name(header.packet_type),
            sender_id: to_hex(&header.sender_id),
            msg_id: to_hex(&header.msg_id),
            timestamp: header.timestamp,
            ttl: header.ttl,
            addressed_to_us: outcome.addressed_to_us,
            should_relay: outcome.should_relay,
            latitude: header.location.as_ref().map(|l| l.latitude),
            longitude: header.location.as_ref().map(|l| l.longitude),
            relay_packet_hex: if outcome.should_relay {
                outcome.packet.to_bytes().ok().map(|b| to_hex(&b))
            } else {
                None
            },
            ack_packet_hex: outcome
                .ack
                .as_ref()
                .and_then(|p| p.to_bytes().ok())
                .map(|b| to_hex(&b)),
            handshake_reply_hex: outcome
                .handshake_reply
                .as_ref()
                .and_then(|p| p.to_bytes().ok())
                .map(|b| to_hex(&b)),
            plaintext: outcome
                .plaintext
                .and_then(|p| String::from_utf8(p).ok()),
            sos_text,
            group_id: outcome.group_id.as_ref().map(|g| to_hex(g)),
            group_name: outcome.group_name.clone(),
            group_event: outcome.group_event.as_ref().map(invite_outcome_name),
        };

        json_out(&mut env, &result)
    })
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

#[derive(Serialize)]
struct PinJson {
    pin_id: String,
    pin_type: &'static str,
    latitude: f32,
    longitude: f32,
    label: String,
    created_at: u64,
    expires_at: u64,
    creator_id: String,
}

#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeActivePinsJson(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    guard(std::ptr::null_mut(), || {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs();

        let pins = with_node!(std::ptr::null_mut(), |node| node
            .pin_store
            .get_active_pins(now));

        let json: Vec<PinJson> = pins
            .into_iter()
            .map(|p| PinJson {
                pin_id: to_hex(&p.pin_id),
                pin_type: match p.pin_type {
                    ResourcePinType::WaterPoint => "WaterPoint",
                    ResourcePinType::Shelter => "Shelter",
                    ResourcePinType::MedicalStation => "MedicalStation",
                    ResourcePinType::Hazard => "Hazard",
                    ResourcePinType::Roadblock => "Roadblock",
                },
                latitude: p.latitude,
                longitude: p.longitude,
                label: p.label,
                created_at: p.created_at,
                expires_at: p.expires_at,
                creator_id: to_hex(&node_id_from_pubkey(&p.creator_pubkey)),
            })
            .collect();

        json_out(&mut env, &json)
    })
}

/// Feeds battery telemetry into the duty-cycle dampener.
#[no_mangle]
pub extern "system" fn Java_org_meshline_app_bridge_MeshCoreBridge_nativeUpdateBattery(
    _env: JNIEnv,
    _class: JClass,
    battery_level: jint,
    is_charging: jboolean,
) {
    guard((), || {
        let state = if is_charging == JNI_TRUE {
            BatteryPowerState::Charging
        } else if battery_level <= 20 {
            BatteryPowerState::LowPowerSaver
        } else {
            BatteryPowerState::Normal
        };

        if let Ok(slot) = NODE_INSTANCE.lock() {
            if let Some(node) = slot.as_ref() {
                node.routing.set_battery_state(state);
            }
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_round_trips() {
        let bytes = [0x00u8, 0x0f, 0xa5, 0xff];
        assert_eq!(to_hex(&bytes), "000fa5ff");
        assert_eq!(from_hex("000fa5ff").unwrap(), bytes);
    }

    #[test]
    fn hex_rejects_malformed_input() {
        assert!(from_hex("abc").is_none(), "odd length");
        assert!(from_hex("zz").is_none(), "non-hex digits");
    }

    #[test]
    fn node_id_requires_exactly_sixteen_bytes() {
        assert!(node_id_from_hex(&"ab".repeat(16)).is_some());
        assert!(node_id_from_hex(&"ab".repeat(15)).is_none());
        assert!(node_id_from_hex(&"ab".repeat(17)).is_none());
    }

    #[test]
    fn guard_converts_panic_into_fallback() {
        let value = guard(-1, || panic!("boom"));
        assert_eq!(value, -1, "panics must never cross the JNI boundary");
    }
}
