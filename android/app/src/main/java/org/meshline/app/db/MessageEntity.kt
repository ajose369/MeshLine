package org.meshline.app.db

data class MessageEntity(
    val msgId: String,
    val senderId: String,
    val recipientId: String,
    val payloadText: String,
    val ttl: Int,
    val timestamp: Long,
    val packetType: String,
    val status: String // QUEUED, RELAYED, DELIVERED_ACK
)
