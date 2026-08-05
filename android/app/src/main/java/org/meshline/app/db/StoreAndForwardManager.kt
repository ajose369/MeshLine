package org.meshline.app.db

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.meshline.app.bridge.MeshCoreBridge
import java.util.UUID

data class PeerNodeEntity(
    val nodeId: String,
    val deviceModel: String,
    val rssiDbm: Int,
    val hopDistance: Int,
    val lastSeenSec: Int,
    val transport: String
)

class StoreAndForwardManager private constructor(private val context: Context) {

    private val messageStore = ConcurrentHashMap<String, MessageEntity>()
    private val pinStore = ConcurrentHashMap<String, ResourcePinEntity>()
    private val peerStore = ConcurrentHashMap<String, PeerNodeEntity>()
    private val rawPacketsQueue = ConcurrentLinkedQueue<ByteArray>()

    private val _messagesFlow = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messagesFlow: StateFlow<List<MessageEntity>> = _messagesFlow.asStateFlow()

    private val _pinsFlow = MutableStateFlow<List<ResourcePinEntity>>(emptyList())
    val pinsFlow: StateFlow<List<ResourcePinEntity>> = _pinsFlow.asStateFlow()

    private val _peersFlow = MutableStateFlow<List<PeerNodeEntity>>(emptyList())
    val peersFlow: StateFlow<List<PeerNodeEntity>> = _peersFlow.asStateFlow()

    companion object {
        @Volatile
        private var INSTANCE: StoreAndForwardManager? = null

        fun getInstance(context: Context): StoreAndForwardManager {
            return INSTANCE ?: synchronized(this) {
                val instance = StoreAndForwardManager(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }

    init {
        if (MeshCoreBridge.isNativeReady()) {
            // Seed native store with some sample pins
            val samplePinsToSeed = listOf(
                Triple("pin_1_water_pmp", 1, "Clean Water Filtration Pump"),
                Triple("pin_2_shelter_c", 2, "Community Shelter (Gen Available)"),
                Triple("pin_3_medical_t", 3, "First Aid Kit & Triage"),
                Triple("pin_4_hazard_br", 4, "Collapsed Bridge / Roadblock")
            )
            for (item in samplePinsToSeed) {
                val pinIdBytes = ByteArray(16)
                val uuid = UUID.nameUUIDFromBytes(item.first.toByteArray())
                val buffer = java.nio.ByteBuffer.wrap(pinIdBytes)
                buffer.putLong(uuid.mostSignificantBits)
                buffer.putLong(uuid.leastSignificantBits)
                MeshCoreBridge.createSignedPinPacketSafe(
                    pinIdBytes,
                    item.second,
                    37.7749f + (Math.random() - 0.5).toFloat() * 0.01f,
                    -122.4194f + (Math.random() - 0.5).toFloat() * 0.01f,
                    item.third,
                    86400
                )
            }
            syncResourcePins()
        } else {
            // Pre-populate with initial crisis resource pins in Kotlin memory fallback
            val samplePins = listOf(
                ResourcePinEntity("pin_1", "WaterPoint", 37.7749f, -122.4194f, "Clean Water Filtration Pump", System.currentTimeMillis(), System.currentTimeMillis() + 64800000, "pubkey_01", "sig_01", 5),
                ResourcePinEntity("pin_2", "Shelter", 37.7780f, -122.4220f, "Community Shelter (Gen Available)", System.currentTimeMillis(), System.currentTimeMillis() + 151200000, "pubkey_02", "sig_02", 12),
                ResourcePinEntity("pin_3", "MedicalStation", 37.7710f, -122.4150f, "First Aid Kit & Triage", System.currentTimeMillis(), System.currentTimeMillis() + 21600000, "pubkey_03", "sig_03", 3),
                ResourcePinEntity("pin_4", "Hazard", 37.7760f, -122.4120f, "Collapsed Bridge / Roadblock", System.currentTimeMillis(), System.currentTimeMillis() + 86400000, "pubkey_04", "sig_04", 8)
            )
            samplePins.forEach { pinStore[it.pinId] = it }
            _pinsFlow.value = pinStore.values.toList().sortedBy { it.createdAt }
        }

        // Pre-populate with sample messages for active chat visualization
        val sampleMessages = listOf(
            MessageEntity("msg_1", "Rescue Commander #4a19", "All", "All teams check in. Sector 3 bridge is reported flooded.", 8, System.currentTimeMillis() - 600000, "Chat", "RELAYED ACK"),
            MessageEntity("msg_2", "Me", "All", "Unit 2 at Community Center shelter. 6 medical kits & clean water available.", 8, System.currentTimeMillis() - 300000, "Chat", "Delivered (1 hop)"),
            MessageEntity("msg_3", "Node #8fa2", "All", "Confirmed water filtration pump operational at latitude 37.7749", 8, System.currentTimeMillis() - 100000, "Chat", "Relayed ACK")
        )
        sampleMessages.forEach { messageStore[it.msgId] = it }
        _messagesFlow.value = messageStore.values.toList().sortedBy { it.timestamp }

        // Pre-populate with sample peers
        val samplePeers = listOf(
            PeerNodeEntity("a1b2", "Pixel 7 Pro", -62, 1, 2, "Bluetooth LE"),
            PeerNodeEntity("c3d4", "Galaxy S22", -78, 1, 5, "Bluetooth LE"),
            PeerNodeEntity("e5f6", "Heltec V3 Bridge", -45, 1, 1, "LoRa 915MHz"),
            PeerNodeEntity("7890", "OnePlus 11 Relay", -91, 3, 18, "Wi-Fi Direct")
        )
        samplePeers.forEach { peerStore[it.nodeId] = it }
        _peersFlow.value = peerStore.values.toList().sortedByDescending { it.rssiDbm }
    }

    fun syncResourcePins() {
        val jsonStr = MeshCoreBridge.getActivePinsJsonSafe() ?: return
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val pinId = obj.getString("pin_id")
                val pinType = obj.getString("pin_type")
                val latitude = obj.getDouble("latitude").toFloat()
                val longitude = obj.getDouble("longitude").toFloat()
                val label = obj.getString("label")
                val createdAt = obj.getLong("created_at")
                val expiresAt = obj.getLong("expires_at")
                val creatorPubkey = obj.getString("creator_pubkey")
                val signatureHex = obj.getString("signature_hex")
                val verifiedCount = obj.getInt("verified_count")

                val entity = ResourcePinEntity(
                    pinId = pinId,
                    pinType = pinType,
                    latitude = latitude,
                    longitude = longitude,
                    label = label,
                    createdAt = createdAt,
                    expiresAt = expiresAt,
                    creatorPubkey = creatorPubkey,
                    signatureHex = signatureHex,
                    verifiedCount = verifiedCount
                )
                pinStore[pinId] = entity
            }
            _pinsFlow.value = pinStore.values.toList().sortedBy { it.createdAt }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun createAndBroadcastSignedPin(
        label: String,
        latitude: Float,
        longitude: Float,
        type: String,
        expiresInSecs: Long
    ): ByteArray? {
        val pinId = UUID.randomUUID()
        val pinIdBytes = ByteArray(16)
        val buffer = java.nio.ByteBuffer.wrap(pinIdBytes)
        buffer.putLong(pinId.mostSignificantBits)
        buffer.putLong(pinId.leastSignificantBits)

        val pinTypeVal = when (type) {
            "WaterPoint" -> 1
            "Shelter" -> 2
            "MedicalStation" -> 3
            "Hazard" -> 4
            "Roadblock" -> 5
            else -> 1
        }

        val packetBytes = MeshCoreBridge.createSignedPinPacketSafe(
            pinIdBytes,
            pinTypeVal,
            latitude,
            longitude,
            label,
            expiresInSecs
        )

        syncResourcePins()

        val newSosMsg = MessageEntity(
            msgId = pinId.toString(),
            senderId = "Me",
            recipientId = "All",
            payloadText = "Resource Pin: $label ($type)",
            ttl = 8,
            timestamp = System.currentTimeMillis(),
            packetType = "ResourcePin",
            status = "QUEUED"
        )
        queueMessage(newSosMsg)

        return packetBytes
    }

    fun queueRawPacket(packetBytes: ByteArray) {
        rawPacketsQueue.add(packetBytes)
    }

    fun getPendingPacketsForTx(): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        
        // 1. Drain raw packets queue (forwarded/relayed packets)
        while (true) {
            val p = rawPacketsQueue.poll() ?: break
            packets.add(p)
        }
        
        // 2. Generate packets for locally queued messages
        val queuedMessages = messageStore.values.filter { it.status == "QUEUED" }
        for (msg in queuedMessages) {
            val bytes = getPacketBytes(msg)
            if (bytes != null) {
                packets.add(bytes)
                // Update status to RELAYED so we don't send it again
                messageStore[msg.msgId] = msg.copy(status = "RELAYED")
            }
        }
        
        _messagesFlow.value = messageStore.values.toList().sortedBy { it.timestamp }
        return packets
    }

    private fun getPacketBytes(msg: MessageEntity): ByteArray? {
        return if (MeshCoreBridge.isNativeReady()) {
            when (msg.packetType) {
                "PublicSos" -> MeshCoreBridge.createPublicSosSafe(msg.payloadText, 37.7749f, -122.4194f)
                "Chat" -> MeshCoreBridge.createChatPacketSafe(msg.payloadText, msg.recipientId)
                "ResourcePin" -> {
                    val pin = pinStore[msg.msgId]
                    if (pin != null) {
                        val pinIdBytes = UUID.fromString(pin.pinId).let { uuid ->
                            val bytes = ByteArray(16)
                            val buf = java.nio.ByteBuffer.wrap(bytes)
                            buf.putLong(uuid.mostSignificantBits)
                            buf.putLong(uuid.leastSignificantBits)
                            bytes
                        }
                        val typeVal = when (pin.pinType) {
                            "WaterPoint" -> 1
                            "Shelter" -> 2
                            "MedicalStation" -> 3
                            "Hazard" -> 4
                            "Roadblock" -> 5
                            else -> 1
                        }
                        MeshCoreBridge.createSignedPinPacketSafe(
                            pinIdBytes,
                            typeVal,
                            pin.latitude,
                            pin.longitude,
                            pin.label,
                            (pin.expiresAt - pin.createdAt) / 1000
                        )
                    } else null
                }
                else -> null
            }
        } else {
            // Fallback modes
            when (msg.packetType) {
                "PublicSos" -> MeshCoreBridge.createPublicSosFallback(msg.payloadText, 37.7749f, -122.4194f)
                "Chat" -> "CHAT_V1|${msg.msgId}|${msg.senderId}|${msg.recipientId}|${msg.payloadText}|${msg.ttl}|${msg.timestamp}".toByteArray(Charsets.UTF_8)
                "ResourcePin" -> {
                    val pin = pinStore[msg.msgId]
                    if (pin != null) {
                        val pinIdBytes = UUID.fromString(pin.pinId).let { uuid ->
                            val bytes = ByteArray(16)
                            val buf = java.nio.ByteBuffer.wrap(bytes)
                            buf.putLong(uuid.mostSignificantBits)
                            buf.putLong(uuid.leastSignificantBits)
                            bytes
                        }
                        val typeVal = when (pin.pinType) {
                            "WaterPoint" -> 1
                            "Shelter" -> 2
                            "MedicalStation" -> 3
                            "Hazard" -> 4
                            "Roadblock" -> 5
                            else -> 1
                        }
                        MeshCoreBridge.createSignedPinPacketFallback(
                            pinIdBytes,
                            typeVal,
                            pin.latitude,
                            pin.longitude,
                            pin.label,
                            (pin.expiresAt - pin.createdAt) / 1000
                        )
                    } else null
                }
                else -> null
            }
        }
    }

    fun processIncomingPacket(rawPacket: ByteArray): ByteArray? {
        val result = if (MeshCoreBridge.isNativeReady()) {
            val forwardBytes = MeshCoreBridge.processIncomingPacketSafe(rawPacket)
            if (forwardBytes != null) {
                queueRawPacket(forwardBytes)
            }
            
            // Parse the packet to display in UI if it is a Chat or SOS packet
            val jsonStr = MeshCoreBridge.parsePacketJsonSafe(rawPacket)
            if (jsonStr != null) {
                try {
                    val obj = JSONObject(jsonStr)
                    val msgId = obj.getString("msg_id")
                    val senderId = obj.getString("sender_id").take(6)
                    val recipientId = obj.getString("recipient_id")
                    val packetType = obj.getString("packet_type")
                    val ttl = obj.getInt("ttl")
                    val timestamp = obj.getLong("timestamp") * 1000
                    val payloadText = obj.getString("payload_text")
                    
                    if (packetType == "Chat" || packetType == "PublicSos" || packetType == "PrivateSos") {
                        if (!messageStore.containsKey(msgId)) {
                            val entity = MessageEntity(
                                msgId = msgId,
                                senderId = "Node #$senderId",
                                recipientId = recipientId,
                                payloadText = payloadText,
                                ttl = ttl,
                                timestamp = timestamp,
                                packetType = packetType,
                                status = "RELAYED"
                            )
                            queueMessage(entity)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            syncResourcePins()
            forwardBytes
        } else {
            // Kotlin Fallback mode
            val str = String(rawPacket, Charsets.UTF_8)
            if (str.startsWith("SOS_V1|") || str.startsWith("CHAT_V1|")) {
                val parts = str.split("|")
                val type = if (parts[0] == "SOS_V1") "PublicSos" else "Chat"
                val id = parts.getOrNull(1) ?: UUID.randomUUID().toString()
                if (!messageStore.containsKey(id)) {
                    val sender = parts.getOrNull(2) ?: "Peer"
                    val recipient = if (type == "PublicSos") "All" else (parts.getOrNull(3) ?: "All")
                    val msg = if (type == "PublicSos") parts.getOrNull(2) ?: "" else (parts.getOrNull(4) ?: "")
                    val ts = parts.lastOrNull()?.toLongOrNull() ?: System.currentTimeMillis()
                    
                    val entity = MessageEntity(
                        msgId = id,
                        senderId = if (sender == "Me") "Me" else "Node #$sender",
                        recipientId = recipient,
                        payloadText = msg,
                        ttl = 7,
                        timestamp = ts,
                        packetType = type,
                        status = "RELAYED"
                    )
                    queueMessage(entity)
                    queueRawPacket(rawPacket)
                }
            } else if (str.startsWith("PIN_V1|")) {
                val parts = str.split("|")
                val id = parts.getOrNull(1) ?: ""
                if (!pinStore.containsKey(id)) {
                    val type = parts.getOrNull(2) ?: "WaterPoint"
                    val lat = parts.getOrNull(3)?.toFloatOrNull() ?: 0.0f
                    val lon = parts.getOrNull(4)?.toFloatOrNull() ?: 0.0f
                    val label = parts.getOrNull(5) ?: ""
                    val tsCreated = parts.getOrNull(6)?.toLongOrNull() ?: System.currentTimeMillis()
                    val tsExpires = parts.getOrNull(7)?.toLongOrNull() ?: (System.currentTimeMillis() + 86400000)
                    
                    val entity = ResourcePinEntity(id, type, lat, lon, label, tsCreated, tsExpires, "peer_pubkey", "peer_sig", 1)
                    upsertResourcePin(entity)
                    queueRawPacket(rawPacket)
                }
            }
            null
        }
        return result
    }

    fun queueMessage(message: MessageEntity) {
        messageStore[message.msgId] = message
        _messagesFlow.value = messageStore.values.toList().sortedBy { it.timestamp }
    }

    fun getPendingMessagesForRelay(): List<MessageEntity> {
        return messageStore.values.filter { it.status == "QUEUED" && it.ttl > 0 }
    }

    fun markMessageDelivered(msgId: String) {
        messageStore[msgId]?.let {
            messageStore[msgId] = it.copy(status = "DELIVERED_ACK")
            _messagesFlow.value = messageStore.values.toList().sortedBy { it.timestamp }
        }
    }

    fun upsertResourcePin(pin: ResourcePinEntity) {
        pinStore[pin.pinId] = pin
        _pinsFlow.value = pinStore.values.toList().sortedBy { it.createdAt }
    }

    fun getActiveResourcePins(): List<ResourcePinEntity> {
        syncResourcePins()
        val now = System.currentTimeMillis()
        return pinStore.values.filter { it.expiresAt > now }
    }

    fun pruneExpiredPackets() {
        val now = System.currentTimeMillis()
        val removed = pinStore.values.removeIf { it.expiresAt <= now }
        if (removed) {
            _pinsFlow.value = pinStore.values.toList().sortedBy { it.createdAt }
        }
    }

    fun upsertPeer(peer: PeerNodeEntity) {
        peerStore[peer.nodeId] = peer
        _peersFlow.value = peerStore.values.toList().sortedByDescending { it.rssiDbm }
    }

    fun getActivePeers(): List<PeerNodeEntity> {
        return peerStore.values.toList()
    }
}
