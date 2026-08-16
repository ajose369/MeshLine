# MeshLine — Offline Disaster Communication

[![Rust Core](https://img.shields.io/badge/Rust-1.75+-orange.svg)](https://www.rust-lang.org/)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-green.svg)](https://developer.android.com/jetpack/compose)
[![Crypto](https://img.shields.io/badge/Crypto-Noise--XX%20%2B%20Ed25519-blue.svg)](#security-model)

> **No cell tower, no internet, no SIM, no server.**
> Emergency messaging that hops directly between nearby phones over Bluetooth LE.

---

## What it does

* **One-tap emergency SOS** — a public broadcast with your coordinates,
  rate-limited by proof-of-work, relayed onward by every phone in range.
* **End-to-end encrypted chat** — mutual Noise-XX handshake, authenticated
  against a long-lived Ed25519 mesh identity. Relays carry the ciphertext and
  cannot read it.
* **Signed resource pins** — water, shelter, medical, hazards and blocked routes,
  each carrying its own creator signature and an expiry, listed by distance and
  bearing from wherever you are.
* **Store-and-forward relaying** — your phone carries other people's packets to
  extend the network, with duplicate suppression and TTL-bounded flooding.
* **Battery-aware duty cycling** — ordinary traffic is dampened as the battery
  drops; SOS traffic never is.

### What it does *not* do

Being straight about this matters more than a longer feature list:

* **Bluetooth LE only.** LoRa and Wi-Fi Direct are not implemented in this
  release. See §7 of [RELEASE.md](RELEASE.md).
* **Range is BLE range** — tens of metres, extended only by other phones running
  MeshLine. This is not a satellite messenger.
* **At most 8 hops.** In a chain topology that is roughly 7 devices from the
  origin.
* **Delivery is best-effort.** Nothing guarantees an SOS reaches anyone.
* **Public SOS broadcasts are unencrypted** — deliberately, so any stranger in
  range can read and act on them.
* **No offline basemap.** The Pins screen gives distance and bearing rather than
  a map, because shipping a fabricated basemap in a disaster app invites someone
  to navigate by it.

---

## Security model

The mesh is a hostile medium: anyone in radio range can transmit anything.

| Property | Mechanism |
|---|---|
| Message authenticity | Every packet carries the sender's Ed25519 public key and a signature over the immutable header and payload. `Packet::verify()` runs **before** any other processing on receive. |
| Sender identity binding | `sender_id` must equal `SHA-256(sender_pubkey)[..16]`, so an attacker cannot attach their own key and claim someone else's id. |
| Relay tolerance | `ttl` and `flags` are excluded from the signed payload, since relays mutate them. Signatures therefore survive every hop. |
| Chat confidentiality | Noise_XX_25519_ChaChaPoly_SHA256 via [`snow`](https://crates.io/crates/snow), with each side's Noise static key signed by its mesh identity so the session is bound to a known node. |
| Replay resistance | Explicit per-message nonces with a sliding window, plus an LRU duplicate cache and a packet freshness window. |
| Pin integrity | Each pin carries its own creator signature, verified independently of the packet that relayed it, so a malicious relay cannot rewrite a pin in flight. |
| Anti-flooding | Proof-of-work on public SOS, plus a per-sender token bucket charged only *after* the sender's identity is proven. |
| Identity at rest | The 32-byte identity secret is wrapped by an Android Keystore AES-GCM key and excluded from cloud backup and device transfer. |

**There is no plaintext fallback anywhere.** If the native core is unavailable or
no session exists, the app refuses to send and says so. A messenger that silently
downgrades is more dangerous than one that fails loudly.

### Degraded modes

The app fails soft wherever the mesh can still do useful work, and hard only
where it cannot:

| Condition | Behaviour |
|---|---|
| Location denied (Android 12+) | Relays and broadcasts normally; SOS carries no coordinates and the UI says "Permission not granted". |
| Location denied (Android 11 and below) | Blocking — a BLE scan without it silently returns nothing, so there is no useful degraded mode. |
| Bluetooth off | Relay service stays up and reports "Bluetooth is turned off". |
| Notifications denied | Relay keeps running, silently. |
| Native core missing | Hard stop. The app refuses to send or verify anything and explains why. |

---

## Architecture

```
                 +---------------------------------------+
                 |       MeshLine Android UI             |
                 |  (Jetpack Compose)                    |
                 +-------------------+-------------------+
                                     |
                       JNI (12 symbols, panic-guarded)
                                     |
+------------------------------------+------------------------------------+
|                       meshline-core (Rust)                              |
|                                                                         |
|  +----------------+  +-----------------+  +-------------------------+   |
|  | Noise-XX       |  | Flood routing   |  | Signed GIS pin store    |   |
|  | session mgr    |  | + duty cycling  |  | (TTL expiry)            |   |
|  +----------------+  +-----------------+  +-------------------------+   |
|  +-------------------------------------------------------------------+  |
|  | Verify-first receive path: signature -> freshness -> PoW -> limit  |  |
|  +-------------------------------------------------------------------+  |
+------------------------------------+------------------------------------+
                                     |
                          +----------+----------+
                          | BLE GATT transport  |
                          +---------------------+
```

## Workspace layout

```
MeshLine/
├── crates/
│   ├── meshline-core/       # Packets, crypto, routing, pins, rate limiting
│   ├── meshline-ffi/        # JNI bindings (every export panic-guarded)
│   └── meshline-sim/        # Topology simulator with pass/fail assertions
├── android/app/src/main/java/org/meshline/app/
│   ├── bridge/              # MeshCoreBridge — fails closed, no fallback
│   ├── db/                  # Store-and-forward custody
│   ├── location/            # Real position fixes, no placeholder coordinates
│   ├── permissions/         # Runtime permission model
│   ├── security/            # Keystore-wrapped identity persistence
│   ├── service/             # Foreground relay service
│   ├── transport/           # BLE GATT
│   └── ui/                  # SOS, Chat, Pins, Radar
├── PRIVACY.md               # Privacy policy (publish before submitting)
└── RELEASE.md               # Signing, verification, and Play checklist
```

---

## Building

### Rust core

```bash
cargo test --workspace      # 53 tests
cargo run -p meshline-sim   # asserts reachability, SOS priority, forgery rejection
```

### Android

Requires JDK 17, Android SDK platform 36, NDK `27.1.12297006`, and:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
```

Then:

```bash
cd android
./gradlew assembleDebug     # Gradle invokes cargo-ndk automatically
./gradlew testDebugUnitTest # 14 tests
./gradlew bundleRelease     # signed AAB, if keystore.properties exists
```

The native `.so` files are build outputs written into
`app/build/rustJniLibs/<variant>/` by the Gradle build; they are not checked in.

See [RELEASE.md](RELEASE.md) for signing, pre-upload verification, the on-device
smoke test, and the Play Console checklist.
