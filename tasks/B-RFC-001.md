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
  "hops_on_arrival": 1,
  "is_duplicate": false,
  "wire_ttl": 8
}
```
Or at minimum `hops_on_arrival: u8` (the number of hops the packet traversed to reach this node, calculated on wire TTL before decrement, e.g. `DEFAULT_TTL - wire_ttl`), and `is_duplicate: bool`.
Having `hops_on_arrival` directly computed in the core prevents Android from hard-coding `DEFAULT_TTL = 8`.

## Meanwhile
In `StoreAndForwardManager.kt`:
Check `obj.optInt("hops_on_arrival", -1)`. If present (>= 0), use it directly.
If absent:
- If `obj.optBoolean("is_duplicate", false)` is true, do not treat as direct (hops >= 2, reachability = RELAYED).
- Otherwise fallback to `(8 - ttl).coerceAtLeast(1)`.

## Resolution
<!-- Requester fills in: link to the other lane's PR / handoff entry, then set status: done -->
