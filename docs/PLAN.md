# MeshLine — plan

> **Contract file.** A short index for the agents. The long-form documents it points to are the user's
> (`offline-mesh-comms-plan.md`, `REVIEW.md`); agents read them but never edit them.

## 1. Sources
- **Product vision and phases:** [`offline-mesh-comms-plan.md`](../offline-mesh-comms-plan.md) (§1 vision, §3 phased
  features, §4 architecture).
- **Engineering review and product direction:** [`REVIEW.md`](../REVIEW.md). Part A §2 lists the correctness bugs
  by severity (C1–C3 release blockers, H1–H14 high, M1–M6 medium); §3–§5 security, architecture and testing gaps.
  Part B §6–§12 is the product/UX direction.
- **Release process:** [`RELEASE.md`](../RELEASE.md) (build, pre-upload checks, two-device test, Play Console).
- **Rules for the agents:** [`AGENTS.md`](../AGENTS.md). Task list: `tasks/` (`python tools/board.py`).

## 2. Milestones

| Milestone | Goal | Exit criteria |
|---|---|---|
| **M0 — Kickoff** | `main` carries the current code; both agents can work unattended | Harden branch merged to `main` (X-001); CI green and fully blocking (A-001); native build + JNI check in CI (B-001) |
| **M1 — Release blockers** | Fix REVIEW.md C1–C3 and H1–H14 so the Play release is safe to ship | Every C/H task done with tests; two-device mesh test passed (X-101); crypto design reviewed (X-102) |
| **M2 — Field-ready polish** | REVIEW.md M1–M6 and the Part B UX work (onboarding, honest states, SOS flow) | M tasks done; Part B §9 items scheduled as tasks and the first ones shipped |
| **M3 — Phase 2 features** | `offline-mesh-comms-plan.md` §3 Phase 2 (Wi-Fi Direct payloads, delivery ACKs end to end, offline map) | To be broken into tasks when M2 is near done |

## 3. Lane split
Lane A (Claude): Rust core, JNI on both sides, Kotlin key/at-rest crypto. Lane B (Gemini): the Android app above
the bridge, the site and release docs. User (X): merges, devices, Play Console, server. Exact paths: `OWNERS.toml`.

Most REVIEW.md bugs cross the boundary. The convention: the Lane A task delivers the core and bridge half with tests,
and the Lane B task that wires it into the app lists the Lane A task in `needs`.
