# MeshLine — Review & Product Direction

> GPT-6 Astra via Experiential Labs · 2026-09-07 06:35
> Static inspection of the source bundle; not executed.
> tokens: prompt=318164 completion=11267

# MeshLine — Engineering and Product Review

**Recommendation: do not release this build as an emergency communication tool.** There is substantial implementation work here, including real cryptography, bounded BLE framing, encrypted storage, and a coherent interface. However, the integration currently fabricates SOS coordinates, permits session nonce reuse after crashes, loses outbound custody, and reports transmission and security outcomes that have not actually occurred.

**Review scope:** static review of the supplied files; no builds, tests, hardware experiments, or live-site checks were performed. Android build scripts, the manifest, backup rules, R8 rules, and the complete dependency lockfiles were not supplied, so their claimed configuration cannot be verified.

Paths below are relative to `MeshLine/`. For readability, **`app/` means `android/app/src/main/java/org/meshline/app/`**.

---

# PART A — Code review

## 1. What it is

MeshLine is an Android/Jetpack Compose application backed by a Rust protocol core accessed through JNI. The core implements signed packets, Noise-XX session establishment, custom persisted pairwise transport encryption, symmetric-key groups, resource pins, and probabilistic flood routing. Android owns BLE GATT discovery and fragmentation, an in-memory outbound queue, UI projections, location acquisition, and Keystore-backed persistence. A separate Rust simulator exercises idealized network graphs, while a dependency-free website markets the application and hosts its privacy policy.

## 2. Correctness bugs, ranked by severity

### Critical — release blockers

#### C1. Crashes can reuse a pairwise encryption key/nonce combination

**Files:** `crates/meshline-core/src/crypto/session.rs`; `app/db/StoreAndForwardManager.kt`; `app/security/SecureStateStore.kt`.

`TransportSession.encrypt()` uses a persisted sending key and a monotonically increasing counter as its ChaCha20-Poly1305 nonce. Android exposes the packet to `outboundQueue` before asynchronously persisting the advanced counter.

**Failing scenario:** persist counter `n`; encrypt and transmit message A using `n`; kill the process before persistence completes; restore the previous state; encrypt message B using `n` again. The two ciphertexts reuse a key and nonce, compromising confidentiality and AEAD security. Receiver replay rejection does not protect against passive interception of those ciphertexts.

**Fix:** establish a crash-safe nonce-allocation protocol before releasing ciphertext. Options include durably reserving counter ranges, or replacing persisted transport sessions with fresh, explicitly identified sessions after restart. This needs a cryptographic design review—not merely more frequent saves. Restored receive windows also roll back after uncommitted receives.

#### C2. “No coordinates attached” actually broadcasts `(0, 0)` with invented accuracy

**Files:** `app/ui/sos/SosScreen.kt`; `crates/meshline-ffi/src/lib.rs`; `crates/meshline-core/src/lib.rs`.

When no fix exists, `SosScreen` passes `0f, 0f`. JNI forwards them unchanged, and `create_public_sos()` always sets `Some(LocationTag { … accuracy_meters: 5 })`.

**Failing scenario:** deny location, send an SOS, and inspect its packet. It contains a signed location in the Gulf of Guinea, while the UI explicitly says no coordinates were attached. Even with a real fix, its measured accuracy is discarded and replaced by **5 metres**.

**Fix:** carry an explicit optional location across every layer, including measured accuracy and acquisition time. Zero is a valid coordinate, not a missing-value sentinel.

#### C3. Panic wipe can recreate readable history and does not stop in-flight transmissions

**Files:** `app/db/StoreAndForwardManager.kt`; `app/db/EncryptedMessageStore.kt`; `app/security/KeystoreVault.kt`; `app/transport/BleTransportManager.kt`.

`panicWipe()` does not synchronize with persistence or inbound processing. It also clears only the central queue, not packets already held in BLE `WriteQueueState`.

**Failing scenario:** a persistence task captures the old message list; wipe deletes history and its wrapping key; the task resumes, calls `wrap()`, generates a new wrapping key, and writes the old history back. Separately, a queued SOS already drained into a BLE connection can continue transmitting after wipe.

Deletion failures are swallowed, yet `PanicWipeControl` unconditionally displays success. Pins remain in both the Kotlin and Rust stores.

**Fix:** implement wipe as a coordinated state transition: stop transport and intake, invalidate work generations, serialize against persistence, clear all designated stores and in-flight buffers, delete keys, and return a verifiable result. State explicitly what survives.

### High severity — broken delivery, authorization, or safety information

#### H1. The Android transport does not implement flood fan-out

**Files:** `app/transport/BleTransportManager.kt`; `app/db/StoreAndForwardManager.kt`.

`onServicesDiscovered()` drains the **entire global outbound queue for one peer**. Successful writes remove that packet from local custody; there is no per-neighbor forwarding ledger.

**Failing scenario:** A has neighbors B and C, and C is the only route to D. B connects first and takes A’s outbound packets; C subsequently finds an empty queue. Relayed packets can also be handed back to the neighbor that supplied them instead of being forwarded to another branch.

**Fix:** retain packet custody independently of individual connections. Schedule bounded fan-out using per-link transfer state and packet expiry. The simulator’s “send to all neighbors” behavior is not what Android does.

#### H2. BLE retry logic drops the packet whose final fragment failed

**File:** `app/transport/BleTransportManager.kt`.

`sendNextFrame()` increments `state.index` **before** the write succeeds. `requeueUnsent()` then starts from `frames[state.index]`.

**Failing scenario:** a single-frame packet fails its write callback. `index == frames.size`, so nothing is requeued. For a multi-packet batch, failure of the final fragment of packet A starts recovery at packet B.

The return value of `writeCharacteristic()` is also ignored on both API branches. An immediately rejected operation can leave the connection waiting for a callback that will never arrive.

**Fix:** track submitted and acknowledged frame positions separately; check immediate return codes; add operation deadlines and retry the current whole packet when necessary.

#### H3. Delivery status and ACK correlation are broken at three boundaries

**Files:** `app/db/StoreAndForwardManager.kt`; `crates/meshline-ffi/src/lib.rs`; `crates/meshline-core/src/lib.rs`.

- Local history receives a fresh Kotlin UUID unrelated to the packet’s Rust-generated ID.
- Rust ACK payloads contain the original packet ID, but JNI exposes only the ACK packet’s own ID.
- Kotlin calls `markDelivered(msgId)` with that ACK ID.

**Failing scenario:** a valid recipient ACK arrives, but it cannot identify the local outgoing message.

Additionally, `drainOutbound()` marks **every queued message** sent before any BLE write succeeds. Draining an unrelated handshake can even mark a restored, permanently stranded message “relayed.” Rust generates chat ACKs even when decryption fails.

**Fix:** use one canonical packet ID throughout; expose `acked_message_id`; validate the ACK signer against the original recipient; acknowledge only the defined successful application event. Persist status transitions. GATT success, relay custody, decrypted receipt, and human response are different facts.

#### H4. Outbound messages and resource pins are not durable

**Files:** `app/db/StoreAndForwardManager.kt`; `crates/meshline-core/src/lib.rs`.

Only message display records and secure session/group/trust state are persisted. Neither raw outbound packets nor the pin store is saved.

**Failing scenario:** send an SOS with no peers nearby, background the app, and let Android kill it. The history may restore as `QUEUED`, but the actual packet is gone and is never reconstructed. All pins disappear after restart.

**Fix:** persist an encrypted outbox and pin repository. Restore custody before starting transport. Message history is not a store-and-forward queue.

#### H5. Incrementally adding group members breaks communication among members

**Files:** `crates/meshline-core/src/crypto/group.rs`; `crates/meshline-core/src/lib.rs`.

`add_member()` changes membership without changing the epoch. `invite_to_group()` sends the updated list only to the new member. Existing members reject same-epoch invites as stale.

**Failing scenario:** Alice adds Bob, delivers his invite, then adds Carol. Bob’s membership list excludes Carol, so Bob rejects Carol’s group messages with `NotAGroupMember`.

**Fix:** make every membership change an authenticated, versioned update distributed to all members. Prefer a new epoch/key on addition as well, so new members are not handed the key to previously recorded traffic.

#### H6. Rekey UI claims delivery and gives unsafe compromised-device advice

**Files:** `app/ui/chat/SecurityDialogs.kt`; `app/db/StoreAndForwardManager.kt`; `crates/meshline-core/src/lib.rs`.

`unreachable` means “could not build a pairwise-encrypted invite,” not “out of radio range.” `isComplete` means all packets were constructed—not delivered. Nevertheless, the UI says every remaining member “has the new one.”

The “Rotate group key” explanation recommends rotation after a member’s phone is taken, but rotation sends the new key to **all existing members**, including that phone.

**Failing scenario:** rotate after Bob’s phone is seized without removing Bob. The seized device receives the new key and continues reading.

**Fix:** say “key updates queued,” track per-member epoch acknowledgements, and make **Remove compromised device and rotate** the recovery workflow.

#### H7. One creator can overwrite another creator’s resource pin

**File:** `crates/meshline-core/src/gis/pins.rs`.

`upsert_pin()` verifies the incoming signature but checks only `pin_id` and increasing `created_at` against existing data.

**Failing scenario:** Mallory learns Alice’s public pin ID, creates a replacement with a later timestamp and Mallory’s own valid signature, and submits it in a new outer packet. The replacement is accepted.

The inner pin’s coordinates are not validated either; a signed pin with NaN or out-of-range coordinates can be accepted even when the outer packet has no location.

**Fix:** bind pin identity to its creator; require creator continuity for updates; validate inner coordinates, label size, timestamp ordering, and maximum lifetime.

#### H8. Pin updates created through the normal API are suppressed as duplicate packets

**Files:** `crates/meshline-core/src/lib.rs`; `crates/meshline-core/src/routing/flood.rs`.

`create_signed_pin_packet()` uses `pin_id` as the outer packet’s `msg_id`.

**Failing scenario:** Alice updates a pin with the same ID and a newer timestamp. Receivers that have seen its previous packet treat the update as a duplicate and skip pin processing.

**Fix:** separate resource identity, resource revision, and transport message identity.

#### H9. Authenticated replays can exhaust an innocent sender’s rate limit

**Files:** `crates/meshline-core/src/lib.rs`; `crates/meshline-core/src/security/rate_limit.rs`.

The sender bucket is charged before duplicate detection.

**Failing scenario:** an observer replays one captured, valid packet ten times, consuming the originator’s **10-token** burst allowance. The originator’s new SOS can then be rate-limited. No signature forgery is required.

**Fix:** suppress authenticated exact duplicates before charging new-message quotas, with a bounded pre-verification work budget as well.

#### H10. Deduplication is not namespaced by sender

**File:** `crates/meshline-core/src/routing/flood.rs`.

The seen cache keys only on `msg_id`.

**Failing scenario:** an attacker sends a valid packet under their own identity using an observed victim message ID, arriving first at a downstream node. The victim’s genuine packet is then suppressed as a duplicate.

**Fix:** key by authenticated origin plus message ID, or by an appropriate immutable packet digest.

#### H11. Cold-start discovery cannot populate Chat without application traffic

**Files:** `app/transport/BleTransportManager.kt`; `app/db/StoreAndForwardManager.kt`; `app/ui/chat/ChatScreen.kt`.

BLE discovery does not exchange identities. Empty connections immediately disconnect. A peer enters `peersFlow` only after receiving an authenticated application packet.

**Failing scenario:** two fresh installs sit next to each other with empty queues. They can scan and connect, but neither obtains a selectable Chat peer. There is no normal “start a conversation” bootstrap.

**Fix:** add a bounded signed identity/capability exchange on connection, or an explicit QR contact flow.

#### H12. Bluetooth recovery and service health are not implemented as state transitions

**Files:** `app/service/MeshRelayService.kt`; `app/transport/BleTransportManager.kt`; `app/MainActivity.kt`.

Transport starts only in service `onCreate()`. There is no Bluetooth-state receiver or retry loop.

**Failing scenario:** launch with Bluetooth off, then turn it on. The sticky service remains alive without restarting BLE. Calling start service again does not rerun `onCreate()`.

Startup can also report success despite a null GATT server or scanner; asynchronous scan/advertising failures are not represented in a shared health model. Housekeeping overwrites the original failure notification with a peer count after 30 seconds.

**Fix:** introduce a lifecycle-owned transport state machine with retry, backoff, permission checks, and observable failure states.

#### H13. Radar fabricates signal strength and confuses origins with neighbors

**Files:** `app/db/StoreAndForwardManager.kt`; `app/transport/BleTransportManager.kt`; `app/ui/radar/RadarScreen.kt`.

Incoming packet origins become peers. The transport never supplies RSSI to `observePeer()`, so its default is **0 dBm**. Radar clamps that to the strongest range and renders four bars.

**Failing scenario:** a distant sender’s packet arrives through a relay. Radar shows that origin as a strong nearby device at a hash-derived angle. Its sweep is driven by core readiness, not transport activity.

**Fix:** distinguish direct BLE links, authenticated identities heard through the mesh, and actual custody holders. Unknown RSSI must render as unknown; remove positional plotting without positional evidence.

#### H14. Received SOS coordinates are discarded

**Files:** `crates/meshline-ffi/src/lib.rs`; `app/db/StoreAndForwardManager.kt`; `app/db/MessageEntity.kt`; `app/ui/chat/ChatScreen.kt`.

JNI exposes received latitude and longitude, but Kotlin does not store them. The public feed displays only message text, sender, and time.

**Failing scenario:** a survivor sends accurate coordinates, but the responder cannot retrieve them from the SOS message.

**Fix:** introduce a structured SOS entity and detail screen with location, uncertainty, age, and response actions.

### Medium severity

#### M1. Fix freshness is checked only at acquisition, not at use

**Files:** `app/location/MeshLocationProvider.kt`; `app/ui/sos/SosScreen.kt`; `app/ui/map/MapScreen.kt`.

A retained `Fix` never ages in the UI. Active callbacks bypass the cached-fix age filter; wall-clock subtraction accepts future-dated cached fixes.

**Scenario:** obtain a fix, lose GPS, remain on the screen for 30 minutes, then send. The old fix is still treated as usable.

Use `Location.elapsedRealtimeNanos`, retain acquisition time, and revalidate immediately before sending.

#### M2. Direct conversations disappear when peers go stale

**File:** `app/ui/chat/ChatScreen.kt`.

After the five-minute peer timeout, selection falls back to Public and the peer chip disappears, even though encrypted history and session keys remain.

A single shared `inputText` also follows the user between conversations, allowing an Alice draft to be sent to Bob. Tab changes dispose `remember` state and lose drafts.

Separate persistent conversations from live reachability; retain drafts by conversation ID.

#### M3. Concurrent persistence can corrupt or remove the last good snapshot

**Files:** `app/db/StoreAndForwardManager.kt`; `app/db/EncryptedMessageStore.kt`; `app/security/SecureStateStore.kt`.

Executor writes and lifecycle-triggered `persistNow()` calls can run simultaneously, using the same `.tmp` filenames. One writer can rename another’s data; a failed rename path deletes the target.

Serialize all writes, use a crash-safe replacement mechanism, and propagate failures rather than silently returning successful UX.

#### M4. Message retention is capped on disk, not in memory

**File:** `app/db/StoreAndForwardManager.kt`.

`messageStore` grows without limit and is fully sorted on every publication. Only `EncryptedMessageStore.save()` trims to 2,000 records.

Long-running relays therefore retain more than the advertised history cap and incur increasing memory, sorting, and serialization costs.

#### M5. Android 12+ location requests omit coarse location

**File:** `app/permissions/MeshPermissions.kt`.

The request includes `ACCESS_FINE_LOCATION` but not `ACCESS_COARSE_LOCATION`. Android 12’s precise-location request flow expects both together; some Android 12 releases ignore fine-only requests.

Request coarse and fine together, and support approximate location with honest uncertainty.

#### M6. The advertised Rust minimum version is incorrect

**Files:** `README.md`; `crates/meshline-ffi/src/lib.rs`.

The README advertises Rust **1.75+**, but `usize::is_multiple_of()` requires a newer compiler.

Declare and test the real `rust-version`, or replace the call with a compatible expression. Supplied build fingerprints are not evidence that the documented minimum works.

## 3. Security & data handling

### What is sound

- Packet verification binds the sender ID to its Ed25519 public key and uses strict signature verification.
- Private messaging has no plaintext fallback.
- Noise identity proofs bind the authenticated Noise static key to a mesh identity.
- Group ciphertext binds group, epoch, sender, and counter through AAD.
- Replay windows record counters only after successful AEAD verification.
- Outer packet decoding and group-invite decoding have size limits.
- BLE reassembly has useful per-device, total-count, fragment-count, and byte limits.
- Local ciphertext uses authenticated encryption rather than an unauthenticated storage cipher.

These are good foundations, not proof of end-to-end system security.

### Authentication and authorization

**A valid signature proves authorship, not truth, proximity, or responder credentials.** The comment in `app/db/ResourcePinEntity.kt` saying pins can be rendered “without further qualification” is wrong at the product-security level.

Additional concerns:

- `SessionManager.accept_handshake()` has no simultaneous-initiation resolution. Two initiators can consume incompatible handshake messages and lose their pending state.
- The initiator is promoted before the responder receives message 3. There is no reliable handshake retransmission or completion confirmation.
- `begin_handshake()` does not enforce the 32-pending-session cap applied to inbound handshakes.
- Group admins can leave locally, orphaning membership administration; no transfer or dissolution workflow exists.

### Forward secrecy is overstated

**Files:** `README.md`; `crates/meshline-core/src/crypto/group.rs`; `crates/meshline-core/src/crypto/session.rs`.

The documentation says pairwise sessions ratchet. They do not: transport keys remain fixed while counters advance. Compromise of a live session key reveals recorded traffic encrypted with that key and permits reading subsequent traffic until a new handshake changes it.

Describe the actual property: Noise handshake forward secrecy against later identity-key compromise, but **no per-message ratchet or post-compromise recovery** in the current transport.

### Flooding and resource exhaustion

**Files:** `crates/meshline-core/src/security/rate_limit.rs`; `crates/meshline-core/src/crypto/noise.rs`; `crates/meshline-core/src/gis/pins.rs`; `app/db/StoreAndForwardManager.kt`.

- SOS PoW hashes only **message text + nonce**. One solution can be reused across arbitrary message IDs, identities, locations, and timestamps.
- Twelve bits costs about **4,096 hashes on average**; it is not a meaningful Sybil defense.
- Rate-limit buckets never expire.
- Established sessions, trust records, group count, pins, and outbound custody lack global bounds.
- Every new valid attacker identity gets a new burst allowance.
- Normal traffic and handshakes have no PoW requirement.

Bind work to a canonical packet challenge, but prioritize bounded memory, aggregate CPU/airtime budgets, fair scheduling, and local abuse controls. Do not claim PoW prevents flooding.

### Deserialization and validation

`crates/meshline-core/src/lib.rs` uses unrestricted `bincode::deserialize()` for resource pins and secure-state contents. The outer packet and encrypted vault have byte ceilings, but inner schemas still need explicit collection limits and semantic validation. I have not demonstrated an allocation exploit here; fuzz it rather than assuming byte length alone proves safety.

Also:

- JNI `from_hex()` slices UTF-8 strings at byte offsets and can panic on non-ASCII input. Validate exact ASCII length first.
- BLE server writes ignore `preparedWrite` and `offset`; reject unsupported prepared/offset writes instead of processing them as immediate complete frames.
- Reassembly’s caller supplies wall-clock time despite its documented monotonic-time requirement.
- Expiry occurs only when another frame arrives; duplicate fragments refresh the timeout.
- Global reassembly eviction can displace another peer’s entry when the 32-entry table is full. The unconditional “noisy peers cannot evict quiet peers” claim is too strong.

No SQL, shell-execution, or user-controlled HTML rendering path is present in the supplied application code. The website’s `innerHTML` operation duplicates static markup; it is not, by itself, a demonstrated injection vulnerability.

### Secrets and erasure

**Files:** `app/security/KeystoreVault.kt`; `crates/meshline-core/src/lib.rs`; `crates/meshline-core/src/crypto/group.rs`; `crates/meshline-ffi/src/lib.rs`.

- Keystore use does **not** establish hardware backing on every device. The code neither checks `KeyInfo` security level nor enforces hardware availability.
- The raw vault secret exists in Kotlin memory and crosses JNI. Only its wrapping key is non-exportable.
- Serialized plaintext vaults and decrypted invite byte buffers are not zeroized.
- JNI creates additional unzeroized identity and vault-key copies.
- `Group` and `GroupInvite` derive `Debug`, which includes secret key fields.
- Kotlin identity arrays are cleared only on successful initialization paths.
- Unreadable identity storage silently creates a new identity; the user is not told.
- Secure state is not bound to the local identity. A surviving vault can be restored after identity replacement, leaving inconsistent group membership and session ownership.

Use redacted debug implementations, `Zeroizing` buffers, `finally` cleanup, explicit storage error types, and identity-bound vault metadata. Do not promise complete RAM erasure on the JVM.

### Privacy claims need correction

**Files:** `PRIVACY.md`; `site/privacy.html`; `site/index.html`; `RELEASE.md`.

Current contradictions include:

- Group keys **do leave the device**, encrypted inside pairwise invites.
- Received pins and their coordinates are relayed onward.
- Queued location-bearing messages may transmit while the app is backgrounded.
- Location flows are composition-scoped, not explicitly lifecycle-scoped.
- Panic wipe retains identity and pins; website copy says it removes “all of it.”
- “Nothing unsigned reaches a parser” is false: framing and packet decoding precede signature verification.
- A serverless app still processes and transmits personal data. Play Data Safety answers require a current policy assessment, not the shortcut “no servers, therefore not applicable.”

No actual private signing keys, passwords, or API credentials were found in the supplied files. `site/DEPLOY.md`, `.claude/settings.local.json`, and `target/` expose operational/local-environment details, not authentication secrets. Exclude generated and machine-local files from distribution and source-review exports.

### Dependency and release risk

No complete lockfiles or Android dependency graph were supplied, so there is no basis to claim either a clean dependency audit or a specific vulnerable shipped version.

Require:

- Locked Rust and Gradle dependencies; pinned build tools.
- `cargo audit`, `cargo deny`, Android dependency scanning, and an SBOM.
- Clean release builds with artifact hashes and source revision.
- JNI ABI checks and actual minified-release instrumentation.
- Android native **16 KB page-size compatibility** verification.
- An independent protocol and persistence-security audit.

`RELEASE.md` should also distinguish a recoverable Play **upload key** from the app-signing key; losing an upload key under Play App Signing is not necessarily permanent loss of update capability.

## 4. Architecture concerns

### The main missing abstraction is custody

Introduce a durable `PacketCustodyRepository` with:

- Canonical packet ID and immutable bytes.
- Origin, priority, expiry, and creation time.
- Per-neighbor attempts and custody receipts.
- Retry deadline and backoff.
- Application receipt state.
- Explicit eviction reason.

BLE should borrow work from this repository—not own the only surviving copy.

### State is duplicated without transactional ownership

Rust owns cryptographic state; Kotlin owns message state, UI state, and outbox state. There is no transaction spanning “advance nonce, persist state, retain packet, expose packet to transport.”

A single serialized coordinator should own protocol commands and durable commit ordering. Separate repositories can still exist, but their consistency contract must be explicit.

### JNI serializes everything behind one global lock

`NODE_INSTANCE` covers signing, PoW, decryption, vault export, safety-number derivation, and state queries. Expensive synchronous work can block BLE callbacks and UI calls simultaneously.

Move protocol work off the main thread, return typed asynchronous outcomes, and bound command queues. Keep the concurrency model simple before adding finer-grained locks.

### Error information is discarded

Rust has useful error types, but JNI collapses almost everything into null/false. Kotlin consequently reports “no session” for failures that may actually be oversized input, poisoned locks, or storage faults.

Expose a versioned result schema with stable error codes. Also distinguish:

- Authenticated packet.
- Successfully decrypted message.
- Applied group update.
- Relay decision.
- Duplicate.
- Recoverable protocol failure.

### The battery feature drops traffic rather than duty-cycling radios

`routing/flood.rs` forwards ordinary traffic with probability **0.85** normally and **0.30** below 20% battery. Android still continuously requests low-latency scanning and advertising at high transmit power.

A seven-relay single-path chat has approximately `0.85⁷ ≈ 32%` forwarding probability before radio loss; at `0.30⁷`, about **0.022%**. Permanently marking dampened packets seen prevents a later duplicate from getting another forwarding chance.

Implement actual measured scan scheduling and reliable control-traffic priority. Preserve scarce battery without silently making sessions unusable.

### Scaling limits are undefined

Examples of six-month problems:

- Whole-history rewrites and full-list sorting on each update.
- Linear group/trust lookup.
- `by_tag()` recomputes HKDF-derived tags across every group per group packet.
- No global group/session quota despite a **1 MiB** vault ceiling.
- No connection cap, retained GATT-client registry, or complete shutdown accounting.
- Hex-encoded JNI packet bodies double binary size before JSON/JVM overhead.

Start with explicit configurable budgets: for example **4 concurrent GATT clients**, **1,000 outbox packets or 8 MiB**, **2,000 history messages**, and **256 retained contacts**. These are initial engineering budgets to test, not proven capacity.

### Protocol evolution is fragile

Bincode serializes enum declaration order, not the `repr(u8)` wire values assumed by a casual reader. Field changes can break both packet and vault compatibility.

Publish a wire specification, golden vectors, capability negotiation, and migration policy. Distinguish framing version, packet version, session format, group epoch, and storage schema version.

### The simulator is not a transport simulator

`crates/meshline-sim/src/main.rs` models perfect adjacency edges, copies packets to every neighbor, and uses global knowledge of already reached nodes. Its “5 km LoRa hardware bridge” is just another graph edge; it exercises neither LoRa framing nor hardware.

Rename the scenario and add a scheduler that models actual custody, connection limits, latency, packet loss, retries, partitions, and radio airtime.

## 5. Testing gaps

The existing unit tests cover useful primitives, but disproportionately exercise successful round trips. Highest priorities:

| Priority | Test | Required assertion |
|---|---|---|
| P0 | Kill process between encryption, persistence, and transmission | No repeated pairwise key/nonce |
| P0 | Missing SOS location through Compose → JNI → wire | `location == None`; never fabricated `(0,0)` |
| P0 | Wipe concurrent with save, receive, and BLE write | No old history resurrection; no post-wipe in-flight sends |
| P0 | Three-node branching transport | Both branches receive; no destructive first-peer drain |
| P0 | Failure of every fragment position | Current packet remains in custody |
| P0 | ACK end-to-end | Correct message and recipient; no ACK on failed decrypt |
| P0 | Incremental group addition | All members can read all authorized senders |
| P0 | Pin replacement under a different creator | Replacement rejected |
| P1 | Replay flood against one valid sender | New legitimate SOS remains admissible under policy |
| P1 | Bluetooth off/on, denied/regranted, service restart | Recovery and UI health agree |
| P1 | Lost/duplicate/simultaneous handshakes | Eventual recovery without false session readiness |
| P1 | Restart while offline | Outbox and unexpired pins survive |
| P1 | Parser fuzzing and signed hostile payloads | Bounded allocations and work; no panics |
| P1 | 200% font scale, TalkBack, landscape | All primary actions reachable and named |
| P1 | Website without JS and with reduced motion | Core content and final numeric values remain visible |

Run a physical matrix of at least **12 phones across four OEM families**, including API 26/30, 31/32, and 33–36. Add three-, five-, and ten-device chains, an eight-hour screen-off soak, airplane mode with Bluetooth enabled, and controlled interference.

`RELEASE.md` explicitly says the two-device real-radio test is still outstanding. That is the minimum starting point, not the final release gate.

---

# PART B — Product, UX and future

## 6. Product assessment

### Who it serves

The strongest initial audience is **pre-organized, geographically concentrated groups**: campus safety volunteers, shelter teams, event stewards, and neighborhood preparedness groups. They can install in advance, verify identities, run drills, and supply enough local density for BLE to matter.

It is a poor initial fit for:

- A lone hiker expecting remote rescue.
- A citywide emergency service expecting guaranteed coverage.
- High-risk journalists or activists relying on anonymity and seizure resistance.
- Mixed-platform groups expecting equivalent Android/iOS background behavior.

### Real differentiator

The useful proposition is not “encrypted Bluetooth chat.” It is:

> **A local incident-and-resource board that works without internet, with attributable reports and explicit uncertainty.**

Signed resource reports, structured distress requests, aging information, and responder coordination can distinguish MeshLine. That differentiation is only valuable when users can separate **signed**, **person verified**, **recently observed**, and **confirmed available**.

### Honest maturity

**Engineering alpha with a polished presentation—not field-ready.** The UI and website communicate substantially more certainty than the transport, persistence, and protocol integration justify.

The immediate product job is to make a ten-phone drill dependable and understandable. More transports, ML, and visual effects should wait.

## 7. UX problems today

| Screen/component | Concrete friction |
|---|---|
| **SOS — `SosScreen.kt` / `Beacon`** | After queueing, the button says “SENDING” indefinitely, yet stays tappable and can create duplicates. No active incident, expiry, resolution, or resend history exists. |
| **SOS disclosure** | The public-location warning is below the large beacon and readiness card. Users can act before seeing the relevant privacy consequence. |
| **Chat — `ChatScreen.kt`** | Opens to an editable Public composer whose Send button can never work. It looks broken rather than intentionally read-only. |
| **Chat channel strip** | Public, groups, transient peers, and “New group” occupy one horizontal strip. No unread counts, search, stable contacts, or conversation previews. |
| **New group** | Creation closes the dialog but neither selects the group nor opens member selection. Managing it requires an undisclosed long press. |
| **Verify peer — `SecurityDialogs.kt`** | Sixty digits with no QR flow, local contact name, or clear mismatch action. The copy says a mismatch may mean “someone is relaying”; ordinary relaying is the product’s intended behavior. Say “wrong identity or interception.” |
| **Group management** | “Leave group” is the dialog’s primary confirmation action; removal and rotation lack strong consequence previews. Failures can still produce success notices. |
| **Pins — `MapScreen.kt`** | Missing location always says “Waiting for a position fix,” even when permission is denied. All pins last 24 hours. No visible age, expiry, detail, update, or withdrawal. |
| **Pin bearing** | A tiny north tick is not an explicit “north-up, not phone-relative” explanation. An arrow may be mistaken for a compass instruction. |
| **Radar — `RadarScreen.kt`** | A 250 dp decorative radar dominates useful diagnostics; position is arbitrary and signal is currently fabricated. “Who is carrying for you” is not backed by custody receipts. |
| **Shell — `MainActivity.kt` / `BrandBar`** | “Searching” can mean Bluetooth off, scan failure, stopped relay, or genuinely no neighbors. Tapping it does not open diagnostics. |
| **PermissionGate** | Explains required permissions but requests optional location/notifications in the same batch. Optional grants have no contextual retry workflow on their relevant screens. |
| **Theme/components** | Many labels are 9–11.5 sp. Custom controls lack explicit button/tab roles and selected semantics. Fixed heights and large decorations constrain font scaling and landscape. |
| **Website — `site/index.html`** | Four scroll-driven sections total 1,290 vh before other content. Without JavaScript, important hero content remains hidden and counters show zero. Illustrative traffic is not labeled as illustration. |

There is also **no incoming-SOS alert pipeline** in the supplied code: the foreground service uses a low-importance status channel, but does not notify on new distress messages. A responder should not have to stare at the Public chat feed.

## 8. Information architecture

Keep four primary tabs, but change their jobs:

| Screen | Job | Primary action |
|---|---|---|
| **Help** | Compose an SOS and see your active incident | **Send public SOS** |
| **Nearby** | Browse received distress reports and resources | **Open report** |
| **Messages** | Stable direct and group conversations, including offline contacts | **New message** |
| **Mesh** | Explain actual link health, battery policy, and custody | **Start / repair relay** |

Secondary screens:

| Screen | Job | Primary action |
|---|---|---|
| **Readiness check** | Explain limits, obtain required permissions, test a link | **Test with another phone** |
| **SOS review** | Show exact audience, text, and location to be shared | **Broadcast now** |
| **Active SOS** | Show queue/handoff/response timeline and current need | **Update status** |
| **SOS detail** | Present origin, age, location uncertainty, and responses | **Offer help** |
| **Resource detail** | Show evidence, observation time, expiry, and updates | **Confirm / report change** |
| **Add resource** | Select type, observation, lifetime, and location | **Share public report** |
| **Conversation** | Private communication with a fixed destination | **Send** |
| **Contact & verification** | Local name, identity, verification history, QR comparison | **Verify identity** |
| **Group details** | Membership, pending invites, and epoch distribution | **Add member** |
| **Delivery detail** | Explain the evidence behind one message’s state | **Retry if eligible** |
| **Privacy & storage** | Retention, identity lifecycle, and deletion choices | **Delete selected data** |
| **Emergency wipe** | Fast, explicit, coordinated deletion | **Wipe now** |
| **About & limits** | Version, protocol compatibility, audit, and field evidence | **Run readiness check** |

Keep an emergency-wipe shortcut accessible from Mesh and the persistent overflow menu. Do not hide basic app limits behind a tappable logo alone.

## 9. UI/UX improvements

### Onboarding: prepare, do not merely request permissions

A three-step flow:

1. **Understand:** “Works only with nearby MeshLine phones. Does not contact emergency services.”
2. **Enable:** nearby-device permissions; contextual location and notification choices.
3. **Test:** exchange a non-emergency test message with another phone.

Target **under two minutes** for a pair of users to complete a successful drill. Mark practice traffic distinctly on the wire and in the UI; never seed fake neighbors into live screens.

Permit read-only access to stored messages and help content when radio permissions are missing.

### Core SOS workflow

- Place “**Public: nearby listeners can read this text and location**” directly above Send.
- Keep quick phrases, but add structured optional fields: people affected, immediate hazard, mobility, landmark.
- Offer “Share location” explicitly; default appropriately for the chosen public-help workflow.
- Show “Position from 35 seconds ago, estimated ±42 m,” not unexplained coordinates.
- Use separate states: **Preparing**, **Saved on this phone**, **Handed to one relay**, **Response received**, **Expired**, **Resolved**.
- Never label a relay receipt as “help is coming.”
- Offer resolution/update messages linked to the same incident.
- Show existing emergency-call options without suggesting MeshLine has contacted them.

### Empty, loading, and error states

| State | Suggested copy/action |
|---|---|
| Bluetooth off | “Relay paused: Bluetooth is off.” → **Turn on Bluetooth** |
| No neighbors | “Scanning. No direct links found yet.” → **Run a two-phone test** |
| No location permission | “Your SOS can still send without coordinates.” → **Add location** |
| GPS timeout after 30 seconds | “No recent fix. Add a landmark or send without location.” |
| Handshake pending | “Connecting securely, attempt 1 of 3.” → **Cancel** |
| Storage failure | “Not safely saved. This message may be lost if the app closes.” |
| Rekey pending | “New key confirmed by 3 of 5 remaining devices.” |
| No resources | “No unexpired reports received. This does not mean no resources exist.” |

Retain underlying drafts through errors and navigation.

### Data provenance

Every report should expose:

- Creator identity and locally assigned name.
- Identity-verification status.
- Observation time, received time, and expiry.
- Original report versus amendment.
- Location accuracy and source.
- Corroboration or conflict, without treating votes as proof.
- A clear distinction between direct observation and forwarded information.

A cryptographic signature should read **“Signature valid”**, not **“Resource verified.”**

### Progressive disclosure

Default to plain language:

- “Encrypted; person not verified.”
- “Waiting for a relay.”
- “Resource report is 18 minutes old.”

Put Noise suite names, packet IDs, TTL, group epochs, and raw RSSI in detail screens.

### Accessibility and battery

- Minimum **48 × 48 dp** interactive targets; **56 dp** for primary field actions.
- Body text **16 sp**, supporting text **14 sp**, critical status labels at least **12 sp**.
- Support 200% font scaling, TalkBack, switch access, and landscape.
- Explicit roles, selected states, meaningful live-region announcements, and non-color status cues.
- Static backgrounds by default; no perpetual animation in battery saver.
- Announce grouped safety-number digits without forcing users through decorative elements.

## 10. Visual design direction

### Direction: calm field instrument

Retain the near-black/cyan identity, but remove the “sci-fi radar” implication. Use solid surfaces, clear typography, and restrained semantic color. Red belongs to distress and destructive actions—not general branding everywhere.

### Palette

| Semantic role | Light | Dark |
|---|---|---|
| Page background | `#F4F7FA` | `#070A11` |
| Primary surface | `#FFFFFF` | `#121826` |
| Raised surface | `#EAF0F6` | `#19212F` |
| Primary text | `#152235` | `#EDF2F9` |
| Secondary text | `#46566B` | `#B3BED0` |
| Supporting text | `#596A80` | `#94A3B8` |
| Decorative border | `#D5DEE8` | `#334155` |
| Control boundary | `#64748B` | `#64748B` |
| Primary action / links | `#00677F` | `#3FE0FF` |
| Confirmed receipt / verified identity | `#146C43` | `#3DDC97` |
| Warning / pending verification | `#805000` | `#FFB443` |
| Error text / destructive outline | `#B42318` | `#FF8A80` |
| SOS filled button | `#B42318` | `#B42318` |
| Text on SOS button | `#FFFFFF` | `#FFFFFF` |
| Advanced security accent | `#6043A8` | `#B5A7FF` |

Use dark text `#07121C` on bright cyan, green, and amber fills. Validate every actual composited state against **4.5:1** normal-text contrast and **3:1** large-text/control requirements.

The existing white-on-`#FF4438` combination is only roughly **3.4:1**; it is inappropriate for small button captions.

### Type scale

Use platform Roboto/Noto sans families, with bundled script coverage where required. Monospace is for IDs and comparison codes, not all metadata.

| Role | Size / line height | Weight |
|---|---|---|
| Main screen title | 28 / 34 sp | 700 |
| Section heading | 22 / 28 sp | 600 |
| Card title | 18 / 24 sp | 600 |
| Body | 16 / 24 sp | 400 |
| Supporting text | 14 / 20 sp | 400 |
| Caption | 12 / 16 sp | 500 |
| Primary button | 16 / 20 sp | 600 |
| Safety number | 20 / 30 sp | 500, monospace |

Remove 1.6 sp tracking from tiny labels and avoid all-uppercase instructional text.

### Spacing and components

- **4 dp** base grid.
- Spacing tokens: **4, 8, 12, 16, 24, 32, 48 dp**.
- Screen margins: **16 dp** compact, **24 dp** larger devices.
- Cards: **12 dp** radius, **16 dp** padding, solid fill, optional 1 dp border.
- Buttons: minimum **56 dp** height, **12 dp** radius, visible pressed/focus states.
- Fields: persistent labels; error text below; no placeholder-only identification.
- Bottom navigation: standard Material navigation semantics, **12 sp** labels.
- Chat bubbles: maximum **82%** width rather than a hard 288 dp ceiling.
- Motion: **120–180 ms** transitions; no animation that implies radio activity without telemetry.
- Light mode: first-class for sunlight readability, with a manual and system-following toggle.

## 11. Future roadmap

All numbers below are **proposed acceptance targets**, not current measured capabilities.

### Next 3 months — earn an alpha pilot

**Weeks 1–4: safety and protocol repair**

- Fix nonce durability, optional location, wipe races, ACK semantics, group convergence, and pin ownership.
- Add durable outbox and stable conversations.
- Introduce protocol result/error types and explicit transport health.
- Correct security, privacy, and marketing claims.
- Begin independent security review.

**Weeks 5–8: transport reliability**

- Per-peer custody/fan-out scheduler.
- Signed discovery exchange.
- GATT operation deadlines, retries, lifecycle recovery, and connection caps.
- Priority for SOS and protocol-control traffic.
- Physical test matrix and deterministic failure simulation.

**Weeks 9–12: product pilot**

- Readiness drill, structured SOS details, received-SOS alerts.
- Honest receipt timeline and group-update status.
- Accessible type scale, light mode, resource provenance.
- Two supervised pilots of **20–30 participants**.

**Pilot entry gates:**

- Zero repeated pairwise key/nonces across **10,000 injected crash schedules**.
- Zero false delivered/wipe-success states in automated fault tests.
- At least **95% one-hop test-message receipt within 30 seconds** under declared lab conditions.
- At least **90% three-hop receipt within 120 seconds** in the defined field setup.
- Eight-hour screen-off soak with bounded memory and published battery measurements.

Failure of those targets means further engineering—not revised marketing.

### 6–12 months — deployable local coordination

- Offline vector maps using legitimate OSM-derived region packages, with attribution, dates, and storage estimates.
- Resource amendments, withdrawals, configurable expiry, and conflict display.
- Organization-issued responder credentials verifiable offline.
- Local coordinator console on Android tablets and Linux.
- GeoJSON import/export; QGIS interoperability; carefully scoped CAP alert ingestion.
- Authenticated Wi-Fi/LAN transport for larger payloads, without reintroducing an open unauthenticated socket.
- Meshtastic integration tested against real firmware and radio airtime limits. The existing wrapper is not interoperability.
- iOS feasibility work with explicit foreground/background capability labeling.
- English plus **two pilot-region languages**, selected through actual deployment needs.
- Signed offline update packages and a maintained protocol compatibility matrix.

### Ambitious long term

- Solar-powered fixed relay nodes for shelters and campuses.
- Multi-transport delay-tolerant routing across BLE, local Wi-Fi, and constrained radios.
- Optional organization-owned gateways, with explicit consent and destination disclosure when data leaves the local mesh.
- Audited group protocol evolution suitable for intermittent connectivity.
- Federation between relief organizations without requiring a central MeshLine account.
- Pre-disaster readiness kits: maps, verified contacts, drills, and local resource directories.

### ML opportunities—later, and subordinate to evidence

Potentially useful:

- On-device translation with original text always available.
- Offline speech-to-text for accessibility, reviewed before sending.
- Suggested incident categories and duplicate-report clustering.
- Link-quality or scan-scheduling prediction, compared against simple deterministic baselines.

Do **not** use ML to invent shelter availability, guarantee route safety, label people trustworthy, or automatically suppress distress traffic. No cloud inference by default. Benchmark model energy and memory costs before adding it to a battery-constrained relay.

## 12. Productisation

### Who buys it

Sell first to organizations able to create local deployment density:

1. Campus safety and facilities teams.
2. Event organizers and venue operators.
3. NGOs operating shelters and local response teams.
4. Industrial or remote-site operators needing supplemental local text coordination.

The buyer purchases **preparedness, deployment, drills, support, and interoperability**. Survivors should not purchase the right to send an SOS.

Avoid starting with citywide government procurement or high-risk activist positioning; both require stronger evidence and substantially longer trust-building.

### Pricing options to validate

Illustrative USD hypotheses:

| Offer | Price hypothesis | Includes |
|---|---:|---|
| Community app | Free | SOS, private text, resources, relaying, verification |
| Supported pilot | **$2,500–$7,500** | Site assessment, 30–100 devices, training, one drill, report |
| Team preparedness | **$1,500–$5,000/year** | Up to 100 managed devices, offline configuration, updates, scheduled support |
| Venue / multi-site | **$10,000–$30,000/year** | Deployment tooling, integrations, training, agreed support coverage |
| Relay hardware | Cost-plus after validation | Hardware, provisioning, installation, maintenance |

Do not require online license validation during an incident. Price support availability only when staffing can deliver it. No ads, location resale, or analytics monetization.

### Competitive landscape

- **Briar:** a benchmark for security-focused offline communication and contact verification. MeshLine must differentiate on local incident/resource workflows, not assert superior security.
- **Bridgefy and other phone-only mesh messengers:** benchmarks for discovery, deployment ease, SDK integration, and mixed-device behavior. Historical vulnerability reports are not substitutes for evaluating current versions.
- **Meshtastic:** a hardware-and-LoRa ecosystem with a different range/density trade-off. It is both a competitor and a plausible integration.
- **ATAK and field-coordination systems:** relevant for professional mapping, interoperability, and operational workflows; materially more complex than the intended consumer experience.
- **Satellite messengers and conventional radios:** alternatives when assured reach beyond nearby phones matters. MeshLine should complement them, not imply equivalence.
- **Newer BLE messengers such as bitchat:** include in a current product and protocol assessment rather than relying on the older plan’s competitor table.

The plan’s claim that competitors have no GIS/resource capability is too broad and requires current validation.

### What must be true before it sells

1. **Independent security assessment**, with critical/high findings resolved and a public summary.
2. **Measured field evidence**, including failures, OEM compatibility, latency distributions, battery use, and deployment density.
3. **Truthful state semantics:** queued, handed off, received, responded to, verified, and wiped each have a defined evidence source.
4. **Repeatable deployment:** signed distribution, offline installation guidance, drills, and recovery documentation.
5. **Data-governance clarity:** retention, group-key sharing, public-location exposure, organization credentials, and optional gateway behavior.
6. **Support and incident response:** security contact, vulnerability disclosure, update mechanism, and named maintenance ownership.
7. **Licensing clarity:** no project license was supplied; source availability alone does not establish open-source rights.
8. **A demonstrated buyer outcome:** for example, a 50-person campus team completes an outage drill and identifies resources faster than its existing fallback procedure.

**Bottom line:** MeshLine has a credible product direction and worthwhile technical foundations. The next investment should make its claims true across crashes, lossy radios, group changes, and distressed human use—not add more claims.