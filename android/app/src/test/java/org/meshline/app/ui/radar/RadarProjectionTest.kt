package org.meshline.app.ui.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `peer entity reachability and custody flags reflect state honestly`() {
        val directCustodyPeer = PeerNodeEntity(
            nodeId = "n1",
            displayName = "Node n1",
            rssiDbm = -60,
            lastSeenMillis = 1000L,
            transport = "Bluetooth LE",
            reachability = PeerReachability.DIRECT,
            custodyPacketsHeld = 3
        )
        assertTrue(directCustodyPeer.isDirect)
        assertTrue(directCustodyPeer.isCustodyHolder)

        val relayedPeer = PeerNodeEntity(
            nodeId = "n2",
            displayName = "Node n2",
            rssiDbm = null,
            lastSeenMillis = 1000L,
            transport = "Bluetooth LE",
            reachability = PeerReachability.RELAYED,
            custodyPacketsHeld = 0
        )
        assertFalse(relayedPeer.isDirect)
        assertFalse(relayedPeer.isCustodyHolder)
    }
}
