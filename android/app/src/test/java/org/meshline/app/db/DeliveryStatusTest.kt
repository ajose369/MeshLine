package org.meshline.app.db

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.transport.BleTransportManager
import org.meshline.app.transport.PacketFraming
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowBluetoothGatt

@RunWith(RobolectricTestRunner::class)
class DeliveryStatusTest {

    private lateinit var context: Context
    private lateinit var store: StoreAndForwardManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        StoreAndForwardManager.resetForTesting()
        store = StoreAndForwardManager.getInstance(context)
        store.panicWipe()
    }

    @After
    fun tearDown() {
        store.panicWipe()
        StoreAndForwardManager.resetForTesting()
    }

    @Test
    fun `sendSos records canonical packet id in history with QUEUED status`() {
        val canonicalId = "11223344556677889900aabbccddeeff"
        val packetBytes = byteArrayOf(1, 2, 3, 4)
        store.createSosPacketInvoker = { text, lat, lon, acc ->
            MeshCoreBridge.CreatedPacket(canonicalId, packetBytes)
        }

        val ok = store.sendSos("SOS: need water", 12.34, 56.78, 5.0f)
        assertTrue(ok)
        assertEquals(1, store.pendingOutboundCount())

        val msg = store.getMessage(canonicalId)
        assertNotNull(msg)
        assertEquals(canonicalId, msg?.msgId)
        assertEquals("SOS: need water", msg?.payloadText)
        assertEquals("PublicSos", msg?.packetType)
        assertEquals(MessageStatus.QUEUED, msg?.status)
    }

    @Test
    fun `sendSos fails closed with FAILED status and UUID when bridge unavailable`() {
        store.createSosPacketInvoker = { _, _, _, _ -> null }

        val ok = store.sendSos("SOS: need water", 12.34, 56.78, 5.0f)
        assertFalse(ok)
        assertEquals(0, store.pendingOutboundCount())

        val messages = store.messagesFlow.value
        assertEquals(1, messages.size)
        val msg = messages.first()
        assertEquals(MessageStatus.FAILED, msg.status)
        assertEquals("SOS: need water", msg.payloadText)
    }

    @Test
    fun `sendChat and sendGroupChat record canonical packet id with QUEUED status`() {
        val chatPacketId = "chat11223344556677889900aabbccdd"
        val groupPacketId = "group11223344556677889900aabbccd"

        store.createChatPacketInvoker = { recipient, text ->
            MeshCoreBridge.CreatedPacket(chatPacketId, byteArrayOf(10, 20))
        }
        store.createGroupChatPacketInvoker = { group, text ->
            MeshCoreBridge.CreatedPacket(groupPacketId, byteArrayOf(30, 40))
        }

        assertTrue(store.sendChat("recipient_node_id", "hello private"))
        val chatMsg = store.getMessage(chatPacketId)
        assertNotNull(chatMsg)
        assertEquals(chatPacketId, chatMsg?.msgId)
        assertEquals(MessageStatus.QUEUED, chatMsg?.status)
        assertTrue(chatMsg?.encrypted == true)

        assertTrue(store.sendGroupChat("group_node_id", "hello group"))
        val groupMsg = store.getMessage(groupPacketId)
        assertNotNull(groupMsg)
        assertEquals(groupPacketId, groupMsg?.msgId)
        assertEquals(MessageStatus.QUEUED, groupMsg?.status)
        assertTrue(groupMsg?.encrypted == true)
        assertEquals("group_node_id", groupMsg?.groupId)
    }

    @Test
    fun `sendChat and sendGroupChat fail closed when bridge unavailable`() {
        store.createChatPacketInvoker = { _, _ -> null }
        store.createGroupChatPacketInvoker = { _, _ -> null }

        assertFalse(store.sendChat("peer", "secret"))
        assertFalse(store.sendGroupChat("group", "secret"))

        val messages = store.messagesFlow.value
        assertEquals(2, messages.size)
        assertTrue(messages.all { it.status == MessageStatus.FAILED })
    }

    @Test
    fun `drainOutbound drains raw packets without transitioning QUEUED messages to SENT`() {
        val msgId = "packet_canonical_id_001"
        store.createSosPacketInvoker = { text, _, _, _ ->
            MeshCoreBridge.CreatedPacket(msgId, byteArrayOf(1, 2, 3))
        }

        store.sendSos("help")
        assertEquals(1, store.pendingOutboundCount())
        assertEquals(MessageStatus.QUEUED, store.getMessage(msgId)?.status)

        // Draining must NOT change QUEUED to SENT
        val packets = store.drainOutbound()
        assertEquals(1, packets.size)
        assertEquals(0, store.pendingOutboundCount())
        assertEquals(MessageStatus.QUEUED, store.getMessage(msgId)?.status)
    }

    @Test
    fun `markSent transitions QUEUED to SENT and leaves DELIVERED intact`() {
        val msgId = "packet_canonical_id_002"
        store.createSosPacketInvoker = { text, _, _, _ ->
            MeshCoreBridge.CreatedPacket(msgId, byteArrayOf(1, 2, 3))
        }
        store.sendSos("help")

        // QUEUED -> SENT
        store.markSent(msgId)
        assertEquals(MessageStatus.SENT, store.getMessage(msgId)?.status)

        // Mark delivered
        store.markDelivered(msgId)
        assertEquals(MessageStatus.DELIVERED, store.getMessage(msgId)?.status)

        // markSent must NOT downgrade DELIVERED to SENT
        store.markSent(msgId)
        assertEquals(MessageStatus.DELIVERED, store.getMessage(msgId)?.status)
    }

    @Test
    fun `markDelivered transitions SENT and QUEUED to DELIVERED`() {
        val msgId1 = "packet_001"
        val msgId2 = "packet_002"

        store.createSosPacketInvoker = { text, _, _, _ ->
            MeshCoreBridge.CreatedPacket(if (text == "1") msgId1 else msgId2, byteArrayOf(1))
        }
        store.sendSos("1")
        store.sendSos("2")

        // msg1: QUEUED -> DELIVERED directly
        store.markDelivered(msgId1)
        assertEquals(MessageStatus.DELIVERED, store.getMessage(msgId1)?.status)

        // msg2: QUEUED -> SENT -> DELIVERED
        store.markSent(msgId2)
        assertEquals(MessageStatus.SENT, store.getMessage(msgId2)?.status)
        store.markDelivered(msgId2)
        assertEquals(MessageStatus.DELIVERED, store.getMessage(msgId2)?.status)
    }

    @Test
    fun `processIncomingPacket with Ack packet extracts acked_message_id and marks delivered`() {
        val outgoingMsgId = "local_outbound_msg_id_999"
        store.createSosPacketInvoker = { _, _, _, _ ->
            MeshCoreBridge.CreatedPacket(outgoingMsgId, byteArrayOf(1, 2, 3))
        }
        store.sendSos("Distress message")
        store.markSent(outgoingMsgId)
        assertEquals(MessageStatus.SENT, store.getMessage(outgoingMsgId)?.status)

        // Inbound ACK packet signed by recipient acknowledging outgoingMsgId
        val ackJson = """
            {
                "verified": true,
                "packet_type": "Ack",
                "sender_id": "peer_node_abc",
                "msg_id": "ack_packet_id_0001",
                "timestamp": 1700000000,
                "ttl": 7,
                "addressed_to_us": true,
                "should_relay": false,
                "acked_message_id": "$outgoingMsgId"
            }
        """.trimIndent()

        store.processIncomingInvoker = { _ -> ackJson }
        store.processIncomingPacket(byteArrayOf(99))

        // Outbound message was marked DELIVERED
        assertEquals(MessageStatus.DELIVERED, store.getMessage(outgoingMsgId)?.status)
        // The ACK packet's own ID was not stored as a message
        assertNull(store.getMessage("ack_packet_id_0001"))
    }

    @Test
    fun `processIncomingPacket with Ack missing acked_message_id does not mark delivered`() {
        val outgoingMsgId = "local_outbound_msg_id_888"
        store.createSosPacketInvoker = { _, _, _, _ ->
            MeshCoreBridge.CreatedPacket(outgoingMsgId, byteArrayOf(1, 2, 3))
        }
        store.sendSos("Distress message")
        store.markSent(outgoingMsgId)

        val unverifiedAckJson = """
            {
                "verified": true,
                "packet_type": "Ack",
                "sender_id": "peer_node_abc",
                "msg_id": "ack_packet_id_0002",
                "timestamp": 1700000000,
                "ttl": 7,
                "addressed_to_us": true,
                "should_relay": false,
                "acked_message_id": null
            }
        """.trimIndent()

        store.processIncomingInvoker = { _ -> unverifiedAckJson }
        store.processIncomingPacket(byteArrayOf(99))

        // Message remains SENT, not DELIVERED
        assertEquals(MessageStatus.SENT, store.getMessage(outgoingMsgId)?.status)
    }

    @Test
    fun `BleTransportManager marks packet sent only when final fragment write succeeds`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(app).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT)
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val ble = BleTransportManager(context)
        val msgId = "multi_fragment_packet_123"
        store.createSosPacketInvoker = { _, _, _, _ ->
            MeshCoreBridge.CreatedPacket(msgId, byteArrayOf(1, 2, 3))
        }
        store.sendSos("Large packet")
        assertEquals(MessageStatus.QUEUED, store.getMessage(msgId)?.status)

        ble.packetIdResolver = { packet ->
            if (packet.contentEquals(byteArrayOf(1, 2, 3))) msgId else null
        }
        ble.writeCharacteristicInvoker = { _, _, _ -> true }
        ble.disconnectInvoker = { }

        val testAddress = "11:22:33:44:55:66"
        val device = btManager.adapter.getRemoteDevice(testAddress)
        val gatt = device.connectGatt(context, false, ble.gattClientCallback)

        val characteristic = BluetoothGattCharacteristic(
            BleTransportManager.MESHLINE_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        // Simulate queue with 2 frames for packet 0
        val packet0 = byteArrayOf(1, 2, 3)
        val frames = listOf(
            BleTransportManager.Frame(0, byteArrayOf(10)),
            BleTransportManager.Frame(0, byteArrayOf(20))
        )
        val state = BleTransportManager.WriteQueueState(
            packets = listOf(packet0),
            frames = frames,
            submittedIndex = 0,
            acknowledgedIndex = -1,
            characteristic = characteristic
        )

        ble.writeStates[testAddress] = state

        // Frame 0 write acknowledged: packet 0 is NOT yet completed
        ble.gattClientCallback.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        assertEquals(MessageStatus.QUEUED, store.getMessage(msgId)?.status)

        // Frame 1 write submitted and acknowledged: packet 0 IS completed
        state.submittedIndex = 1
        ble.gattClientCallback.onCharacteristicWrite(gatt, characteristic, BluetoothGatt.GATT_SUCCESS)
        assertEquals(MessageStatus.SENT, store.getMessage(msgId)?.status)
    }

    @Test
    fun `delivery states survive EncryptedMessageStore serialization round-trip`() {
        val messages = listOf(
            MessageEntity("m1", MessageEntity.LOCAL_SENDER, "peer1", "msg1", 8, 1000, "Chat", MessageStatus.QUEUED),
            MessageEntity("m2", MessageEntity.LOCAL_SENDER, "peer2", "msg2", 8, 2000, "Chat", MessageStatus.SENT),
            MessageEntity("m3", MessageEntity.LOCAL_SENDER, "peer3", "msg3", 8, 3000, "Chat", MessageStatus.DELIVERED),
            MessageEntity("m4", MessageEntity.LOCAL_SENDER, "peer4", "msg4", 8, 4000, "Chat", MessageStatus.FAILED)
        )

        val json = EncryptedMessageStore.serialize(messages)
        val restored = EncryptedMessageStore.parse(json).associateBy { it.msgId }

        assertEquals(MessageStatus.QUEUED, restored["m1"]?.status)
        assertEquals(MessageStatus.SENT, restored["m2"]?.status)
        assertEquals(MessageStatus.DELIVERED, restored["m3"]?.status)
        assertEquals(MessageStatus.FAILED, restored["m4"]?.status)
    }

    @Test
    fun `inbound echo of own SOS does not overwrite outgoing record or change sender`() {
        val sosId = "own_sos_11223344556677889900aabb"
        store.createSosPacketInvoker = { text, lat, lon, acc ->
            MeshCoreBridge.CreatedPacket(sosId, byteArrayOf(1, 2, 3))
        }

        assertTrue(store.sendSos("SOS: local emergency", 10.0, 20.0, 5.0f))
        store.markSent(sosId)
        val outgoing = store.getMessage(sosId)
        assertNotNull(outgoing)
        assertTrue(outgoing!!.isOutgoing)
        assertEquals(MessageEntity.LOCAL_SENDER, outgoing.senderId)
        assertEquals(MessageStatus.SENT, outgoing.status)

        // Simulated echo arriving from neighbour
        store.processIncomingInvoker = {
            """
            {
                "packet_type": "PublicSos",
                "sender_id": "neighbour_relayed_node",
                "msg_id": "$sosId",
                "ttl": 7,
                "timestamp": 1700000000,
                "addressed_to_us": false,
                "sos_text": "SOS: local emergency"
            }
            """.trimIndent()
        }

        store.processIncomingPacket(byteArrayOf(1, 2, 3))

        val afterEcho = store.getMessage(sosId)
        assertNotNull(afterEcho)
        assertEquals("Sender must remain LOCAL_SENDER after echo", MessageEntity.LOCAL_SENDER, afterEcho!!.senderId)
        assertTrue("isOutgoing must remain true after echo", afterEcho.isOutgoing)
        assertEquals("Payload must be unchanged", "SOS: local emergency", afterEcho.payloadText)
        assertEquals("Status must remain SENT", MessageStatus.SENT, afterEcho.status)
    }

    @Test
    fun `inbound echo of own group chat does not overwrite outgoing record or change sender`() {
        val groupId = "group_12345"
        val groupMsgId = "own_group_msg_1122334455667788"
        store.createGroupChatPacketInvoker = { gId, text ->
            MeshCoreBridge.CreatedPacket(groupMsgId, byteArrayOf(4, 5, 6))
        }

        assertTrue(store.sendGroupChat(groupId, "Hello group from me"))
        val outgoing = store.getMessage(groupMsgId)
        assertNotNull(outgoing)
        assertTrue(outgoing!!.isOutgoing)
        assertEquals(MessageStatus.QUEUED, outgoing.status)

        // Simulated echo arriving from mesh
        store.processIncomingInvoker = {
            """
            {
                "packet_type": "GroupChat",
                "sender_id": "mesh_node_relayer",
                "msg_id": "$groupMsgId",
                "group_id": "$groupId",
                "ttl": 6,
                "timestamp": 1700000000,
                "addressed_to_us": false,
                "plaintext": "Hello group from me"
            }
            """.trimIndent()
        }

        store.processIncomingPacket(byteArrayOf(4, 5, 6))

        val afterEcho = store.getMessage(groupMsgId)
        assertNotNull(afterEcho)
        assertEquals("Sender must remain LOCAL_SENDER", MessageEntity.LOCAL_SENDER, afterEcho!!.senderId)
        assertTrue("isOutgoing must remain true", afterEcho.isOutgoing)
        assertEquals("Payload must be unchanged", "Hello group from me", afterEcho.payloadText)
        assertEquals("Status must remain QUEUED", MessageStatus.QUEUED, afterEcho.status)
    }

    @Test
    fun `foreign packet reusing outgoing msg_id is dropped and does not overwrite message text or status`() {
        val chatMsgId = "own_chat_msg_1122334455667788"
        store.createChatPacketInvoker = { recipient, text ->
            MeshCoreBridge.CreatedPacket(chatMsgId, byteArrayOf(7, 8, 9))
        }

        assertTrue(store.sendChat("recipient_alice", "Original private text"))
        store.markSent(chatMsgId)

        // Hostile / spoofed packet arriving with same msgId and tampered text
        store.processIncomingInvoker = {
            """
            {
                "packet_type": "Chat",
                "sender_id": "attacker_eve",
                "msg_id": "$chatMsgId",
                "ttl": 7,
                "timestamp": 1700000000,
                "addressed_to_us": true,
                "plaintext": "Attacker replaced payload text!"
            }
            """.trimIndent()
        }

        store.processIncomingPacket(byteArrayOf(7, 8, 9))

        val afterHostile = store.getMessage(chatMsgId)
        assertNotNull(afterHostile)
        assertEquals("Sender must remain LOCAL_SENDER", MessageEntity.LOCAL_SENDER, afterHostile!!.senderId)
        assertTrue("isOutgoing must remain true", afterHostile.isOutgoing)
        assertEquals("Payload text must not be overwritten", "Original private text", afterHostile.payloadText)
        assertEquals("Status must not be mutated", MessageStatus.SENT, afterHostile.status)
    }

    @Test
    fun `markSent does not downgrade DELIVERED status in a concurrent write race`() {
        val msgId = "race_msg_11223344556677889900"
        store.createChatPacketInvoker = { _, _ ->
            MeshCoreBridge.CreatedPacket(msgId, byteArrayOf(1, 1, 1))
        }

        assertTrue(store.sendChat("peer", "Test race"))
        assertEquals(MessageStatus.QUEUED, store.getMessage(msgId)?.status)

        // ACK arrives and marks delivered first
        store.markDelivered(msgId)
        assertEquals(MessageStatus.DELIVERED, store.getMessage(msgId)?.status)

        // Late markSent completion callback must not downgrade DELIVERED to SENT
        store.markSent(msgId)
        assertEquals("markSent must not downgrade DELIVERED to SENT", MessageStatus.DELIVERED, store.getMessage(msgId)?.status)
    }
}

