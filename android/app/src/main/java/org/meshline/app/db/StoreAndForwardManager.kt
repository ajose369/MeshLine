package org.meshline.app.db

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        // Pre-populate with initial crisis resource pins
        val samplePins = listOf(
            ResourcePinEntity("pin_1", "WaterPoint", 37.7749f, -122.4194f, "Clean Water Filtration Pump", System.currentTimeMillis(), System.currentTimeMillis() + 64800000, "pubkey_01", "sig_01", 5),
            ResourcePinEntity("pin_2", "Shelter", 37.7780f, -122.4220f, "Community Shelter (Gen Available)", System.currentTimeMillis(), System.currentTimeMillis() + 151200000, "pubkey_02", "sig_02", 12),
            ResourcePinEntity("pin_3", "MedicalStation", 37.7710f, -122.4150f, "First Aid Kit & Triage", System.currentTimeMillis(), System.currentTimeMillis() + 21600000, "pubkey_03", "sig_03", 3),
            ResourcePinEntity("pin_4", "Hazard", 37.7760f, -122.4120f, "Collapsed Bridge / Roadblock", System.currentTimeMillis(), System.currentTimeMillis() + 86400000, "pubkey_04", "sig_04", 8)
        )
        samplePins.forEach { pinStore[it.pinId] = it }
        _pinsFlow.value = pinStore.values.toList().sortedBy { it.createdAt }

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
