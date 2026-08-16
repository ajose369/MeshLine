package org.meshline.app.db

/** Delivery state of a message this device originated or received. */
enum class MessageStatus {
    /** Waiting for a peer to carry it. */
    QUEUED,

    /** Handed to at least one peer. */
    SENT,

    /** The recipient returned a signed acknowledgement. */
    DELIVERED,

    /** Could not be built or sent; shown to the user as a failure. */
    FAILED
}

data class MessageEntity(
    val msgId: String,
    /** Mesh node id of the sender, or [LOCAL_SENDER] for our own messages. */
    val senderId: String,
    val recipientId: String,
    val payloadText: String,
    val ttl: Int,
    /** Milliseconds since epoch, for display. */
    val timestampMillis: Long,
    val packetType: String,
    val status: MessageStatus,
    /** True when the text was recovered from an authenticated encrypted session. */
    val encrypted: Boolean = false
) {
    val isOutgoing: Boolean get() = senderId == LOCAL_SENDER

    companion object {
        const val LOCAL_SENDER = "me"
        const val BROADCAST_RECIPIENT = "all"
    }
}
