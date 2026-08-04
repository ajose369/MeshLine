# MeshLine — Offline Disaster Communication System

[![Rust Core](https://img.shields.io/badge/Rust-1.75+-orange.svg)](https://www.rust-lang.org/)
[![Android App](https://img.shields.io/badge/Android-Jetpack%20Compose-green.svg)](https://developer.android.com/jetpack/compose)
[![Protocol](https://img.shields.io/badge/Security-Noise--XX%20%2B%20Ed25519-blue.svg)](#security--cryptography)

> **Zero cellular, zero internet, zero SIM required.**  
> A phone-to-phone mesh communication platform built for natural disasters, emergency rescues, civil defense, and offline field coordination.

---

## 🌟 Key Features

* **🚨 One-Tap Emergency SOS**: Unencrypted public emergency broadcasts tagged with GPS telemetry and validated via Proof-of-Work to bypass mesh rate limits.
* **🔒 Serverless Noise-XX E2E Encrypted Chat**: Mutual key exchange and Double Ratchet encrypted messaging over multi-hop relays without a central server.
* **🗺️ Offline GIS Resource Pins**: Signed spatial markers for Water Points, Emergency Shelters, Medical Stations, and Roadblock Hazards with TTL staleness expiration.
* **📡 Multi-Transport Mesh Network**:
  * **Bluetooth Low Energy (BLE)**: GATT Server/Client mesh discovery (`0000FE60...`).
  * **USB OTG LoRa Bridge**: Heltec V3 / LilyGO T-Beam 915MHz hardware bridge support (`0x94` Meshtastic SLIP header framing).
  * **Wi-Fi Direct Socket Transfer**: High-speed P2P socket server (`port 8888`) for transferring offline photos, voice notes, and map extracts.
* **🔋 Adaptive Duty-Cycling**: Battery-aware flood dampening (`Charging` = 100%, `Normal` = 85%, `Low Power` = 30% relay rate).
* **💾 Store-and-Forward Custody**: Retains undelivered packets until ACK delivery confirmation or TTL hop expiration.

---

## 🏗️ System Architecture

```
                 +---------------------------------------+
                 |       MeshLine Android UI             |
                 |  (Jetpack Compose - Obsidian Glass)   |
                 +-------------------+-------------------+
                                     |
                                 JNI Bridge
                                     |
+------------------------------------+------------------------------------+
|                       meshline-core (Rust Engine)                      |
|                                                                        |
|  +----------------+  +-----------------+  +-------------------------+  |
|  | Noise-XX E2EE  |  | Adaptive Flood  |  | Signed GIS Pin Store    |  |
|  | Crypto Engine  |  | Routing + Duty  |  | (OSM Vector Extracts)   |  |
|  +----------------+  +-----------------+  +-------------------------+  |
|  +------------------------------------------------------------------+  |
|  | Proof-of-Work Anti-Spam & Sender Token-Bucket Rate Limiter       |  |
|  +------------------------------------------------------------------+  |
+------------------------------------+------------------------------------+
                                     |
+------------------------------------+------------------------------------+
|                       Multi-Transport Layer                             |
|  +--------------------+   +-------------------+   +------------------+  |
|  | BLE GATT Service   |   | USB OTG LoRa      |   | Wi-Fi Direct     |  |
|  | (Peer Discovery)   |   | (915MHz Hardware) |   | (Socket Server)  |  |
|  +--------------------+   +-------------------+   +------------------+  |
+-------------------------------------------------------------------------+
```

---

## 💻 Workspace Structure

```
MeshLine/
├── crates/
│   ├── meshline-core/       # Core Rust engine (Noise-XX, Flood Routing, PoW, GIS Pins)
│   ├── meshline-ffi/        # JNI C-bindings for Android integration
│   └── meshline-sim/        # Disaster network topology benchmark simulator
├── android/
│   └── app/                 # Kotlin Android Application (Jetpack Compose UI)
│       └── src/main/java/org/meshline/app/
│           ├── bridge/      # MeshCoreBridge JNI wrapper
│           ├── db/          # Store-and-Forward SQLite custody manager
│           ├── gis/         # Offline vector map tile manager
│           ├── service/     # Foreground MeshRelayService
│           ├── transport/   # BLE, Wi-Fi Direct, and USB OTG LoRa drivers
│           └── ui/          # SosScreen, ChatScreen, MapScreen, RadarScreen
└── offline-mesh-comms-plan.md
```

---

## 🚀 Building & Testing

### 1. Build Rust Core & Run Checks
```bash
cargo check --workspace
cargo test --workspace
```

### 2. Run Network Topology Benchmark
```bash
cargo run -p meshline-sim
```

### 3. Build Android App
```bash
cd android
./gradlew assembleDebug
```
