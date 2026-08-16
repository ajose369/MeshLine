package org.meshline.app.db

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.meshline.app.bridge.MeshCoreBridge

/** A peer this device has actually heard from. */
data class PeerNodeEntity(
    val nodeId: String,
    val displayName: String,
    val rssiDbm: Int,
    val lastSeenMillis: Long,
    val transport: String,
    val hasSecureSession: Boolean = false
)

/**
 * Custody store for messages, pins, and peers.
 *
 * Everything in here originates from a packet the native core has already
 * authenticated, or from the local user. Nothing is seeded, invented, or
 * simulated: an emergency app that displays imaginary nearby responders or
 * fabricated shelter locations is actively harmful, so the stores start empty
 * and stay empty until real traffic arrives.
 */
class StoreAndForwardManager private constructor(private val context: Context) {

    private val messageStore = ConcurrentHashMap<String, MessageEntity>()
    private val pinStore = ConcurrentHashMap<String, ResourcePinEntity>()
    private val peerStore = ConcurrentHashMap<String, PeerNodeEntity>()
    private val outboundQueue = ConcurrentLinkedQueue<ByteArray>()

    private val _messagesFlow = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messagesFlow: StateFlow<List<MessageEntity>> = _messagesFlow.asStateFlow()

    private val _pinsFlow = MutableStateFlow<List<ResourcePinEntity>>(emptyList())
    val pinsFlow: StateFlow<List<ResourcePinEntity>> = _pinsFlow.asStateFlow()

    private val _peersFlow = MutableStateFlow<List<PeerNodeEntity>>(emptyList())
    val peersFlow: StateFlow<List<PeerNodeEntity>> = _peersFlow.asStateFlow()

    companion object {
        private const val TAG = "MeshStore"

        /** Peers unheard from for this long stop being shown as in range. */
        private const val PEER_STALE_MILLIS = 5 * 60 * 1000L

        @Volatile
        private var INSTANCE: StoreAndForwardManager? = null

        fun getInstance(context: Context): StoreAndForwardManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StoreAndForwardManager(context.applicationContext)
                    .also { INSTANCE = it }
            }
    }

    // -----------------------------------------------------------------------
    // Outbound
    // -----------------------------------------------------------------------

    /**
     * Builds, records, and queues a public SOS.
     * Returns false when the core is unavailable, in which case nothing is
     * queued and the caller must tell the user the SOS did not send.
     */
    fun sendSos(text: String, latitude: Float, longitude: Float): Boolean {
        val packet = MeshCoreBridge.createSos(text, latitude, longitude)
        if (packet == null) {
            Log.e(TAG, "SOS could not be created; mesh core unavailable.")
            recordLocalMessage(text, "PublicSos", MessageStatus.FAILED)
            return false
        }
        outboundQueue.add(packet)
        recordLocalMessage(text, "PublicSos", MessageStatus.QUEUED)
        return true
    }

    /**
     * Builds, records, and queues an encrypted chat message.
     * Returns false when no secure session exists with the recipient; the
     * message is never downgraded to plaintext.
     */
    fun sendChat(recipientIdHex: String, text: String): Boolean {
        val packet = MeshCoreBridge.createChat(recipientIdHex, text)
        if (packet == null) {
            Log.w(TAG, "Chat not sent: no secure session with $recipientIdHex.")
            recordLocalMessage(text, "Chat", MessageStatus.FAILED, recipientIdHex, encrypted = true)
            return false
        }
        outboundQueue.add(packet)
        recordLocalMessage(text, "Chat", MessageStatus.QUEUED, recipientIdHex, encrypted = true)
        return true
    }

    /** Starts a Noise handshake so chat with this peer becomes possible. */
    fun startHandshake(peerIdHex: String): Boolean {
        val packet = MeshCoreBridge.beginHandshake(peerIdHex) ?: return false
        outboundQueue.add(packet)
        return true
    }

    /** Creates and queues a signed resource pin. Returns false on failure. */
    fun createAndBroadcastPin(
        label: String,
        latitude: Float,
        longitude: Float,
        type: String,
        expiresInSecs: Long
    ): Boolean {
        val pinId = UUID.randomUUID()
        val pinIdBytes = ByteArray(16)
        ByteBuffer.wrap(pinIdBytes).apply {
            putLong(pinId.mostSignificantBits)
            putLong(pinId.leastSignificantBits)
        }

        val packet = MeshCoreBridge.createPin(
            pinIdBytes,
            pinTypeValue(type),
            latitude,
            longitude,
            label,
            expiresInSecs
        )
        if (packet == null) {
            Log.e(TAG, "Pin could not be created; mesh core unavailable.")
            return false
        }

        outboundQueue.add(packet)
        syncResourcePins()
        return true
    }

    /** Drains everything waiting to be transmitted. */
    fun drainOutbound(): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        while (true) {
            packets.add(outboundQueue.poll() ?: break)
        }
        if (packets.isNotEmpty()) {
            markQueuedAsSent()
        }
        return packets
    }

    fun queueRawPacket(packet: ByteArray) {
        outboundQueue.add(packet)
    }

    fun hasPendingOutbound(): Boolean = outboundQueue.isNotEmpty()

    // -----------------------------------------------------------------------
    // Inbound
    // -----------------------------------------------------------------------

    /**
     * Hands a raw frame to the native core for verification and acts on the
     * result. Frames that fail authentication are dropped silently; they are
     * noise or hostile, and either way nothing about them should reach the UI.
     */
    fun processIncomingPacket(rawPacket: ByteArray, transport: String = "Bluetooth LE") {
        val json = MeshCoreBridge.processIncoming(rawPacket)
        if (json == null) {
            // Not an error worth surfacing: unverifiable traffic is expected on
            // an open radio and is simply not ours to act on.
            return
        }

        try {
            val obj = JSONObject(json)
            val packetType = obj.getString("packet_type")
            val senderId = obj.getString("sender_id")
            val msgId = obj.getString("msg_id")
            val ttl = obj.getInt("ttl")
            val timestampMillis = obj.getLong("timestamp") * 1000L
            val addressedToUs = obj.getBoolean("addressed_to_us")

            // Anything that needs to go back out.
            obj.optStringOrNull("relay_packet_hex")?.let { outboundQueue.add(it.hexToBytes()) }
            obj.optStringOrNull("ack_packet_hex")?.let { outboundQueue.add(it.hexToBytes()) }
            obj.optStringOrNull("handshake_reply_hex")?.let { outboundQueue.add(it.hexToBytes()) }

            observePeer(senderId, transport)

            when (packetType) {
                "PublicSos" -> obj.optStringOrNull("sos_text")?.let { text ->
                    upsertMessage(
                        MessageEntity(
                            msgId = msgId,
                            senderId = senderId,
                            recipientId = MessageEntity.BROADCAST_RECIPIENT,
                            payloadText = text,
                            ttl = ttl,
                            timestampMillis = timestampMillis,
                            packetType = packetType,
                            status = MessageStatus.SENT,
                            encrypted = false
                        )
                    )
                }

                "Chat" -> if (addressedToUs) {
                    obj.optStringOrNull("plaintext")?.let { text ->
                        upsertMessage(
                            MessageEntity(
                                msgId = msgId,
                                senderId = senderId,
                                recipientId = MessageEntity.LOCAL_SENDER,
                                payloadText = text,
                                ttl = ttl,
                                timestampMillis = timestampMillis,
                                packetType = packetType,
                                status = MessageStatus.SENT,
                                encrypted = true
                            )
                        )
                    }
                }

                "Ack" -> if (addressedToUs) {
                    markDelivered(msgId)
                }

                "ResourcePin" -> syncResourcePins()

                "NoiseHandshake" -> refreshPeerSessionState(senderId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Malformed result from mesh core.", e)
        }
    }

    // -----------------------------------------------------------------------
    // Pins
    // -----------------------------------------------------------------------

    fun syncResourcePins() {
        val jsonStr = MeshCoreBridge.activePinsJson() ?: return
        try {
            val array = JSONArray(jsonStr)
            val seen = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val entity = ResourcePinEntity(
                    pinId = obj.getString("pin_id"),
                    pinType = obj.getString("pin_type"),
                    latitude = obj.getDouble("latitude").toFloat(),
                    longitude = obj.getDouble("longitude").toFloat(),
                    label = obj.getString("label"),
                    createdAtSecs = obj.getLong("created_at"),
                    expiresAtSecs = obj.getLong("expires_at"),
                    creatorId = obj.getString("creator_id")
                )
                pinStore[entity.pinId] = entity
                seen += entity.pinId
            }
            // The core is authoritative: pins it dropped have expired.
            pinStore.keys.retainAll(seen)
            publishPins()
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse pins from mesh core.", e)
        }
    }

    fun getActiveResourcePins(): List<ResourcePinEntity> {
        syncResourcePins()
        return _pinsFlow.value
    }

    private fun publishPins() {
        val nowSecs = System.currentTimeMillis() / 1000
        _pinsFlow.value = pinStore.values
            .filterNot { it.isExpired(nowSecs) }
            .sortedByDescending { it.createdAtSecs }
    }

    // -----------------------------------------------------------------------
    // Peers
    // -----------------------------------------------------------------------

    /** Records that we heard an authenticated packet from this node. */
    fun observePeer(nodeId: String, transport: String, rssiDbm: Int? = null) {
        val existing = peerStore[nodeId]
        peerStore[nodeId] = PeerNodeEntity(
            nodeId = nodeId,
            displayName = "Node ${nodeId.take(6)}",
            rssiDbm = rssiDbm ?: existing?.rssiDbm ?: 0,
            lastSeenMillis = System.currentTimeMillis(),
            transport = transport,
            hasSecureSession = MeshCoreBridge.hasSession(nodeId)
        )
        publishPeers()
    }

    private fun refreshPeerSessionState(nodeId: String) {
        peerStore[nodeId]?.let {
            peerStore[nodeId] = it.copy(hasSecureSession = MeshCoreBridge.hasSession(nodeId))
            publishPeers()
        }
    }

    fun prunePeers() {
        val cutoff = System.currentTimeMillis() - PEER_STALE_MILLIS
        val before = peerStore.size
        peerStore.values.removeIf { it.lastSeenMillis < cutoff }
        if (peerStore.size != before) publishPeers()
    }

    private fun publishPeers() {
        _peersFlow.value = peerStore.values.sortedByDescending { it.lastSeenMillis }
    }

    // -----------------------------------------------------------------------
    // Messages
    // -----------------------------------------------------------------------

    private fun recordLocalMessage(
        text: String,
        packetType: String,
        status: MessageStatus,
        recipientId: String = MessageEntity.BROADCAST_RECIPIENT,
        encrypted: Boolean = false
    ) {
        upsertMessage(
            MessageEntity(
                msgId = UUID.randomUUID().toString(),
                senderId = MessageEntity.LOCAL_SENDER,
                recipientId = recipientId,
                payloadText = text,
                ttl = 8,
                timestampMillis = System.currentTimeMillis(),
                packetType = packetType,
                status = status,
                encrypted = encrypted
            )
        )
    }

    private fun upsertMessage(message: MessageEntity) {
        messageStore[message.msgId] = message
        publishMessages()
    }

    private fun markQueuedAsSent() {
        var changed = false
        messageStore.forEach { (id, msg) ->
            if (msg.status == MessageStatus.QUEUED) {
                messageStore[id] = msg.copy(status = MessageStatus.SENT)
                changed = true
            }
        }
        if (changed) publishMessages()
    }

    private fun markDelivered(ackedMsgId: String) {
        messageStore[ackedMsgId]?.let {
            messageStore[ackedMsgId] = it.copy(status = MessageStatus.DELIVERED)
            publishMessages()
        }
    }

    private fun publishMessages() {
        _messagesFlow.value = messageStore.values.sortedBy { it.timestampMillis }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun pinTypeValue(type: String): Int = when (type) {
        "WaterPoint" -> 1
        "Shelter" -> 2
        "MedicalStation" -> 3
        "Hazard" -> 4
        "Roadblock" -> 5
        else -> 1
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key).takeIf { it.isNotEmpty() }
    }

    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { i ->
            substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
}
