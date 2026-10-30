package org.meshline.app.ui.radar

import org.meshline.app.db.PeerNodeEntity
import kotlin.math.abs

/**
 * Pure projection logic for Radar visualization.
 *
 * Implements the honesty rules from REVIEW.md H13:
 * - Signal strength must be a genuine physical radio measurement, never an invented default.
 * - Unknown RSSI yields 0 signal bars and is never plotted on range rings.
 * - Relayed identities heard across the mesh lack positional evidence and are excluded from the radar face.
 */
object RadarProjection {
    const val MIN_RSSI_DBM = -95
    const val MAX_RSSI_DBM = -40

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
}
