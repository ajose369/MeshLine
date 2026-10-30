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
    val encrypted: Boolean = false,
    /**
     * Set when this message belongs to a private group. Group messages are
     * addressed to a derived tag rather than to a node, so [recipientId] cannot
     * identify the conversation on its own.
     */
    val groupId: String? = null
) {
    val isOutgoing: Boolean get() = senderId == LOCAL_SENDER

    /** The conversation this message belongs in: a group, a peer, or the broadcast feed. */
    val conversationId: String
        get() = when {
            groupId != null -> groupId
            // Broadcast traffic is one shared feed regardless of direction, so
            // an incoming SOS must not open a conversation with its sender.
            recipientId == BROADCAST_RECIPIENT -> BROADCAST_RECIPIENT
            isOutgoing -> recipientId
            else -> senderId
        }

    companion object {
        const val LOCAL_SENDER = "me"
        const val BROADCAST_RECIPIENT = "all"
    }
}
