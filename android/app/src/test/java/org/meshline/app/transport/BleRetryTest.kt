package org.meshline.app.transport

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.meshline.app.db.StoreAndForwardManager
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BleRetryTest {

    private lateinit var context: Context
    private lateinit var characteristic: BluetoothGattCharacteristic
    private val testAddress = "AA:BB:CC:DD:EE:01"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        characteristic = BluetoothGattCharacteristic(
            BleTransportManager.MESHLINE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        // Drain any stale outbound queue before each test
        StoreAndForwardManager.getInstance(context).drainOutbound()
    }

    private fun dummyPacket(id: Byte, size: Int = 100): ByteArray {
        return ByteArray(size) { if (it == 0) id else (it % 250).toByte() }
    }

    @Test
    fun `failed final fragment of single-frame packet requeues the whole packet`() {
        val p0 = dummyPacket(1, 50)
        val packets = listOf(p0)
        val frames = listOf(
            BleTransportManager.Frame(packetIndex = 0, bytes = p0)
        )

        val state = BleTransportManager.WriteQueueState(
            packets = packets,
            frames = frames,
            submittedIndex = 0,
            acknowledgedIndex = -1, // Write failed, never acknowledged
            characteristic = characteristic
        )

        assertEquals("Whole packet must be identified as uncompleted", 0, state.firstUncompletedPacketIndex())

        val manager = BleTransportManager(context)
        manager.writeStates[testAddress] = state
        manager.requeueUnsent(testAddress)

        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertEquals(1, requeued.size)
        assertArrayEquals(p0, requeued[0])
    }

    @Test
    fun `failed final fragment of multi-fragment packet requeues the whole packet`() {
        val p0 = dummyPacket(1, 300)
        val packets = listOf(p0)
        val frames = listOf(
            BleTransportManager.Frame(packetIndex = 0, bytes = ByteArray(100)),
            BleTransportManager.Frame(packetIndex = 0, bytes = ByteArray(100)),
            BleTransportManager.Frame(packetIndex = 0, bytes = ByteArray(100)) // final fragment
        )

        // Frames 0 and 1 acknowledged; frame 2 submitted but failed
        val state = BleTransportManager.WriteQueueState(
            packets = packets,
            frames = frames,
            submittedIndex = 2,
            acknowledgedIndex = 1, // Last ACK was frame 1, so frame 2 failed
            characteristic = characteristic
        )

        assertEquals(
            "Packet 0 was not fully acknowledged; it must be requeued from packet 0",
            0,
            state.firstUncompletedPacketIndex()
        )

        val manager = BleTransportManager(context)
        manager.writeStates[testAddress] = state
        manager.requeueUnsent(testAddress)

        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertEquals(1, requeued.size)
        assertArrayEquals(p0, requeued[0])
    }

    @Test
    fun `failed middle fragment of multi-fragment packet requeues the whole packet`() {
        val p0 = dummyPacket(1, 300)
        val packets = listOf(p0)
        val frames = listOf(
            BleTransportManager.Frame(packetIndex = 0, bytes = ByteArray(100)),
            BleTransportManager.Frame(packetIndex = 0, bytes = ByteArray(100)),
            BleTransportManager.Frame(packetIndex = 0, bytes = ByteArray(100))
        )

        // Frame 0 acknowledged; frame 1 submitted and failed
        val state = BleTransportManager.WriteQueueState(
            packets = packets,
            frames = frames,
            submittedIndex = 1,
            acknowledgedIndex = 0,
            characteristic = characteristic
        )

        assertEquals(0, state.firstUncompletedPacketIndex())

        val manager = BleTransportManager(context)
        manager.writeStates[testAddress] = state
        manager.requeueUnsent(testAddress)

        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertEquals(1, requeued.size)
        assertArrayEquals(p0, requeued[0])
    }

    @Test
    fun `multi-packet batch where packet A completes and packet B final fragment fails`() {
        val p0 = dummyPacket(1, 200)
        val p1 = dummyPacket(2, 200)
        val packets = listOf(p0, p1)
        val frames = listOf(
            // Packet 0 has 2 frames
            BleTransportManager.Frame(packetIndex = 0, bytes = ByteArray(100)),
            BleTransportManager.Frame(packetIndex = 0, bytes = ByteArray(100)),
            // Packet 1 has 2 frames
            BleTransportManager.Frame(packetIndex = 1, bytes = ByteArray(100)),
            BleTransportManager.Frame(packetIndex = 1, bytes = ByteArray(100))
        )

        // Packet 0 was fully acknowledged (frames 0, 1 acked)
        // Packet 1 frame 2 acked, but frame 3 (final fragment) failed!
        val state = BleTransportManager.WriteQueueState(
            packets = packets,
            frames = frames,
            submittedIndex = 3,
            acknowledgedIndex = 2,
            characteristic = characteristic
        )

        assertEquals(
            "Packet 0 completed; Packet 1 failed its final fragment and must be requeued",
            1,
            state.firstUncompletedPacketIndex()
        )

        val manager = BleTransportManager(context)
        manager.writeStates[testAddress] = state
        manager.requeueUnsent(testAddress)

        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertEquals(1, requeued.size)
        assertArrayEquals("Only packet 1 should be requeued", p1, requeued[0])
    }

    @Test
    fun `multi-packet batch where packet A final fragment fails requeues both packets`() {
        val p0 = dummyPacket(1, 200)
        val p1 = dummyPacket(2, 200)
        val packets = listOf(p0, p1)
        val frames = listOf(
            // Packet 0 has 2 frames
            BleTransportManager.Frame(packetIndex = 0, bytes = ByteArray(100)),
            BleTransportManager.Frame(packetIndex = 0, bytes = ByteArray(100)),
            // Packet 1 has 2 frames
            BleTransportManager.Frame(packetIndex = 1, bytes = ByteArray(100)),
            BleTransportManager.Frame(packetIndex = 1, bytes = ByteArray(100))
        )

        // Frame 0 acked, frame 1 (packet 0's final fragment) failed!
        val state = BleTransportManager.WriteQueueState(
            packets = packets,
            frames = frames,
            submittedIndex = 1,
            acknowledgedIndex = 0,
            characteristic = characteristic
        )

        assertEquals(
            "Packet 0 did not finish, so packets starting from 0 must be requeued",
            0,
            state.firstUncompletedPacketIndex()
        )

        val manager = BleTransportManager(context)
        manager.writeStates[testAddress] = state
        manager.requeueUnsent(testAddress)

        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertEquals(2, requeued.size)
        assertArrayEquals("Packet 0 must be requeued first", p0, requeued[0])
        assertArrayEquals("Packet 1 must be requeued second", p1, requeued[1])
    }

    @Test
    fun `all frames acknowledged requeues nothing`() {
        val p0 = dummyPacket(1, 100)
        val p1 = dummyPacket(2, 100)
        val packets = listOf(p0, p1)
        val frames = listOf(
            BleTransportManager.Frame(packetIndex = 0, bytes = p0),
            BleTransportManager.Frame(packetIndex = 1, bytes = p1)
        )

        // All frames acked
        val state = BleTransportManager.WriteQueueState(
            packets = packets,
            frames = frames,
            submittedIndex = 1,
            acknowledgedIndex = 1,
            characteristic = characteristic
        )

        assertEquals(2, state.firstUncompletedPacketIndex())

        val manager = BleTransportManager(context)
        manager.writeStates[testAddress] = state
        manager.requeueUnsent(testAddress)

        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertTrue("No packets should be requeued when all were acknowledged", requeued.isEmpty())
    }

    @Test
    fun `timeout cancels cleanly when requeuing`() {
        val manager = BleTransportManager(context)
        val p0 = dummyPacket(1, 50)
        var timedOut = false
        val timeoutRunnable = Runnable { timedOut = true }
        manager.timeoutHandler.postDelayed(timeoutRunnable, 1000)
        val state = BleTransportManager.WriteQueueState(
            packets = listOf(p0),
            frames = listOf(BleTransportManager.Frame(0, p0)),
            submittedIndex = 0,
            acknowledgedIndex = -1,
            characteristic = characteristic,
            timeoutRunnable = timeoutRunnable
        )

        manager.writeStates[testAddress] = state
        manager.requeueUnsent(testAddress)

        assertNull("Timeout runnable reference should be cleared", state.timeoutRunnable)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        assertEquals("Posted timeout runnable must not execute after requeue cancel", false, timedOut)
    }

    private fun createGatt(manager: BleTransportManager): BluetoothGatt {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(app).grantPermissions(
            android.Manifest.permission.BLUETOOTH_CONNECT
        )
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = btManager.adapter.getRemoteDevice(testAddress)
        return device.connectGatt(context, false, manager.gattClientCallback)
    }

    @Test
    fun `rejected write immediately requeues packet and disconnects`() {
        val manager = BleTransportManager(context)
        var disconnectCalls = 0
        manager.disconnectInvoker = { disconnectCalls++ }
        manager.writeCharacteristicInvoker = { _, _, _ -> false }
        val gatt = createGatt(manager)

        val p0 = dummyPacket(1, 50)
        val state = BleTransportManager.WriteQueueState(
            packets = listOf(p0),
            frames = listOf(BleTransportManager.Frame(0, p0)),
            submittedIndex = -1,
            acknowledgedIndex = -1,
            characteristic = characteristic
        )

        manager.writeStates[testAddress] = state
        manager.sendNextFrame(gatt, state)

        assertNull("State must be removed from writeStates on immediate rejection", manager.writeStates[testAddress])
        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertEquals(1, requeued.size)
        assertArrayEquals(p0, requeued[0])
        assertEquals("Disconnect must be called on rejected write", 1, disconnectCalls)
    }

    @Test
    fun `write deadline expiry requeues packet and disconnects`() {
        val manager = BleTransportManager(context)
        var disconnectCalls = 0
        manager.disconnectInvoker = { disconnectCalls++ }
        manager.writeCharacteristicInvoker = { _, _, _ -> true }
        val gatt = createGatt(manager)

        val p0 = dummyPacket(2, 60)
        val state = BleTransportManager.WriteQueueState(
            packets = listOf(p0),
            frames = listOf(BleTransportManager.Frame(0, p0)),
            submittedIndex = -1,
            acknowledgedIndex = -1,
            characteristic = characteristic
        )

        manager.writeStates[testAddress] = state
        manager.sendNextFrame(gatt, state)

        assertEquals("Frame 0 must be submitted", 0, state.submittedIndex)
        assertNotNull("Write state should remain pending before timeout", manager.writeStates[testAddress])
        assertEquals(0, disconnectCalls)

        // Advance the looper past the write timeout
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertNull("Write state must be cleared after deadline expires", manager.writeStates[testAddress])
        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertEquals(1, requeued.size)
        assertArrayEquals(p0, requeued[0])
        assertEquals("Disconnect must be called on write deadline expiry", 1, disconnectCalls)
    }

    @Test
    fun `success callback advances exactly one frame until queue finishes`() {
        val manager = BleTransportManager(context)
        var disconnectCalls = 0
        manager.disconnectInvoker = { disconnectCalls++ }
        manager.writeCharacteristicInvoker = { _, _, _ -> true }
        val gatt = createGatt(manager)

        val p0 = dummyPacket(3, 100)
        val frame0 = dummyPacket(3, 50)
        val frame1 = dummyPacket(4, 50)
        val state = BleTransportManager.WriteQueueState(
            packets = listOf(p0),
            frames = listOf(
                BleTransportManager.Frame(0, frame0),
                BleTransportManager.Frame(0, frame1)
            ),
            submittedIndex = -1,
            acknowledgedIndex = -1,
            characteristic = characteristic
        )

        manager.writeStates[testAddress] = state
        manager.sendNextFrame(gatt, state)

        assertEquals("First frame submitted", 0, state.submittedIndex)
        assertEquals("No frames acknowledged yet", -1, state.acknowledgedIndex)

        // Frame 0 completes successfully
        manager.gattClientCallback.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)

        assertEquals("First frame acknowledged", 0, state.acknowledgedIndex)
        assertEquals("Second frame submitted", 1, state.submittedIndex)
        assertNotNull("State still active", manager.writeStates[testAddress])
        assertEquals(0, disconnectCalls)

        // Frame 1 completes successfully
        manager.gattClientCallback.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)

        assertEquals("Second frame acknowledged", 1, state.acknowledgedIndex)
        assertNull("Queue should be removed upon full completion", manager.writeStates[testAddress])
        assertEquals("Disconnect must be called upon full completion", 1, disconnectCalls)

        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertTrue("No packets should be requeued after clean completion", requeued.isEmpty())
    }

    @Test
    fun `failure callback requeues packet and disconnects`() {
        val manager = BleTransportManager(context)
        var disconnectCalls = 0
        manager.disconnectInvoker = { disconnectCalls++ }
        manager.writeCharacteristicInvoker = { _, _, _ -> true }
        val gatt = createGatt(manager)

        val p0 = dummyPacket(5, 50)
        val state = BleTransportManager.WriteQueueState(
            packets = listOf(p0),
            frames = listOf(BleTransportManager.Frame(0, p0)),
            submittedIndex = -1,
            acknowledgedIndex = -1,
            characteristic = characteristic
        )

        manager.writeStates[testAddress] = state
        manager.sendNextFrame(gatt, state)

        assertEquals(0, state.submittedIndex)
        assertEquals(0, disconnectCalls)

        // Write callback returns failure
        manager.gattClientCallback.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_FAILURE)

        assertNull("State must be removed after failure", manager.writeStates[testAddress])
        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertEquals(1, requeued.size)
        assertArrayEquals(p0, requeued[0])
        assertEquals("Disconnect must be called on write failure callback", 1, disconnectCalls)
    }

    @Test
    fun `service discovery deadline expiry cleans up connectedDevices and closes gatt`() {
        val manager = BleTransportManager(context)
        var disconnectCalls = 0
        var closeCalls = 0
        manager.disconnectInvoker = { disconnectCalls++ }
        manager.closeInvoker = { closeCalls++ }
        val gatt = createGatt(manager)

        manager.peerCount.set(1)
        manager.connectedDevices.add(testAddress)
        manager.scheduleDiscoveryTimeout(gatt, testAddress)

        assertTrue(manager.discoveryTimeouts.containsKey(testAddress))
        assertTrue(manager.connectedDevices.contains(testAddress))

        // Advance looper past discovery timeout
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertTrue("Connected devices must not retain timed out address", !manager.connectedDevices.contains(testAddress))
        assertTrue("Discovery timeouts map must clear timed out address", !manager.discoveryTimeouts.containsKey(testAddress))
        assertEquals("Peer count must decrement on discovery timeout", 0, manager.activePeersCount)
        assertEquals("Disconnect must be called on discovery deadline", 1, disconnectCalls)
        assertEquals("Close must be called on discovery deadline", 1, closeCalls)
    }

    @Test
    fun `service discovery rejection cleans up connectedDevices and disconnects`() {
        val manager = BleTransportManager(context)
        var disconnectCalls = 0
        manager.disconnectInvoker = { disconnectCalls++ }
        val gatt = createGatt(manager)

        manager.connectedDevices.add(testAddress)
        manager.discoverServicesInvoker = { _ -> false }

        val ok = manager.discoverServicesQuietly(gatt)
        assertEquals(false, ok)
        assertTrue("Connected devices must be cleared when discoverServices fails", !manager.connectedDevices.contains(testAddress))
        assertEquals("Disconnect must be called when discoverServices fails", 1, disconnectCalls)
    }

    @Test
    fun `connect flow schedules discovery deadline, expiring it decrements activePeersCount and closes gatt`() {
        val manager = BleTransportManager(context)
        var disconnectCalls = 0
        var closeCalls = 0
        manager.disconnectInvoker = { disconnectCalls++ }
        manager.closeInvoker = { closeCalls++ }
        manager.discoverServicesInvoker = { true }
        val gatt = createGatt(manager)

        // Connect fires STATE_CONNECTED
        manager.gattClientCallback.onConnectionStateChange(
            gatt,
            BluetoothGatt.GATT_SUCCESS,
            android.bluetooth.BluetoothProfile.STATE_CONNECTED
        )

        assertEquals("Peer count must increment on connect", 1, manager.activePeersCount)
        assertTrue("Discovery deadline must be scheduled", manager.discoveryTimeouts.containsKey(testAddress))
        assertEquals(0, disconnectCalls)
        assertEquals(0, closeCalls)

        // Advance looper past discovery deadline (e.g. 10s)
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("Active peers count must decrement to 0 on discovery timeout", 0, manager.activePeersCount)
        assertTrue("Discovery timeout map must be cleared", !manager.discoveryTimeouts.containsKey(testAddress))
        assertTrue("Connected devices must be cleared", !manager.connectedDevices.contains(testAddress))
        assertEquals("Disconnect must be called on discovery timeout", 1, disconnectCalls)
        assertEquals("Close must be called on discovery timeout", 1, closeCalls)
    }

    @Test
    fun `healthy discovery cancels discovery deadline so expiring looper does not close gatt`() {
        val manager = BleTransportManager(context)
        var closeCalls = 0
        manager.closeInvoker = { closeCalls++ }
        manager.discoverServicesInvoker = { true }
        val gatt = createGatt(manager)

        // Connect fires STATE_CONNECTED
        manager.gattClientCallback.onConnectionStateChange(
            gatt,
            BluetoothGatt.GATT_SUCCESS,
            android.bluetooth.BluetoothProfile.STATE_CONNECTED
        )
        assertTrue("Discovery deadline scheduled", manager.discoveryTimeouts.containsKey(testAddress))

        // Complete discovery successfully
        manager.gattClientCallback.onServicesDiscovered(gatt, BluetoothGatt.GATT_SUCCESS)

        assertTrue("Discovery deadline must be cancelled on services discovered", !manager.discoveryTimeouts.containsKey(testAddress))

        // Advance looper past discovery deadline
        org.robolectric.shadows.ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals("Close must NOT be called when discovery was healthy", 0, closeCalls)
    }

    @Test
    fun `stopping transport requeues in-flight write states`() {
        val manager = BleTransportManager(context)
        val p0 = dummyPacket(7, 50)
        val state = BleTransportManager.WriteQueueState(
            packets = listOf(p0),
            frames = listOf(BleTransportManager.Frame(0, p0)),
            submittedIndex = 0,
            acknowledgedIndex = -1,
            characteristic = characteristic
        )

        manager.writeStates[testAddress] = state
        manager.stopMeshTransport()

        assertTrue("Write states should be empty after stop", manager.writeStates.isEmpty())
        val requeued = StoreAndForwardManager.getInstance(context).drainOutbound()
        assertEquals(1, requeued.size)
        assertArrayEquals(p0, requeued[0])
    }
}
