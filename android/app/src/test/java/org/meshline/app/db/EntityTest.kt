package org.meshline.app.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageEntityTest {

    private fun message(sender: String) = MessageEntity(
        msgId = "m1",
        senderId = sender,
        recipientId = MessageEntity.BROADCAST_RECIPIENT,
        payloadText = "text",
        ttl = 8,
        timestampMillis = 0,
        packetType = "Chat",
        status = MessageStatus.QUEUED
    )

    @Test
    fun `messages from this device are outgoing`() {
        assertTrue(message(MessageEntity.LOCAL_SENDER).isOutgoing)
    }

    @Test
    fun `messages from a peer are not outgoing`() {
        assertFalse(message("a1b2c3d4e5f60718293a4b5c6d7e8f90").isOutgoing)
    }

    @Test
    fun `messages default to unencrypted so the ui never overstates security`() {
        assertFalse(message(MessageEntity.LOCAL_SENDER).encrypted)
    }
}

class ResourcePinEntityTest {

    private fun pin(expiresAtSecs: Long) = ResourcePinEntity(
        pinId = "p1",
        pinType = "WaterPoint",
        latitude = 12.97f,
        longitude = 77.59f,
        label = "tanker at gate 4",
        createdAtSecs = 1_000,
        expiresAtSecs = expiresAtSecs,
        creatorId = "abc123"
    )

    @Test
    fun `a pin past its expiry is expired`() {
        assertTrue(pin(expiresAtSecs = 2_000).isExpired(nowSecs = 2_001))
    }

    @Test
    fun `a pin is expired exactly at its expiry second`() {
        // Stale resource information in a disaster is worse than none, so the
        // boundary resolves against the pin.
        assertTrue(pin(expiresAtSecs = 2_000).isExpired(nowSecs = 2_000))
    }

    @Test
    fun `a live pin is not expired`() {
        assertFalse(pin(expiresAtSecs = 2_000).isExpired(nowSecs = 1_999))
    }

    @Test
    fun `pin retains the creator id needed to attribute it`() {
        assertEquals("abc123", pin(2_000).creatorId)
    }
}
