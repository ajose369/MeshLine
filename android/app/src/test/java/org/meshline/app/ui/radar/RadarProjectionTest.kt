package org.meshline.app.ui.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshline.app.db.PeerNodeEntity
import org.meshline.app.db.PeerReachability

class RadarProjectionTest {

    @Test
    fun `null RSSI produces null range fraction`() {
        assertNull(RadarProjection.rangeFraction(null))
    }

    @Test
    fun `strongest signal (-40 dBm) maps to inner ring fraction`() {
        val fraction = RadarProjection.rangeFraction(-40)
        assertEquals(0.18f, fraction!!, 0.001f)
    }

    @Test
    fun `weakest signal (-95 dBm) maps to outer rim fraction`() {
        val fraction = RadarProjection.rangeFraction(-95)
        assertEquals(0.94f, fraction!!, 0.001f)
    }

    @Test
    fun `signal stronger than -40 dBm clamps to 0_18f`() {
        val fraction = RadarProjection.rangeFraction(0)
        assertEquals(0.18f, fraction!!, 0.001f)
    }

    @Test
    fun `signal weaker than -95 dBm clamps to 0_94f`() {
        val fraction = RadarProjection.rangeFraction(-110)
        assertEquals(0.94f, fraction!!, 0.001f)
    }

    @Test
    fun `range fraction is strictly monotonic with signal attenuation`() {
        val strong = RadarProjection.rangeFraction(-45)!!
        val medium = RadarProjection.rangeFraction(-65)!!
        val weak = RadarProjection.rangeFraction(-85)!!

        assertTrue("Strong signal must place node closer to center than medium", strong < medium)
        assertTrue("Medium signal must place node closer to center than weak", medium < weak)
    }

    @Test
    fun `null RSSI renders zero signal bars, never four bars`() {
        // Honest UI rule: unknown RSSI must never render as 4 full bars
        assertEquals(0, RadarProjection.signalBarCount(null))
    }

    @Test
    fun `signal bar thresholds map accurately`() {
        assertEquals(4, RadarProjection.signalBarCount(-45))
        assertEquals(4, RadarProjection.signalBarCount(-55))
        assertEquals(3, RadarProjection.signalBarCount(-56))
        assertEquals(3, RadarProjection.signalBarCount(-70))
        assertEquals(2, RadarProjection.signalBarCount(-71))
        assertEquals(2, RadarProjection.signalBarCount(-82))
        assertEquals(1, RadarProjection.signalBarCount(-83))
        assertEquals(1, RadarProjection.signalBarCount(-95))
    }

    @Test
    fun `plottablePeers only includes direct BLE links with measured RSSI`() {
        val directWithRssi = PeerNodeEntity(
            nodeId = "direct1",
            displayName = "Node direct1",
            rssiDbm = -65,
            lastSeenMillis = 1000L,
            transport = "Bluetooth LE",
            reachability = PeerReachability.DIRECT
        )
        val directNoRssi = PeerNodeEntity(
            nodeId = "direct2",
            displayName = "Node direct2",
            rssiDbm = null,
            lastSeenMillis = 1000L,
            transport = "Bluetooth LE",
            reachability = PeerReachability.DIRECT
        )
        val relayed = PeerNodeEntity(
            nodeId = "relayed1",
            displayName = "Node relayed1",
            rssiDbm = null,
            lastSeenMillis = 1000L,
            transport = "Bluetooth LE",
            reachability = PeerReachability.RELAYED,
            hopCount = 3
        )
        val relayedWithRssi = PeerNodeEntity(
            nodeId = "relayed2",
            displayName = "Node relayed2",
            rssiDbm = -50,
            lastSeenMillis = 1000L,
            transport = "Bluetooth LE",
            reachability = PeerReachability.RELAYED,
            hopCount = 2
        )

        val plottable = RadarProjection.plottablePeers(
            listOf(directWithRssi, directNoRssi, relayed, relayedWithRssi)
        )

        assertEquals(1, plottable.size)
        assertEquals("direct1", plottable.first().nodeId)
    }

    @Test
    fun `direct observation records direct reachability, physical RSSI and timestamp`() {
        val obs = PeerObservation(
            nodeId = "p1",
            transport = "Bluetooth LE",
            rssiDbm = -60,
            reachability = PeerReachability.DIRECT,
            hopCount = 1
        )
        val peer = RadarProjection.mergePeerObservation(
            existing = null,
            observation = obs,
            nowMillis = 10_000L,
            hasSession = true,
            isVerified = false
        )

        assertTrue(peer.isDirect)
        assertEquals(-60, peer.rssiDbm)
        assertEquals(10_000L, peer.lastDirectMillis)
        assertEquals(1, peer.hopCount)
    }

    @Test
    fun `direct peer transitions to relayed when window expires`() {
        val directPeer = PeerNodeEntity(
            nodeId = "p1",
            displayName = "Node p1",
            rssiDbm = -60,
            lastSeenMillis = 10_000L,
            transport = "Bluetooth LE",
            reachability = PeerReachability.DIRECT,
            lastDirectMillis = 10_000L
        )

        // Relayed observation 31 seconds later (> 30s DIRECT_LINK_EXPIRY_MILLIS)
        val relayedObs = PeerObservation(
            nodeId = "p1",
            transport = "Bluetooth LE",
            rssiDbm = null,
            reachability = PeerReachability.RELAYED,
            hopCount = 1
        )
        val merged = RadarProjection.mergePeerObservation(
            existing = directPeer,
            observation = relayedObs,
            nowMillis = 41_001L,
            hasSession = true,
            isVerified = false
        )

        assertFalse("Peer must drop to RELAYED after direct window expires", merged.isDirect)
        assertNull("RSSI must be cleared on expiration", merged.rssiDbm)
        assertNull("lastDirectMillis must be cleared", merged.lastDirectMillis)
    }

    @Test
    fun `direct peer drops to relayed immediately if packet arrived via multi-hop`() {
        val directPeer = PeerNodeEntity(
            nodeId = "p1",
            displayName = "Node p1",
            rssiDbm = -50,
            lastSeenMillis = 10_000L,
            transport = "Bluetooth LE",
            reachability = PeerReachability.DIRECT,
            lastDirectMillis = 10_000L
        )

        // Relayed packet from origin heard 5 seconds later over 3 hops
        val multiHopObs = PeerObservation(
            nodeId = "p1",
            transport = "Bluetooth LE",
            rssiDbm = null,
            reachability = PeerReachability.RELAYED,
            hopCount = 3
        )
        val merged = RadarProjection.mergePeerObservation(
            existing = directPeer,
            observation = multiHopObs,
            nowMillis = 15_000L,
            hasSession = true,
            isVerified = false
        )

        assertFalse("Multi-hop packet proves origin is not a direct link", merged.isDirect)
        assertNull(merged.rssiDbm)
        assertEquals(3, merged.hopCount)
    }

    @Test
    fun `relayed peer upgrades to direct upon fresh physical BLE measurement`() {
        val relayedPeer = PeerNodeEntity(
            nodeId = "p1",
            displayName = "Node p1",
            rssiDbm = null,
            lastSeenMillis = 10_000L,
            transport = "Bluetooth LE",
            reachability = PeerReachability.RELAYED,
            hopCount = 2
        )

        val directObs = PeerObservation(
            nodeId = "p1",
            transport = "Bluetooth LE",
            rssiDbm = -55,
            reachability = PeerReachability.DIRECT,
            hopCount = 1
        )
        val merged = RadarProjection.mergePeerObservation(
            existing = relayedPeer,
            observation = directObs,
            nowMillis = 20_000L,
            hasSession = true,
            isVerified = false
        )

        assertTrue(merged.isDirect)
        assertEquals(-55, merged.rssiDbm)
        assertEquals(20_000L, merged.lastDirectMillis)
        assertEquals(1, merged.hopCount)
    }

    @Test
    fun `1-hop observation without directLinkRssi remains relayed with null RSSI`() {
        val obs = PeerObservation(
            nodeId = "p1",
            transport = "Bluetooth LE",
            rssiDbm = null,
            reachability = PeerReachability.RELAYED,
            hopCount = 1
        )
        val peer = RadarProjection.mergePeerObservation(
            existing = null,
            observation = obs,
            nowMillis = 10_000L,
            hasSession = false,
            isVerified = false
        )

        assertFalse(peer.isDirect)
        assertNull(peer.rssiDbm)
    }

    @Test
    fun `recheckPeerExpiry downgrades direct peer when idle time exceeds direct window`() {
        val directPeer = PeerNodeEntity(
            nodeId = "p1",
            displayName = "Node p1",
            rssiDbm = -60,
            lastSeenMillis = 10_000L,
            transport = "Bluetooth LE",
            reachability = PeerReachability.DIRECT,
            lastDirectMillis = 10_000L
        )

        // 20s later: still valid
        val valid = RadarProjection.recheckPeerExpiry(directPeer, nowMillis = 30_000L)
        assertTrue(valid.isDirect)
        assertNotNull(valid.rssiDbm)

        // 31s later: expired
        val expired = RadarProjection.recheckPeerExpiry(directPeer, nowMillis = 40_001L)
        assertFalse(expired.isDirect)
        assertNull(expired.rssiDbm)
        assertNull(expired.lastDirectMillis)
    }
}
