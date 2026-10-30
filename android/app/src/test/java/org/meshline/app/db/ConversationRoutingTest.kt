package org.meshline.app.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

private const val ALICE = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
private const val BOB = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0"
private const val GROUP = "9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f9f"

private fun message(
    msgId: String = "m1",
    sender: String = ALICE,
    recipient: String = MessageEntity.BROADCAST_RECIPIENT,
    groupId: String? = null,
    packetType: String = "Chat"
) = MessageEntity(
    msgId = msgId,
    senderId = sender,
    recipientId = recipient,
    payloadText = "text",
    ttl = 8,
    timestampMillis = 1_000,
    packetType = packetType,
    status = MessageStatus.SENT,
    encrypted = groupId != null,
    groupId = groupId
)

/**
 * Routing a message to the wrong conversation is a privacy failure, not a
 * cosmetic one: a group message shown in the public feed, or a private reply
 * filed under a stranger, puts text in front of an audience the sender did not
 * choose.
 */
class ConversationRoutingTest {

    @Test
    fun `a group message belongs to its group whoever sent it`() {
        assertEquals(GROUP, message(groupId = GROUP, packetType = "GroupChat").conversationId)
        assertEquals(
            GROUP,
            message(
                sender = MessageEntity.LOCAL_SENDER,
                groupId = GROUP,
                packetType = "GroupChat"
            ).conversationId
        )
    }

    @Test
    fun `an incoming private message is filed under its sender`() {
        assertEquals(
            ALICE,
            message(sender = ALICE, recipient = MessageEntity.LOCAL_SENDER).conversationId
        )
    }

    @Test
    fun `an outgoing private message is filed under its recipient`() {
        assertEquals(
            BOB,
            message(sender = MessageEntity.LOCAL_SENDER, recipient = BOB).conversationId
        )
    }

    @Test
    fun `broadcast traffic stays in the public feed in both directions`() {
        // An incoming SOS must not open a private conversation with whoever
        // happened to send it.
        assertEquals(
            MessageEntity.BROADCAST_RECIPIENT,
            message(sender = ALICE, packetType = "PublicSos").conversationId
        )
        assertEquals(
            MessageEntity.BROADCAST_RECIPIENT,
            message(sender = MessageEntity.LOCAL_SENDER, packetType = "PublicSos").conversationId
        )
    }

    @Test
    fun `a group message never lands in the public feed`() {
        // Group messages carry the broadcast recipient on the wire, because they
        // are addressed to a derived tag rather than to a node. Only the group
        // id distinguishes them.
        val groupMessage = message(groupId = GROUP, packetType = "GroupChat")
        assertTrue(groupMessage.recipientId == MessageEntity.BROADCAST_RECIPIENT)
        assertEquals(GROUP, groupMessage.conversationId)
    }
}

/**
 * The message history is written to disk, so its encoding has to survive a round
 * trip exactly. A dropped `groupId` would silently move a group's history into
 * the public feed on the next launch.
 */
@RunWith(RobolectricTestRunner::class)
class EncryptedMessageStoreCodecTest {

    @Test
    fun `messages round trip through the stored form`() {
        val original = listOf(
            message(msgId = "m1", groupId = GROUP, packetType = "GroupChat"),
            message(msgId = "m2", sender = MessageEntity.LOCAL_SENDER, recipient = BOB),
            message(msgId = "m3", packetType = "PublicSos")
        )

        val restored = EncryptedMessageStore.parse(EncryptedMessageStore.serialize(original))

        assertEquals(original.size, restored.size)
        original.zip(restored).forEach { (before, after) ->
            assertEquals(before, after)
        }
    }

    @Test
    fun `a message with no group survives as one with no group`() {
        val restored = EncryptedMessageStore.parse(
            EncryptedMessageStore.serialize(listOf(message()))
        )
        assertNull(restored.single().groupId)
    }

    @Test
    fun `conversation routing is preserved across a restart`() {
        val stored = listOf(
            message(msgId = "g", groupId = GROUP, packetType = "GroupChat"),
            message(msgId = "d", sender = MessageEntity.LOCAL_SENDER, recipient = BOB)
        )
        val restored = EncryptedMessageStore.parse(EncryptedMessageStore.serialize(stored))

        assertEquals(GROUP, restored.first { it.msgId == "g" }.conversationId)
        assertEquals(BOB, restored.first { it.msgId == "d" }.conversationId)
    }

    @Test
    fun `an empty history round trips without error`() {
        assertTrue(EncryptedMessageStore.parse(EncryptedMessageStore.serialize(emptyList())).isEmpty())
    }

    @Test
    fun `an unrecognised status falls back rather than throwing`() {
        // A file written by a newer build must not make the app unusable.
        val json = """[{"msg_id":"m1","sender_id":"$ALICE","recipient_id":"all",
            "payload_text":"t","ttl":8,"timestamp_millis":1,"packet_type":"Chat",
            "status":"SOMETHING_NEW","encrypted":true,"group_id":null}]"""
        assertEquals(MessageStatus.SENT, EncryptedMessageStore.parse(json).single().status)
    }
}
