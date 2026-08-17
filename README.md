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
* **Private groups** — a group key delivered to each member individually inside
  an already-established pairwise session, so nobody can be added to a group
  without a completed mutual handshake first. Messages are addressed to a
  derived tag rather than a group name, and removing a member rotates the key.
* **Safety numbers** — a 60-digit value both devices display identically, to be
  compared in person. Until it is checked, the app says the identity is
  unverified rather than showing a green tick.
* **Panic wipe** — destroys every session key, group key, and stored message on
  the device, while keeping the mesh identity so verified contacts still
  recognise you.
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
| Group confidentiality | A 32-byte group key, distributed to each member inside their pairwise Noise session. Messages use XChaCha20-Poly1305 with the group id, epoch, sender, and counter bound as associated data. |
| Group membership control | Only the creator can add or remove members. Removal rotates the key and bumps the epoch, so a removed device cannot read anything sent afterwards. |
| Identity verification | A 60-digit safety number derived from both identity keys by an iterated hash, ordered so both devices render the same string. Unverified sessions are labelled as such throughout the UI. |
| Identity at rest | The 32-byte identity secret is wrapped by an Android Keystore AES-GCM key and excluded from cloud backup and device transfer. |
| Secure state at rest | Session keys, group keys, and verification decisions are sealed by the core with XChaCha20-Poly1305 under a 32-byte key held in the Keystore. Kotlin only ever handles the sealed blob. |
| Message history at rest | Stored encrypted under a separate Keystore key, capped at the most recent 2000 messages. |
| Pre-authentication input | BLE fragment reassembly happens before any signature can be checked, so the reassembly table is bounded on every axis: fragment count, packet size, concurrent reassemblies per peer and in total, and a timeout. A peer that sends one fragment and goes quiet cannot pin memory, and a noisy peer cannot evict a quiet one's part-built packet. |

**There is no plaintext fallback anywhere.** If the native core is unavailable or
no session exists, the app refuses to send and says so. A messenger that silently
downgrades is more dangerous than one that fails loudly.

### What the crypto does not protect

* **Traffic analysis.** Group messages carry a tag derived from the group key.
  It is opaque to non-members and changes on every rekey, but within one epoch
  it is constant — so an observer with a radio can count a group's traffic and
  see which node ids transmit it, without learning who the group is or what was
  said. Hiding that would mean trial-decrypting every packet against every known
  group, which a phone relaying for a crowd cannot afford.
* **First-contact impersonation, until you verify.** Noise-XX between strangers
  is trust-on-first-use. An attacker present at the very first handshake is
  authenticated to both sides. Comparing safety numbers is the only thing that
  detects this, which is why the UI keeps asking.
* **Group forward secrecy between rekeys.** Pairwise sessions ratchet; a group
  key does not. A seized device reveals the group traffic it could already read.
  Rotating the key is what limits the damage going forward.
* **A seized unlocked device.** Session and group keys are persisted so that a
  restart does not force a fresh handshake with every peer. They are encrypted
  under a hardware-backed key, but an adversary with the device unlocked has the
  app's access. The panic wipe exists for exactly this moment.

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
│   └── ui/                  # Design system, SOS, Chat, Pins, Radar
├── site/                    # Marketing site + hosted privacy policy
├── PRIVACY.md               # Privacy policy (source of truth for site/privacy.html)
└── RELEASE.md               # Signing, verification, and Play checklist
```

## Site

`site/` is a static, dependency-free pair of pages — `index.html` and
`privacy.html` — meant for `meshline.praharilabs.com`. There is no build step:
copy the directory to the web root. `privacy.html` is `PRIVACY.md` rendered, and
Play requires that URL in the listing, so the two must be updated together.

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
