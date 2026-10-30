# MeshLine — Play Store Release Guide

Everything needed to produce and ship a signed release build.

---

## 1. Create the upload keystore (once)

Losing this file means you can never update the app on Play under the same
listing. Back it up somewhere durable and offline.

```bash
keytool -genkeypair -v \
  -keystore meshline-upload.jks \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -alias meshline-upload
```

Then create `android/keystore.properties` (gitignored, never commit it):

```properties
storeFile=/absolute/path/to/meshline-upload.jks
storePassword=<store password>
keyAlias=meshline-upload
keyPassword=<key password>
```

If this file is absent, `assembleRelease` still succeeds but produces an
**unsigned** artifact. That is intentional: silently falling back to the debug
key yields a build that looks releasable but can never be uploaded.

---

## 2. Build

```bash
cd android
./gradlew bundleRelease      # app-release.aab — this is what Play wants
./gradlew assembleRelease    # app-release.apk — for direct device testing
```

Outputs:
- `android/app/build/outputs/bundle/release/app-release.aab`
- `android/app/build/outputs/apk/release/app-release.apk`

The Gradle build invokes `cargo ndk` automatically and writes
`libmeshline_ffi.so` for `arm64-v8a`, `armeabi-v7a`, and `x86_64` into
`app/build/rustJniLibs/<variant>/`. Debug and release get separate directories so
an unstripped debug library can never end up in a release artifact.

**Prerequisites**: JDK 17, Android SDK with platform 36, NDK `27.1.12297006`,
Rust with the three Android targets, and `cargo-ndk`:

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk
```

---

## 3. Pre-upload verification

Run all of these before every release. They are cheap and they catch the failure
modes that only appear in a minified release build.

```bash
# Rust core: 53 tests
cargo test --workspace

# Network property assertions (reachability, SOS priority, forgery rejection)
cargo run -p meshline-sim

# Android unit tests: 14 tests
cd android && ./gradlew testDebugUnitTest

# Release lint — MissingPermission is configured as a build-breaking error
./gradlew lintVitalRelease
```

**Verify the JNI symbols survived R8.** A mismatch here fails only at runtime, on
a user's device, with the app silently refusing to send anything:

```bash
NM="$ANDROID_HOME/ndk/27.1.12297006/toolchains/llvm/prebuilt/<host>/bin/llvm-nm"
"$NM" -D --defined-only android/app/build/rustJniLibs/release/arm64-v8a/libmeshline_ffi.so \
  | grep -o "Java_org_meshline[A-Za-z_0-9]*" | sed 's/.*MeshCoreBridge_//' | sort > /tmp/so.txt
grep -oP 'external fun \K\w+' \
  android/app/src/main/java/org/meshline/app/bridge/MeshCoreBridge.kt | sort > /tmp/kt.txt
diff /tmp/so.txt /tmp/kt.txt && echo "JNI symbols match"
```

**Verify all three ABIs shipped:**

```bash
unzip -l android/app/build/outputs/bundle/release/app-release.aab | grep meshline_ffi
```

---

## 4. On-device smoke test

### Already verified (single device, Android 16 / API 36, arm64-v8a)

Recorded here so a future release can tell what regressed. All confirmed on a
physical handset unless noted:

- [x] Installs and launches with no crash; `libmeshline_ffi.so` loads
      (`nativeloader: Load ... libmeshline_ffi.so ... ok`).
- [x] Permission gate renders first and blocks the app until granted.
- [x] After granting, the relay service enters the foreground correctly
      (`isForeground=true foregroundId=1001 types=0x00000010`, i.e.
      `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`).
- [x] Mesh node id is produced by the Rust core over JNI and rendered in Radar.
- [x] **Identity survives a full force-stop and relaunch** — the wrapped blob in
      `shared_prefs/meshline_identity.xml` is byte-identical across restarts, and
      is ciphertext (12-byte IV + 32-byte secret + 16-byte GCM tag), not the key.
- [x] With Bluetooth off, the service degrades gracefully rather than crashing
      (`W MeshRelayService: BLE transport unavailable: Bluetooth is turned off.`).
- [x] With Bluetooth on, BLE advertising starts on a real radio
      (`I MeshBle: Advertising started.`).
- [x] **Runs with location denied**: with `ACCESS_FINE_LOCATION: granted=false`
      the relay still starts and advertises, and the SOS screen reports
      "Permission not granted" instead of an indefinite "Searching…". Verified
      against the real-world case of a user granting location as "only this
      time" and it silently expiring.
- [x] SOS button stays disabled until the user types a description.
- [x] Sending an SOS builds a signed, proof-of-worked packet with no errors, and
      the result card states honestly whether coordinates were attached and that
      no devices are in range — it never claims delivery. *(emulator)*
- [x] Position acquires a real fix and renders it (`12.9716° N, 77.5946° E
      (±5m)` from an injected mock). *(emulator)*
- [x] All four tabs render with honest empty states; no `FATAL EXCEPTION` in
      logcat across the session.

### Still required before release: the two-device mesh test

**None of the above proves the mesh works.** Peer discovery, relaying, the Noise
handshake, and encrypted chat have not been exercised between two real radios —
an emulator has no usable BLE stack, so this cannot be faked. Run this on **two
physical devices**:

1. Install on **two** physical devices.
2. Grant permissions on both. Confirm the permission gate appears first and the
   app does not proceed until the blocking permissions are granted.
3. Confirm the relay notification appears on both.
4. Send an SOS from device A; confirm it appears on device B within ~30s.
5. Confirm the SOS carries real coordinates matching the actual location, not a
   placeholder.
6. On device B, open Chat, select device A, tap "Set up encrypted link", wait for
   the peer to show "encrypted", then send a message. Confirm it arrives.
7. Airplane mode both devices with Bluetooth on. Confirm everything still works —
   this is the entire premise of the app.
8. Create a resource pin on A; confirm it appears on B with a plausible distance
   and bearing.
9. Revoke the Bluetooth permission on A from Settings while the app runs.
   Confirm the app shows the permission gate and does not crash.

---

## 5. Play Console — Data Safety answers

Answer the Data Safety form as follows. These match [PRIVACY.md](PRIVACY.md) and
the app's actual behaviour.

| Question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all user data encrypted in transit? | Not applicable — no data is transmitted to any server. Peer-to-peer private messages are end-to-end encrypted; public SOS broadcasts are intentionally unencrypted. |
| Do you provide a way for users to request data deletion? | Data is stored only on-device; uninstalling deletes it. |

**Say this explicitly in the listing and the Data Safety notes**: a public SOS is
broadcast unencrypted by design, so that any nearby responder can read it.
Claiming blanket encryption would be false and is the kind of discrepancy that
gets an app pulled.

### Permission declarations

- **Foreground service (`connectedDevice`)**: required to keep relaying mesh
  traffic while backgrounded. Play asks for a short justification and a demo
  video showing the feature.
- **Precise location**: justified in-app in the permission rationale screen and
  in the privacy policy.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is **not** declared. Play restricts it,
  and the foreground service is the sanctioned mechanism.

---

## 6. Store listing assets still to produce

These are the remaining human/design tasks; they cannot be generated from the
codebase.

- [ ] **App icon, 512×512 PNG** (32-bit, with alpha). The in-app adaptive icon is
      at `app/src/main/res/drawable/ic_launcher_foreground.xml` and can be
      exported as the basis.
- [ ] **Feature graphic, 1024×500 PNG/JPG.**
- [ ] **Phone screenshots**, 2–8 images, min 320px on the short edge. Capture the
      SOS, Chat, Pins, and Radar tabs with **real** mesh traffic between two
      devices — do not stage them with fabricated peers.
- [ ] **Short description** (≤80 chars), e.g.
      "Emergency messaging between nearby phones. No internet, no SIM, no server."
- [ ] **Full description** (≤4000 chars). Must not claim LoRa or Wi-Fi Direct
      support — see the scope note below.
- [ ] **Privacy policy URL** — deploy `site/` and paste
      `https://meshline.praharilabs.com/privacy.html`. That page is
      `PRIVACY.md` rendered; keep the two in step when either changes.
- [ ] **Content rating questionnaire.**
- [ ] **Target audience**: select adults; this is not a children's app.

---

## 7. Scope note: what this release does and does not ship

The README previously advertised three transports. Only **Bluetooth LE** is
implemented and shipping.

`UsbSerialManager` (LoRa) and `WifiDirectManager` were removed from the app
because neither was wired into any transport path: the USB class only matched
generic USB-serial vendor IDs and never opened the device, and the Wi-Fi class
opened an unauthenticated `ServerSocket` on port 8888 that accepted arbitrary
payloads from anyone on the network. They remain in git history if you want to
finish them. Their permissions were removed from the manifest to match.

**Do not restore the LoRa or Wi-Fi Direct claims to the store listing until the
code behind them exists and has been tested against hardware.**

---

## 8. Known limitations to state honestly in the listing

- **Range is Bluetooth LE range** — tens of metres, extended only by other phones
  running MeshLine relaying for you. It is not a substitute for a satellite
  messenger.
- **Messages traverse at most 8 hops** (`DEFAULT_TTL`). In a chain topology that
  caps reach at roughly 7 devices from the origin; `cargo run -p meshline-sim`
  reports this directly.
- **Delivery is best-effort.** There is no guarantee an SOS reaches anyone. The
  UI states this rather than implying delivery.
- **Public SOS broadcasts are unencrypted** by design.
- **An identity is per-install.** Clearing app storage or reinstalling generates a
  new mesh identity, and prior contacts will see you as a new node.
