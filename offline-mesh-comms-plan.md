# MeshLine — Offline Disaster Communication App
## Detailed Product & Technical Plan

---

## 1. Vision & Problem Statement

**Problem**: When cell towers go down (earthquakes, floods, war, censorship shutdowns), people lose the ability to communicate at exactly the moment they need it most.

**Solution**: A phone-to-phone mesh communication app that works with zero infrastructure — no SIM, no WiFi router, no internet. Messages hop from phone to phone over Bluetooth/WiFi Direct until they reach their destination or a relay point with connectivity.

**Positioning**: Phone-only (no extra hardware required), open protocol, dead-simple SOS UX, built with real cryptographic rigor. Useful daily (festivals, hikes, subways) so it's already installed when disaster hits — not a "break glass in emergency" app nobody has.

---

## 2. Target Users & Use Cases

| Segment | Use case |
|---|---|
| Disaster survivors | Coordinate with family/neighbors, broadcast SOS + location |
| First responders / NGOs | Field coordination without relying on damaged infra |
| Protesters / journalists in unstable regions | Censorship-resistant communication |
| Everyday users (adoption driver) | Offline chat at festivals, treks, subways, flights |
| Civil defense / govt agencies | Complement to cell-broadcast alert systems |

---

## 3. Core Features (Phased)

### Phase 1 — MVP (Months 1–3)
- 1:1 and group chat over BLE mesh (text only)
- Flood routing with TTL + dedup cache
- SOS broadcast mode (unencrypted, GPS-tagged, one-tap)
- Basic E2E encryption for regular chat (Signal protocol reference)
- Android only

### Phase 2 — Field-ready (Months 4–6)
- WiFi Direct for larger payloads (images, voice notes)
- Store-and-forward (message waits on relay devices until a path exists)
- Offline maps with resource pins (shelters, water, medical points)
- Battery-aware relay throttling (adaptive duty cycling)
- Delivery ACKs

### Phase 3 — Scale & resilience (Months 7–12)
- LoRa hardware bridge (Meshtastic-compatible) for multi-km range
- iOS support (foreground-limited, documented workaround via companion BLE beacon mode)
- Mesh network visualization (who's reachable, hop count)
- Multi-language + low-literacy UI (icon-driven SOS)
- Integration APIs for NGOs/civil defense dashboards

---

## 4. Technical Architecture

### Transport Layer
- **BLE (Bluetooth Low Energy)**: discovery + low-bandwidth text messages. ~100–300m per hop outdoors.
- **WiFi Direct**: larger payloads (images, location bundles) when in range.
- **LoRa (Phase 3)**: optional hardware bridge for km-range relay in low-density areas.

### Routing
- **MVP**: epidemic/flood routing — every node forwards to every neighbor until TTL = 0 or delivery confirmed. Simple, robust, battery-expensive.
- **Later**: store-and-forward with proximity/mobility heuristics to cut redundant relays.

### Packet Schema
```json
{
  "msg_id": "uuid",
  "sender_id": "pubkey_hash",
  "type": "chat | sos | ack | resource_pin",
  "ttl": 8,
  "timestamp": 1234567890,
  "payload": "encrypted_blob",
  "location": {"lat": 0.0, "lon": 0.0, "accuracy_m": 0},
  "signature": "..."
}
```
- `msg_id` + local "seen" cache on every device prevents duplicate-relay storms.
- TTL default ~8 hops; tune via field testing.
- SOS packets are unencrypted by design — any nearby stranger's phone can relay without a shared key.
- ACKs are optional but give sender meaningful signal ("reached at least 1 node" vs "still stranded").

### Encryption
- E2E per-conversation keys, Signal protocol as architectural reference.
- Non-negotiable for chat — traffic passes through strangers' devices.
- SOS channel deliberately plaintext for reach; user is warned this is public.

### Platform Constraints
- **Android**: full background BLE mesh relay support — build here first.
- **iOS**: aggressive background BLE restrictions cripple relay unless app is foregrounded. Plan for this asymmetry explicitly rather than treating it as a bug to fix later.

### Battery Management
- Relaying drains battery fast — critical failure mode in real disaster use (people need phones to last days, not hours).
- Adaptive duty-cycle scanning (increase relay activity only when charging or battery > threshold).
- "Relay-only when charging" toggle for shared/community devices (e.g., a phone left plugged in at a shelter acting as a fixed relay node).

---

## 5. GIS/Resource Layer (differentiator)

Given a geospatial engineering background, this is the natural wedge against chat-only competitors (Bridgefy, Briar, FireChat):
- Offline vector maps (OpenStreetMap extracts, pre-downloaded by region)
- Resource pins: shelters, water points, medical stations, blocked roads — sync across the mesh opportunistically
- Infrastructure resilience overlay: pre-loaded data on bridge/road conditions, flood-prone zones, cell tower locations — useful before AND during a disaster
- Pins propagate like messages: signed, timestamped, TTL-based, so stale/false info ages out

---

## 6. Competitive Landscape

| App | Tech | Weakness / gap |
|---|---|---|
| Bridgefy | BLE mesh, proprietary | 2021 crypto vulnerability exposed during protest use; closed-source trust issues |
| Briar | Bluetooth/WiFi + Tor when online | Strong security, clunky UX, Android-only |
| FireChat | BLE mesh | Effectively abandoned |
| Meshtastic | LoRa + companion app | Requires extra hardware — high adoption friction, best range |

**Gap**: no pure-software, open-protocol, phone-only mesh app with both rigorous encryption AND a genuinely simple SOS UX plus a GIS/resource layer.

---

## 7. Adoption Strategy (the real hard problem)

The tech is solvable — the bottleneck is having the app on enough phones *before* disaster strikes.

1. **Non-emergency daily utility**: offline chat at festivals, treks, subways, flights — so installs happen organically, not just during crisis prep.
2. **NGO/disaster-prep partnerships**: Red Cross, civil defense, local NGOs — bundle into prep kits/checklists instead of trying to go viral cold.
3. **Government/telecom integration**: pitch as a complement to cell-broadcast emergency alert systems where mandated.
4. **Campus/dense-population pilots**: university rollouts as a real-world mesh testbed before wider launch.
5. **Regional pre-positioning**: target known-risk areas (earthquake zones, hurricane corridors, unstable regions) rather than a generic global launch — sharper message-market fit.

---

## 8. Risks & Open Problems

- **Cold-start network effect**: useless below a critical mass of nearby users — the core go-to-market risk.
- **Battery drain**: constant BLE scanning/relaying is the top real-world complaint category for this app class.
- **Spam/abuse**: no central moderation on an open mesh — needs rate-limiting and reputation signals at the protocol level.
- **Platform asymmetry**: iOS background restrictions limit relay reliability; plan messaging and UX around this rather than overpromising.
- **False resource pins**: mesh-propagated shelter/water pin data needs signature + staleness handling to avoid bad info spreading in a crisis.

---

## 9. Suggested Team & Timeline (rough)

| Role | Phase 1 | Phase 2 | Phase 3 |
|---|---|---|---|
| Mobile (Android) | 1–2 devs | 1–2 devs | 1 dev |
| Backend/protocol | 1 dev | 1 dev | 1 dev |
| GIS/mapping | — | 1 dev | 1 dev |
| iOS | — | — | 1 dev |
| Security review | consult | consult | full audit before public launch |

Timeline: ~3 months MVP → ~6 months field-ready → ~12 months scaled version with hardware bridge + iOS.

---

## 10. Next Steps

1. Build Android MVP: BLE mesh chat + SOS broadcast + basic E2E encryption.
2. Field-test flood routing battery drain in a real multi-device setup (not just simulation).
3. Draft partnership outreach list (local NGOs, civil defense contacts).
4. Prototype the GIS resource-pin layer as the differentiator, leveraging existing PostGIS/spatial-analysis experience.

---

## 11. Review Notes — Integration Analysis (2026-08-04)

Reviewed against this repo's existing `vela-core`/`vela-client`/`vela-server`
implementation (not just the Vela spec) to answer: reuse Vela's crypto engine,
or build MeshLine standalone?

### What's strong in this plan

- The adoption strategy (§7) is the strongest part of the doc: "useful daily
  (festivals, hikes, subways) so it's already installed when disaster hits" is
  the correct answer to the cold-start problem most disaster-comms pitches get
  wrong, and it's treated as a first-class constraint rather than an
  afterthought.
- The GIS/resource-pin layer (§5) is a genuine differentiator against
  Bridgefy/Briar/FireChat/Meshtastic, none of which have it, and it's designed
  with the right details (signed, TTL'd, ages out on staleness).
- The competitive table (§6) is accurate and specific, not hand-wavy.
- Treating iOS background BLE restriction (§4 Platform Constraints) as a
  permanent constraint to design around, rather than a bug to fix later, is
  the right call.

### Gaps to close before implementation

1. **Session establishment as described doesn't fit a serverless mesh.**
   "Signal protocol as architectural reference" (§4 Encryption) implicitly
   assumes a server hosting signed prekey bundles for async X3DH-style first
   contact. There is no server here. Two devices that have never met,
   communicating over an unknown multi-hop relay, have no equivalent of a
   bundle-fetch endpoint. Needs an interactive, mutually-authenticated
   handshake (Noise-XX-style, as bitchat uses) instead of a bundle-fetch model.

2. **MVP's routing choice fights MVP's own stated top complaint.** §3 picks
   epidemic/flood routing for Phase 1 ("simple, robust, battery-expensive"),
   while §8 Risks names battery drain as "the top real-world complaint
   category for this app class." Worth a rebroadcast-probability dampener or
   basic heuristic in Phase 1 rather than deferring all mitigation to Phase 2.

3. **Plaintext GPS-tagged SOS (§4 Packet Schema) mismatches one of the named
   use cases.** §2 lists "protesters/journalists in unstable regions" as a
   target segment. A plaintext GPS broadcast is fine for an earthquake victim
   and actively dangerous for someone evading a censorship-shutdown regime.
   Needs two SOS modes (open broadcast vs. authenticated-only-to-trusted-
   contacts), not one size fits all.

4. **Spam/abuse is listed as an open problem (§8), not designed.** On an open
   mesh where strangers relay your traffic, a single malicious node flooding
   the network directly attacks the thing already named the top complaint
   (battery). Should move from "risk" to a Phase 1 design requirement.

5. **No group/broadcast crypto exists to build on.** Confirmed in
   `crates/vela-core`: groups/MLS are explicitly unimplemented
   (`crates/vela-core/src/lib.rs:19-22`, `README.md` roadmap). There is also
   no unauthenticated/plaintext broadcast primitive anywhere in `vela-core` —
   every existing path requires a specific recipient's X25519 key
   (`crates/vela-core/src/sealed.rs`). MeshLine's SOS broadcast and group chat
   are both net-new protocol work, not adaptation of existing code.

6. **3-month Android MVP (§9) is optimistic** for 1-2 devs — BLE stack
   inconsistency across Android OEMs alone has historically eaten months in
   comparable projects (Bridgefy, Briar).

### Vela-core reuse assessment (grounded in code, not the spec doc)

An Explore pass over `crates/vela-core`, `crates/vela-client`,
`crates/vela-server`, and `crates/vela-android` found:

- **Reusable as-is:** `vela-core`'s crypto primitives — `identity.rs`
  (Ed25519/X25519 keys), `pqxdh.rs` (session establishment), `ratchet.rs`
  (Double Ratchet), `sealed.rs` (anonymous envelope seal/open). All are pure
  Rust, network-free, byte-in/byte-out
  (`crates/vela-core/src/lib.rs:7`: "no networking and no UI"). This is real,
  already-tested, non-trivial reuse — skip re-implementing and re-auditing
  Double Ratchet + PQXDH from scratch.
- **Not reusable without a rewrite:** `vela-client`. `send_payload()` performs
  a *synchronous HTTP GET* for the peer's prekey bundle
  (`crates/vela-client/src/lib.rs:363-367`) before PQXDH can even start —
  session bootstrap is hardcoded to "a server is reachable right now." There
  is no transport trait, no envelope-in/envelope-out seam anywhere in the
  public API (`send_text`, `send_media`, `receive` all reach directly into
  `self.state.server`). `vela-server`'s mailbox is a single-node SQLite queue
  with destructive drain-on-delivery
  (`crates/vela-server/src/store.rs:8-9`) — no hop/TTL/dedup state, no
  concept of a message in transit through an intermediate stranger's device.
  Retrofitting BLE mesh semantics under `vela-client` would mean
  re-architecting the exact logic MeshLine needs to replace — expensive reuse,
  arguably not worth it.
- **`vela-android`'s JNI layer** (`crates/vela-android/src/lib.rs`) is narrow
  but only as a passthrough to the same server-coupled `Client` methods — no
  transport hook exists to plug BLE into underneath it.

### Recommendation

Don't extend `vela-client`/`vela-server`, and don't re-implement Double
Ratchet/PQXDH from scratch either. Middle path: depend on `vela-core` as a
library crate for crypto primitives only, and build MeshLine's session
bootstrap (Noise-XX-style, no-server handshake), routing (TTL/flood/dedup/
store-and-forward custody), and new plaintext broadcast message type as new
crates in this workspace — e.g. `crates/meshline-core` and
`crates/meshline-android` — rather than as changes inside `vela-client`.
Whether MeshLine ships under the Vela brand or as a separate app is a
positioning decision, independent of this: the code-reuse question and the
branding question don't have to resolve the same way.
