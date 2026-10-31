package org.meshline.app.ui.radar

import org.meshline.app.db.PeerNodeEntity
import org.meshline.app.db.PeerReachability
import kotlin.math.abs

/**
 * An observation of a peer arriving from the transport or mesh core.
 */
data class PeerObservation(
    val nodeId: String,
    val transport: String,
    val rssiDbm: Int? = null,
    val reachability: PeerReachability = PeerReachability.RELAYED,
    val hopCount: Int? = null
)

/**
 * Pure projection logic for Radar visualization.
 *
 * Implements the honesty rules from REVIEW.md H13:
 * - Signal strength must be a genuine physical radio measurement, never an invented default.
 * - Unknown RSSI yields 0 signal bars and is never plotted on range rings.
 * - Relayed identities heard across the mesh lack positional evidence and are excluded from the radar face.
 * - Direct evidence expires after [DIRECT_LINK_EXPIRY_MILLIS], dropping the peer to RELAYED with null RSSI.
 */
object RadarProjection {
    const val MIN_RSSI_DBM = -95
    const val MAX_RSSI_DBM = -40

    /**
     * How long physical direct BLE link evidence remains valid without a fresh direct packet.
     * After this window, the peer drops to RELAYED with null RSSI and is no longer plotted.
     */
    const val DIRECT_LINK_EXPIRY_MILLIS = 30_000L

    /**
     * Maps measured RSSI to a normalized radius fraction between [0.18f, 0.94f].
     * -40 dBm (strong) sits near the centre at 0.18f.
     * -95 dBm (weak) sits near the outer rim at 0.94f.
     *
     * Returns null if RSSI is unknown, which signals that the peer has no positional evidence.
     */
    fun rangeFraction(rssiDbm: Int?): Float? {
        if (rssiDbm == null) return null
        val clamped = rssiDbm.coerceIn(MIN_RSSI_DBM, MAX_RSSI_DBM)
        return (0.18f + (abs(clamped) - 40) / 55f * 0.76f).coerceIn(0.18f, 0.94f)
    }

    /**
     * Determines filled signal bars (0 to 4).
     *
     * Returns 0 for null/unknown RSSI. Under no circumstances may an unknown
     * signal strength render as 4 full bars.
     */
    fun signalBarCount(rssiDbm: Int?): Int {
        if (rssiDbm == null) return 0
        return when {
            rssiDbm >= -55 -> 4
            rssiDbm >= -70 -> 3
            rssiDbm >= -82 -> 2
            else -> 1
        }
    }

    /**
     * Filters the peer list to only devices with genuine physical radio evidence
     * (direct BLE radio link with a measured RSSI).
     */
    fun plottablePeers(peers: List<PeerNodeEntity>): List<PeerNodeEntity> {
        return peers.filter { it.isDirect && it.rssiDbm != null }
    }

    data class HopClassification(
        val reachability: PeerReachability,
        val rssiDbm: Int?,
        val hopCount: Int?
    ) {
        val isDirect: Boolean get() = reachability == PeerReachability.DIRECT
    }

    /**
     * Pure classification of transport hop evidence (H13 fail-closed).
     *
     * Rules:
     * - Direct evidence requires core to explicitly confirm `hopsOnArrival == 0`,
     *   `!isDuplicate`, and a physical `directLinkRssi != null`.
     * - If `hopsOnArrival` is absent (null): fails closed to RELAYED, null RSSI, null hopCount.
     * - If duplicate or `hopsOnArrival > 0` or missing RSSI: RELAYED, null RSSI, hopCount.
     */
    fun classifyHopObservation(
        hopsOnArrival: Int?,
        isDuplicate: Boolean,
        directLinkRssi: Int?
    ): HopClassification {
        val isDirect = hopsOnArrival == 0 && !isDuplicate && directLinkRssi != null
        return if (isDirect) {
            HopClassification(
                reachability = PeerReachability.DIRECT,
                rssiDbm = directLinkRssi,
                hopCount = 0
            )
        } else {
            HopClassification(
                reachability = PeerReachability.RELAYED,
                rssiDbm = null,
                hopCount = hopsOnArrival
            )
        }
    }

    /**
     * Pure merge logic combining a new observation with existing peer state.
     *
     * Rules:
     * 1. Direct observations (reachability == DIRECT && rssiDbm != null) set reachability to DIRECT,
     *    capture the RSSI measurement, and record [nowMillis] in `lastDirectMillis`.
     * 2. Relayed observations update `lastSeenMillis`. If the peer was previously direct, direct
     *    evidence is preserved until `directExpiryMillis` (30s) elapses. A flood echo arriving
     *    through a relay does NOT knock a direct neighbour off the radar.
     * 3. Once `directExpiryMillis` elapses without fresh direct evidence, the peer drops to
     *    RELAYED with null RSSI, null `lastDirectMillis`, and null `hopCount`.
     */
    fun mergePeerObservation(
        existing: PeerNodeEntity?,
        observation: PeerObservation,
        nowMillis: Long,
        hasSession: Boolean,
        isVerified: Boolean,
        directExpiryMillis: Long = DIRECT_LINK_EXPIRY_MILLIS
    ): PeerNodeEntity {
        val isDirectObs = observation.reachability == PeerReachability.DIRECT && observation.rssiDbm != null

        val effectiveReachability: PeerReachability
        val effectiveRssi: Int?
        val effectiveLastDirect: Long?
        val effectiveHops: Int?

        if (isDirectObs) {
            effectiveReachability = PeerReachability.DIRECT
            effectiveRssi = observation.rssiDbm
            effectiveLastDirect = nowMillis
            effectiveHops = observation.hopCount ?: 0
        } else {
            // Relayed observation: retain direct status if within directExpiryMillis window
            val hasRecentDirect = existing?.reachability == PeerReachability.DIRECT &&
                existing.lastDirectMillis != null &&
                (nowMillis - existing.lastDirectMillis <= directExpiryMillis)

            if (hasRecentDirect) {
                effectiveReachability = PeerReachability.DIRECT
                effectiveRssi = existing?.rssiDbm
                effectiveLastDirect = existing?.lastDirectMillis
                effectiveHops = existing?.hopCount ?: 0
            } else {
                effectiveReachability = PeerReachability.RELAYED
                effectiveRssi = null
                effectiveLastDirect = null
                effectiveHops = observation.hopCount
            }
        }

        return PeerNodeEntity(
            nodeId = observation.nodeId,
            displayName = existing?.displayName ?: "Node ${observation.nodeId.take(6)}",
            rssiDbm = effectiveRssi,
            lastSeenMillis = nowMillis,
            transport = observation.transport,
            hasSecureSession = hasSession,
            isVerified = isVerified,
            reachability = effectiveReachability,
            hopCount = effectiveHops,
            lastDirectMillis = effectiveLastDirect
        )
    }

    /**
     * Checks whether an existing direct peer's physical link evidence has expired.
     */
    fun recheckPeerExpiry(
        peer: PeerNodeEntity,
        nowMillis: Long,
        directExpiryMillis: Long = DIRECT_LINK_EXPIRY_MILLIS
    ): PeerNodeEntity {
        if (peer.reachability != PeerReachability.DIRECT) return peer
        val age = if (peer.lastDirectMillis != null) nowMillis - peer.lastDirectMillis else Long.MAX_VALUE
        if (age > directExpiryMillis) {
            return peer.copy(
                reachability = PeerReachability.RELAYED,
                rssiDbm = null,
                lastDirectMillis = null,
                hopCount = null
            )
        }
        return peer
    }
}
