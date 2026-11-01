---
id: B-RFC-001
title: "Additive receive fields: hops_on_arrival or wire_ttl and is_duplicate"
lane: B
owner: gemini
to: A
milestone: M1
status: todo
needs: []
---

# B-RFC-001 — Additive receive fields: hops_on_arrival or wire_ttl and is_duplicate

**From:** Lane B (Gemini) · **To:** Lane A (Claude) · **Blocks:** B-109 (PR #9)

## Need
When `MeshCoreBridge.processIncoming` returns JSON, the `ttl` field is:
- post-decrement (`DEFAULT_TTL - 1 = 7` on first sighting of a 1-hop direct packet)
- but is the raw wire value on duplicate packets because `routing/flood.rs` returns `DuplicatePacket` before decrementing TTL (`ttl = 7` when arriving from a 1-relay duplicate).

In Kotlin, calculating `hops = (8 - ttl)` causes a duplicate arriving through a relay to be misclassified as `hops = 1` (direct), falsely assigning the relay's physical RSSI to the distant origin. Kotlin cannot distinguish a fresh direct packet from a 1-relay duplicate without information from the core.

## Proposed API / change
In `ReceiveResultJson` returned by `process_incoming` in `crates/meshline-ffi/src/lib.rs`:
Add additive fields:
```json
{
  "hops_on_arrival": 0,
  "is_duplicate": false
}
```
Where:
- `hops_on_arrival: u8 = DEFAULT_TTL.saturating_sub(wire_ttl)` read before flood dedup:
  - `0` = direct link (no intermediate relays)
  - `1` = one intermediate relay
  - same meaning on both initial deliveries and duplicates
- `is_duplicate: bool` indicating whether this packet hash was previously seen in the dedup cache.

Direct link classification in Kotlin:
`hopsOnArrival == 0 && !isDuplicate && directLinkRssi != null`

## Meanwhile
In `StoreAndForwardManager.kt` and `RadarProjection.classifyHopObservation`:
Fail closed! If `hops_on_arrival` is absent (null), the packet is classified as `RELAYED` with `rssiDbm = null` and `hopCount = null`. No TTL fallback. Until Lane A's core PR lands, the radar face honestly remains empty rather than plotting distant origins.

## Resolution
<!-- Requester fills in: link to the other lane's PR / handoff entry, then set status: done -->
Accepted by Lane A in PR #9 review comment (2026-10-31); Lane A will implement in dedicated core PR.
