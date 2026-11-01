package org.meshline.app.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.meshline.app.db.CustodyPacket
import org.meshline.app.db.StoreAndForwardManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for per-neighbour flood fan-out and packet custody (REVIEW H1, task B-102).
 *
 * Verifies:
 * 1. Packets stay in local custody independently of any single connection; each neighbour gets its own transfer state.
 * 2. A packet is never handed back to the neighbour that supplied it.
 * 3. The A–B–C–D multi-hop flood routing scenario from REVIEW H1.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleFanOutCustodyTest {

    private lateinit var context: Context
    private lateinit var characteristic: BluetoothGattCharacteristic
    private lateinit var store: StoreAndForwardManager

    private val peerA = "00:11:22:33:44:0A"
    private val peerB = "00:11:22:33:44:0B"
    private val peerC = "00:11:22:33:44:0C"
    private val peerD = "00:11:22:33:44:0D"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StoreAndForwardManager.resetForTesting()
        store = StoreAndForwardManager.getInstance(context)
        store.clearCustody()

        characteristic = BluetoothGattCharacteristic(
            BleTransportManager.MESHLINE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or
                BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_ADVERTISE
        )
    }

    private fun dummyPacket(id: Byte, size: Int = 80): ByteArray {
        return ByteArray(size) { if (it == 0) id else ((it + id) % 250).toByte() }
    }

    private fun createGatt(manager: BleTransportManager, address: String): BluetoothGatt {
        manager.characteristicResolver = { characteristic }
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = btManager.adapter.getRemoteDevice(address)
        return device.connectGatt(context, false, manager.gattClientCallback)
    }

    @Test
    fun `fan-out preserves custody when first neighbour connects so subsequent neighbour receives packet (REVIEW H1)`() {
        val manager = BleTransportManager(context)
        manager.disconnectInvoker = { }
        manager.writeCharacteristicInvoker = { _, _, _ -> true }

        val packet = dummyPacket(1, 100)
        store.queueRawPacket(packet)

        // Initial state: packet is in custody
        assertEquals(1, store.pendingOutboundCount())
        assertTrue("Packet must be eligible for neighbour B", store.hasPendingOutboundForPeer(peerB))
        assertTrue("Packet must be eligible for neighbour C", store.hasPendingOutboundForPeer(peerC))

        // Neighbour B connects first and discovers services
        val gattB = createGatt(manager, peerB)
        manager.gattClientCallback.onServicesDiscovered(gattB, BluetoothGatt.GATT_SUCCESS)

        // B has a WriteQueueState with the packet
        val stateB = manager.writeStates[peerB]
        assertNotNull("WriteQueueState must be created for B", stateB)
        assertEquals(1, stateB!!.packets.size)
        assertArrayEquals(packet, stateB.packets[0])

        // Crucial test of REVIEW H1: Packet must STILL be in custody after B drains/takes its queue!
        assertEquals("Packet must remain in local custody after B begins transfer", 1, store.pendingOutboundCount())
        assertTrue("Packet must still be eligible for neighbour C after B connects", store.hasPendingOutboundForPeer(peerC))

        // B successfully writes and acknowledges all frames of the packet
        for (i in stateB.frames.indices) {
            stateB.submittedIndex = i
            manager.gattClientCallback.onCharacteristicWrite(gattB, characteristic, BluetoothGatt.GATT_SUCCESS)
        }

        // B is now marked as having received this packet
        assertTrue("Packet must be marked as delivered to B", store.isPacketDeliveredToPeer(packet, peerB))
        assertFalse("B must not receive duplicate if rechecked", store.hasPendingOutboundForPeer(peerB))

        // But packet remains in custody for C!
        assertEquals("Packet must stay in local custody for other peers after B completes", 1, store.pendingOutboundCount())
        assertTrue("C must still be eligible to receive the packet", store.hasPendingOutboundForPeer(peerC))

        // Now neighbour C connects subsequently (the exact failing scenario from REVIEW H1)
        val gattC = createGatt(manager, peerC)
        manager.gattClientCallback.onServicesDiscovered(gattC, BluetoothGatt.GATT_SUCCESS)

        // C must NOT find an empty queue! C gets the packet.
        val stateC = manager.writeStates[peerC]
        assertNotNull("WriteQueueState must be created for C (queue must not be empty!)", stateC)
        assertEquals("C must receive the packet that B also received", 1, stateC!!.packets.size)
        assertArrayEquals(packet, stateC.packets[0])

        // C successfully writes and acknowledges all frames
        for (i in stateC.frames.indices) {
            stateC.submittedIndex = i
            manager.gattClientCallback.onCharacteristicWrite(gattC, characteristic, BluetoothGatt.GATT_SUCCESS)
        }

        // Both B and C have received the packet
        assertTrue("Packet delivered to B", store.isPacketDeliveredToPeer(packet, peerB))
        assertTrue("Packet delivered to C", store.isPacketDeliveredToPeer(packet, peerC))

        // Reconnects do not produce duplicates
        assertEquals(0, store.getOutboundPacketsForPeer(peerB).size)
        assertEquals(0, store.getOutboundPacketsForPeer(peerC).size)
    }

    @Test
    fun `relayed packet is never handed back to the neighbour that supplied it (REVIEW H1)`() {
        val manager = BleTransportManager(context)
        manager.disconnectInvoker = { }
        manager.writeCharacteristicInvoker = { _, _, _ -> true }

        val inboundPacket = dummyPacket(5, 120)
        val relayPacket = dummyPacket(6, 120)

        // Mock core bridge processIncoming to return a relay packet
        store.processIncomingInvoker = { raw ->
            if (raw.contentEquals(inboundPacket)) {
                """
                {
                    "packet_type": "PublicSos",
                    "sender_id": "origin_node",
                    "msg_id": "0102030405060708090a0b0c0d0e0f10",
                    "ttl": 7,
                    "timestamp": 1700000000,
                    "addressed_to_us": false,
                    "relay_packet_hex": "${relayPacket.joinToString("") { "%02x".format(it) }}"
                }
                """.trimIndent()
            } else null
        }

        // Neighbour B connects to A's GATT server and writes the inbound packet
        store.processIncomingPacket(
            rawPacket = inboundPacket,
            transport = "Bluetooth LE",
            directLinkRssi = -60,
            sourceAddress = peerB
        )

        // Custody holds the relay packet
        assertEquals(1, store.pendingOutboundCount())
        val custody = store.getCustodyPacket(relayPacket)
        assertNotNull("Relay packet must be in custody", custody)
        assertEquals("Relay packet supplier must be recorded as peer B", peerB, custody!!.sourceAddress)

        // A must NEVER hand the relay packet back to neighbour B (the supplier!)
        assertFalse("Packet must not be eligible for B (the supplier)", store.hasPendingOutboundForPeer(peerB))
        val outboundForB = store.getOutboundPacketsForPeer(peerB)
        assertEquals("Outbound packets for supplier B must be empty", 0, outboundForB.size)

        // If B connects to A, onServicesDiscovered finds nothing to send to B
        val gattB = createGatt(manager, peerB)
        manager.gattClientCallback.onServicesDiscovered(gattB, BluetoothGatt.GATT_SUCCESS)
        assertNull("No write state should be scheduled for supplier B", manager.writeStates[peerB])

        // When neighbour C connects to A, C did NOT supply the packet: C receives it!
        assertTrue("Packet must be eligible for neighbour C", store.hasPendingOutboundForPeer(peerC))
        val outboundForC = store.getOutboundPacketsForPeer(peerC)
        assertEquals(1, outboundForC.size)
        assertArrayEquals(relayPacket, outboundForC[0])

        val gattC = createGatt(manager, peerC)
        manager.gattClientCallback.onServicesDiscovered(gattC, BluetoothGatt.GATT_SUCCESS)
        val stateC = manager.writeStates[peerC]
        assertNotNull("Write state must be created for C", stateC)
        assertArrayEquals(relayPacket, stateC!!.packets[0])
    }

    @Test
    fun `full A-B-C-D multi-hop scenario from REVIEW H1 delivers across the chain`() {
        // Topology from REVIEW H1:
        // A has neighbors B and C. C is the only route to D.
        // Linear chain: B <-> A <-> C <-> D
        // A originates packet; B connects first; C connects subsequently and routes to D.

        val packet = dummyPacket(9, 150)
        store.queueRawPacket(packet)

        // 1. B connects to A first
        val outboundForB = store.getOutboundPacketsForPeer(peerB)
        assertEquals(1, outboundForB.size)
        store.markPacketDeliveredToPeer(packet, peerB)

        // 2. C connects to A afterwards: queue is NOT empty
        val outboundForC = store.getOutboundPacketsForPeer(peerC)
        assertEquals("C must receive packet even after B took it", 1, outboundForC.size)
        assertArrayEquals(packet, outboundForC[0])
        store.markPacketDeliveredToPeer(packet, peerC)

        // 3. Now simulate node C holding the packet and forwarding towards D
        // On node C: supplier was A
        val storeC = StoreAndForwardManager.getInstance(context)
        // Reset and simulate C's store
        storeC.clearCustody()
        storeC.queueRawPacket(packet, sourceAddress = peerA)

        // C must not send back to A
        assertEquals(0, storeC.getOutboundPacketsForPeer(peerA).size)

        // C forwards to D
        val outboundForD = storeC.getOutboundPacketsForPeer(peerD)
        assertEquals("D must receive packet forwarded through C", 1, outboundForD.size)
        assertArrayEquals(packet, outboundForD[0])
        storeC.markPacketDeliveredToPeer(packet, peerD)

        // Final verification: D received packet
        assertTrue(storeC.isPacketDeliveredToPeer(packet, peerD))
    }

    @Test
    fun `failed or interrupted transfer keeps independent transfer state and retries on reconnect`() {
        val manager = BleTransportManager(context)
        manager.disconnectInvoker = { }
        manager.writeCharacteristicInvoker = { _, _, _ -> true }

        val p0 = dummyPacket(10, 200) // Multi-fragment packet
        store.queueRawPacket(p0)

        // B connects
        val gattB = createGatt(manager, peerB)
        manager.gattClientCallback.onServicesDiscovered(gattB, BluetoothGatt.GATT_SUCCESS)
        val stateB = manager.writeStates[peerB]
        assertNotNull(stateB)
        assertTrue("Packet should require multiple frames", stateB!!.frames.size > 1)

        // Frame 0 succeeds
        stateB.submittedIndex = 0
        manager.gattClientCallback.onCharacteristicWrite(gattB, characteristic, BluetoothGatt.GATT_SUCCESS)

        // Before frame 1 is acknowledged, B disconnects or write fails
        manager.requeueUnsent(peerB)

        // Packet must NOT be marked delivered to B because B did not finish all frames!
        assertFalse("Packet must not be marked delivered to B", store.isPacketDeliveredToPeer(p0, peerB))

        // Concurrently/subsequently, C connects and completes the whole packet
        val gattC = createGatt(manager, peerC)
        manager.gattClientCallback.onServicesDiscovered(gattC, BluetoothGatt.GATT_SUCCESS)
        val stateC = manager.writeStates[peerC]
        assertNotNull(stateC)

        for (i in stateC!!.frames.indices) {
            stateC.submittedIndex = i
            manager.gattClientCallback.onCharacteristicWrite(gattC, characteristic, BluetoothGatt.GATT_SUCCESS)
        }

        // C successfully received it
        assertTrue("Packet delivered to C", store.isPacketDeliveredToPeer(p0, peerC))

        // When B reconnects later, B must still be eligible and offered the packet!
        assertTrue("B must still be eligible on reconnect", store.hasPendingOutboundForPeer(peerB))
        val retryForB = store.getOutboundPacketsForPeer(peerB)
        assertEquals(1, retryForB.size)
        assertArrayEquals(p0, retryForB[0])
    }

    @Test
    fun `custody packet ages out after expiry and is pruned`() {
        val p0 = dummyPacket(20, 50)
        // Add with short TTL of 100ms
        store.addCustodyPacket(p0, sourceAddress = null, ttlMillis = 100L)

        assertEquals(1, store.pendingOutboundCount())
        val futureTime = System.currentTimeMillis() + 500L

        // After TTL expires
        store.pruneExpiredCustody(nowMillis = futureTime)
        assertEquals(0, store.pendingOutboundCount())
        assertEquals(0, store.getOutboundPacketsForPeer(peerB).size)
    }

    @Test
    fun `panic wipe clears all custody packets`() {
        store.queueRawPacket(dummyPacket(1, 50))
        store.queueRawPacket(dummyPacket(2, 50))
        assertEquals(2, store.pendingOutboundCount())

        store.panicWipe()

        assertEquals("Custody count must be 0 after panic wipe", 0, store.pendingOutboundCount())
        assertFalse("No outbound packets should remain", store.hasPendingOutbound())
        assertEquals(0, store.getOutboundPacketsForPeer(peerB).size)
    }

    @Test
    fun `custody store is bounded by MAX_CUSTODY_PACKETS`() {
        val limit = StoreAndForwardManager.MAX_CUSTODY_PACKETS
        for (i in 0 until limit + 20) {
            store.queueRawPacket(dummyPacket(i.toByte(), 30))
        }

        assertTrue("Custody must not exceed MAX_CUSTODY_PACKETS", store.pendingOutboundCount() <= limit)
    }
}
