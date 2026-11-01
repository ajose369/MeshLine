package org.meshline.app.db

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.meshline.app.bridge.MeshCoreBridge
import org.meshline.app.security.SecureStateStore
import org.meshline.app.ui.radar.PeerObservation
import org.meshline.app.ui.radar.RadarProjection

enum class PeerReachability {
    DIRECT,
    RELAYED
}

/** A peer this device has actually heard from. */
data class PeerNodeEntity(
    val nodeId: String,
    val displayName: String,
    val rssiDbm: Int? = null,
    val lastSeenMillis: Long,
    val transport: String,
    val hasSecureSession: Boolean = false,
    /**
     * True once the user has compared safety numbers with this peer in person.
     * An encrypted session proves the key is consistent; only this proves the
     * person holding it is who you think.
     */
    val isVerified: Boolean = false,
    val reachability: PeerReachability = PeerReachability.RELAYED,
    val hopCount: Int? = null,
    val lastDirectMillis: Long? = null
) {
    val isDirect: Boolean get() = reachability == PeerReachability.DIRECT
}

/** The outcome of a membership change, for a UI that must not overstate it. */
data class GroupRekeyReport(
    val invitesSent: Int,
    /** Members with no pairwise session, who did not receive the new key. */
    val unreachable: List<String>
) {
    val isComplete: Boolean get() = unreachable.isEmpty()
}

/**
 * A packet retained in local custody for store-and-forward mesh propagation.
 *
 * Packets stay in local custody independently of individual BLE connections.
 * Bounded fan-out ensures each connected neighbour receives the packet once,
 * while preventing packets from being handed back to the neighbour that supplied them.
 */
data class CustodyPacket(
    val packetId: String,
    val bytes: ByteArray,
    val sourceAddress: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val expiryMillis: Long = createdAtMillis + StoreAndForwardManager.DEFAULT_CUSTODY_TTL_MILLIS,
    val deliveredPeers: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis >= expiryMillis

    fun isEligibleForPeer(peerAddress: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (isExpired(nowMillis)) return false
        val normalized = peerAddress.uppercase()
        if (sourceAddress != null && sourceAddress.uppercase() == normalized) return false
        return !deliveredPeers.any { it.uppercase() == normalized }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustodyPacket) return false
        return packetId == other.packetId
    }

    override fun hashCode(): Int = packetId.hashCode()
}

internal fun packetHash(bytes: ByteArray): String {
    val md = MessageDigest.getInstance("SHA-256")
    val digest = md.digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

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
    private val groupStore = ConcurrentHashMap<String, GroupEntity>()
    private val custodyStore = ConcurrentHashMap<String, CustodyPacket>()
    private val custodyOrder = ConcurrentLinkedQueue<String>()

    private val _messagesFlow = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messagesFlow: StateFlow<List<MessageEntity>> = _messagesFlow.asStateFlow()

    private val _pinsFlow = MutableStateFlow<List<ResourcePinEntity>>(emptyList())
    val pinsFlow: StateFlow<List<ResourcePinEntity>> = _pinsFlow.asStateFlow()

    private val _peersFlow = MutableStateFlow<List<PeerNodeEntity>>(emptyList())
    val peersFlow: StateFlow<List<PeerNodeEntity>> = _peersFlow.asStateFlow()

    private val _transportActiveFlow = MutableStateFlow(false)
    val transportActiveFlow: StateFlow<Boolean> = _transportActiveFlow.asStateFlow()

    fun setTransportActive(active: Boolean) {
        _transportActiveFlow.value = active
    }

    private val _groupsFlow = MutableStateFlow<List<GroupEntity>>(emptyList())
    val groupsFlow: StateFlow<List<GroupEntity>> = _groupsFlow.asStateFlow()

    /**
     * Persistence runs off the caller's thread. Writing the message history
     * involves an AES operation and a file rewrite, and doing that inline would
     * put disk I/O on whichever thread happened to receive a packet.
     */
    private val persistExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "mesh-persist").apply { isDaemon = true }
    }
    private val persistPending = AtomicBoolean(false)

    init {
        // Whatever the core restored is authoritative for groups and sessions;
        // the message history is ours.
        messageStore.putAll(EncryptedMessageStore.load(context).associateBy { it.msgId })
        publishMessages()
        syncGroups()
    }

    internal var processIncomingInvoker: (ByteArray) -> String? =
        MeshCoreBridge::processIncoming

    companion object {
        private const val TAG = "MeshStore"

        /** Peers unheard from for this long stop being shown as in range. */
        private const val PEER_STALE_MILLIS = 5 * 60 * 1000L

        /** Default custody time-to-live for packets held for mesh propagation (10 minutes). */
        const val DEFAULT_CUSTODY_TTL_MILLIS = 10 * 60 * 1000L

        /** Maximum number of packets retained in custody. */
        const val MAX_CUSTODY_PACKETS = 256

        @Volatile
        private var INSTANCE: StoreAndForwardManager? = null

        fun getInstance(context: Context): StoreAndForwardManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: StoreAndForwardManager(context.applicationContext)
                    .also { INSTANCE = it }
            }

        @androidx.annotation.VisibleForTesting
        fun resetForTesting() {
            INSTANCE = null
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
    /** Nulls mean the device had no fix; the packet then carries no location. */
    fun sendSos(
        text: String,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracyMeters: Float? = null,
    ): Boolean {
        val packet = MeshCoreBridge.createSos(text, latitude, longitude, accuracyMeters)
        if (packet == null) {
            Log.e(TAG, "SOS could not be created; mesh core unavailable.")
            recordLocalMessage(text, "PublicSos", MessageStatus.FAILED)
            return false
        }
        addCustodyPacket(packet, sourceAddress = null)
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
        addCustodyPacket(packet, sourceAddress = null)
        recordLocalMessage(text, "Chat", MessageStatus.QUEUED, recipientIdHex, encrypted = true)
        return true
    }

    /** Starts a Noise handshake so chat with this peer becomes possible. */
    fun startHandshake(peerIdHex: String): Boolean {
        val packet = MeshCoreBridge.beginHandshake(peerIdHex) ?: return false
        addCustodyPacket(packet, sourceAddress = null)
        return true
    }

    // -----------------------------------------------------------------------
    // Out-of-band verification
    // -----------------------------------------------------------------------

    /**
     * The safety number to read out to this peer, or null when there is no
     * session to derive one from. The UI must show nothing rather than
     * improvising, because a number the two devices computed differently would
     * teach users that mismatches are normal.
     */
    fun safetyNumber(peerIdHex: String): String? = MeshCoreBridge.safetyNumber(peerIdHex)

    /** Records that the user compared safety numbers and they matched. */
    fun setPeerVerified(peerIdHex: String, verified: Boolean): Boolean {
        val ok = MeshCoreBridge.setPeerVerified(peerIdHex, verified)
        if (ok) {
            refreshPeerSessionState(peerIdHex)
            schedulePersist()
        }
        return ok
    }

    fun isPeerVerified(peerIdHex: String): Boolean = MeshCoreBridge.isPeerVerified(peerIdHex)

    // -----------------------------------------------------------------------
    // Groups
    // -----------------------------------------------------------------------

    /** Creates a private group with this device as admin. Returns its id. */
    fun createGroup(name: String): String? {
        val groupId = MeshCoreBridge.createGroup(name)
        if (groupId == null) {
            Log.e(TAG, "Group could not be created; mesh core unavailable.")
            return null
        }
        syncGroups()
        schedulePersist()
        return groupId
    }

    /**
     * Invites a peer into a group.
     *
     * Returns false when there is no pairwise session with them: the group key
     * travels inside that session, so there is no way to add someone you have
     * not completed a handshake with. The UI should offer to set up the secure
     * link first rather than presenting this as an error.
     */
    fun inviteToGroup(groupIdHex: String, peerIdHex: String): Boolean {
        val packet = MeshCoreBridge.inviteToGroup(groupIdHex, peerIdHex)
        if (packet == null) {
            Log.w(TAG, "Invite not sent: no session with $peerIdHex, or not the group admin.")
            return false
        }
        addCustodyPacket(packet, sourceAddress = null)
        syncGroups()
        schedulePersist()
        return true
    }

    /**
     * Builds, records, and queues an encrypted group message.
     * Returns false when this device holds no key for the group; the message is
     * never downgraded to plaintext.
     */
    fun sendGroupChat(groupIdHex: String, text: String): Boolean {
        val packet = MeshCoreBridge.createGroupChat(groupIdHex, text)
        if (packet == null) {
            Log.w(TAG, "Group message not sent: no key for $groupIdHex.")
            recordLocalMessage(
                text,
                "GroupChat",
                MessageStatus.FAILED,
                MessageEntity.BROADCAST_RECIPIENT,
                encrypted = true,
                groupId = groupIdHex
            )
            return false
        }
        addCustodyPacket(packet, sourceAddress = null)
        recordLocalMessage(
            text,
            "GroupChat",
            MessageStatus.QUEUED,
            MessageEntity.BROADCAST_RECIPIENT,
            encrypted = true,
            groupId = groupIdHex
        )
        return true
    }

    /**
     * Removes a member and rotates the group key so they cannot read anything
     * sent afterwards.
     *
     * The report names members that could not be re-keyed. That is not a detail
     * to swallow: until they are reachable again they will see group traffic
     * they cannot decrypt, and the admin needs to know that.
     */
    fun removeFromGroup(groupIdHex: String, peerIdHex: String): GroupRekeyReport? =
        applyRekey(MeshCoreBridge.removeFromGroup(groupIdHex, peerIdHex))

    /** Rotates a group key without changing membership, after a suspected compromise. */
    fun rekeyGroup(groupIdHex: String): GroupRekeyReport? =
        applyRekey(MeshCoreBridge.rekeyGroup(groupIdHex))

    private fun applyRekey(json: String?): GroupRekeyReport? {
        if (json == null) return null
        return try {
            val obj = JSONObject(json)
            val packets = obj.getJSONArray("invite_packets_hex")
            for (i in 0 until packets.length()) {
                addCustodyPacket(packets.getString(i).hexToBytes(), sourceAddress = null)
            }
            val unreachableArray = obj.getJSONArray("unreachable")
            val unreachable = (0 until unreachableArray.length())
                .map { unreachableArray.getString(it) }

            syncGroups()
            schedulePersist()
            GroupRekeyReport(invitesSent = packets.length(), unreachable = unreachable)
        } catch (e: Exception) {
            Log.w(TAG, "Malformed rekey result from mesh core.", e)
            null
        }
    }

    /** Leaves a group, deleting its key from this device. */
    fun leaveGroup(groupIdHex: String): Boolean {
        val left = MeshCoreBridge.leaveGroup(groupIdHex)
        if (left) {
            syncGroups()
            schedulePersist()
        }
        return left
    }

    /** Re-reads the group list from the core, which is authoritative. */
    fun syncGroups() {
        val jsonStr = MeshCoreBridge.groupsJson() ?: return
        try {
            val array = JSONArray(jsonStr)
            val seen = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val membersArray = obj.getJSONArray("members")
                val entity = GroupEntity(
                    groupId = obj.getString("group_id"),
                    name = obj.getString("name"),
                    epoch = obj.getInt("epoch"),
                    adminId = obj.getString("admin_id"),
                    isAdmin = obj.getBoolean("is_admin"),
                    members = (0 until membersArray.length()).map { membersArray.getString(it) },
                    createdAtSecs = obj.getLong("created_at")
                )
                groupStore[entity.groupId] = entity
                seen += entity.groupId
            }
            groupStore.keys.retainAll(seen)
            _groupsFlow.value = groupStore.values.sortedBy { it.name }
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse groups from mesh core.", e)
        }
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

        addCustodyPacket(packet, sourceAddress = null)
        syncResourcePins()
        return true
    }

    /**
     * Enqueues or refreshes a packet in local custody.
     * If already present, the existing custody record is retained.
     */
    fun addCustodyPacket(
        packet: ByteArray,
        sourceAddress: String? = null,
        ttlMillis: Long = DEFAULT_CUSTODY_TTL_MILLIS
    ): CustodyPacket {
        pruneExpiredCustody()
        val id = packetHash(packet)
        val existing = custodyStore[id]
        if (existing != null) {
            return existing
        }
        if (custodyStore.size >= MAX_CUSTODY_PACKETS) {
            val oldest = custodyOrder.poll()
            if (oldest != null) {
                custodyStore.remove(oldest)
            }
        }
        val now = System.currentTimeMillis()
        val cp = CustodyPacket(
            packetId = id,
            bytes = packet,
            sourceAddress = sourceAddress,
            createdAtMillis = now,
            expiryMillis = now + ttlMillis
        )
        custodyStore[id] = cp
        custodyOrder.add(id)
        return cp
    }

    /**
     * Returns all packets in custody that are eligible to be transmitted to [peerAddress].
     * Excludes expired packets, packets already delivered to this peer, and packets
     * that were originally supplied by this peer (flood fan-out without echoes).
     */
    fun getOutboundPacketsForPeer(peerAddress: String): List<ByteArray> {
        pruneExpiredCustody()
        val now = System.currentTimeMillis()
        val result = mutableListOf<ByteArray>()
        for (id in custodyOrder) {
            val cp = custodyStore[id] ?: continue
            if (cp.isEligibleForPeer(peerAddress, now)) {
                result.add(cp.bytes)
            }
        }
        return result
    }

    /**
     * Records that [peerAddress] has fully received [packet], so it is not
     * retransmitted to this peer on subsequent connections.
     */
    fun markPacketDeliveredToPeer(packet: ByteArray, peerAddress: String) {
        val id = packetHash(packet)
        val cp = custodyStore[id] ?: return
        cp.deliveredPeers.add(peerAddress.uppercase())
        markQueuedAsSent()
    }

    fun isPacketDeliveredToPeer(packet: ByteArray, peerAddress: String): Boolean {
        val id = packetHash(packet)
        val cp = custodyStore[id] ?: return false
        return cp.deliveredPeers.any { it.equals(peerAddress, ignoreCase = true) }
    }

    fun getCustodyPacket(packet: ByteArray): CustodyPacket? {
        val id = packetHash(packet)
        return custodyStore[id]
    }

    fun pruneExpiredCustody(nowMillis: Long = System.currentTimeMillis()) {
        val it = custodyStore.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value.isExpired(nowMillis)) {
                it.remove()
            }
        }
        custodyOrder.removeIf { !custodyStore.containsKey(it) }
    }

    fun clearCustody() {
        custodyStore.clear()
        custodyOrder.clear()
    }

    /** Drains everything waiting to be transmitted. Primarily for tests. */
    fun drainOutbound(): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        while (true) {
            val id = custodyOrder.poll() ?: break
            val cp = custodyStore.remove(id) ?: continue
            if (!cp.isExpired()) {
                packets.add(cp.bytes)
            }
        }
        if (packets.isNotEmpty()) {
            markQueuedAsSent()
        }
        return packets
    }

    fun queueRawPacket(packet: ByteArray, sourceAddress: String? = null) {
        addCustodyPacket(packet, sourceAddress)
    }

    fun hasPendingOutbound(): Boolean {
        pruneExpiredCustody()
        return custodyStore.isNotEmpty()
    }

    fun hasPendingOutboundForPeer(peerAddress: String): Boolean {
        pruneExpiredCustody()
        val now = System.currentTimeMillis()
        for (id in custodyOrder) {
            val cp = custodyStore[id] ?: continue
            if (cp.isEligibleForPeer(peerAddress, now)) return true
        }
        return false
    }

    /**
     * How many packets this device is still holding for the mesh.
     *
     * Surfaced in the UI because custody is the one thing store-and-forward asks
     * of a user: a non-zero count means their phone is carrying someone else's
     * traffic and walking away from the crowd throws it away.
     */
    fun pendingOutboundCount(): Int {
        pruneExpiredCustody()
        return custodyStore.size
    }

    // -----------------------------------------------------------------------
    // Inbound
    // -----------------------------------------------------------------------

    /**
     * Hands a raw frame to the native core for verification and acts on the
     * result. Frames that fail authentication are dropped silently; they are
     * noise or hostile, and either way nothing about them should reach the UI.
     */
    fun processIncomingPacket(
        rawPacket: ByteArray,
        transport: String = "Bluetooth LE",
        directLinkRssi: Int? = null,
        sourceAddress: String? = null
    ) {
        val json = processIncomingInvoker(rawPacket)
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
            // Relayed packets record their source neighbor to prevent echo fan-out back to supplier.
            obj.optStringOrNull("relay_packet_hex")?.let { addCustodyPacket(it.hexToBytes(), sourceAddress = sourceAddress) }
            obj.optStringOrNull("ack_packet_hex")?.let { addCustodyPacket(it.hexToBytes(), sourceAddress = null) }
            obj.optStringOrNull("handshake_reply_hex")?.let { addCustodyPacket(it.hexToBytes(), sourceAddress = null) }

            // Classify transport hop evidence fail-closed (B-RFC-001, REVIEW H13).
            // Without explicit core confirmation via hops_on_arrival == 0, no packet
            // is treated as direct.
            val hopsOnArrival = if (obj.has("hops_on_arrival")) obj.getInt("hops_on_arrival") else null
            val isDuplicate = obj.optBoolean("is_duplicate", false)
            val classification = RadarProjection.classifyHopObservation(
                hopsOnArrival = hopsOnArrival,
                isDuplicate = isDuplicate,
                directLinkRssi = directLinkRssi
            )

            observePeer(
                nodeId = senderId,
                transport = transport,
                rssiDbm = classification.rssiDbm,
                reachability = classification.reachability,
                hopCount = classification.hopCount
            )

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

                "GroupChat" -> {
                    // A group message is only readable when the core matched the
                    // packet's tag to a group we hold the key for. No plaintext
                    // means we are relaying for a group we are not in, which is
                    // normal and must leave no trace in the UI.
                    val groupId = obj.optStringOrNull("group_id")
                    val text = obj.optStringOrNull("plaintext")
                    if (groupId != null && text != null) {
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
                                encrypted = true,
                                groupId = groupId
                            )
                        )
                    }
                }

                "GroupInvite" -> if (addressedToUs) {
                    // Membership changed, so the core's group list is now ahead
                    // of ours, and the new key must reach the vault.
                    syncGroups()
                    schedulePersist()
                    obj.optStringOrNull("group_event")?.let { event ->
                        Log.i(TAG, "Group membership event from $senderId: $event")
                    }
                }

                "Ack" -> if (addressedToUs) {
                    markDelivered(msgId)
                }

                "ResourcePin" -> syncResourcePins()

                "NoiseHandshake" -> {
                    refreshPeerSessionState(senderId)
                    // A completed handshake produced session keys worth keeping,
                    // so the next launch does not have to redo it.
                    schedulePersist()
                }
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
    fun observePeer(
        nodeId: String,
        transport: String,
        rssiDbm: Int? = null,
        reachability: PeerReachability = PeerReachability.RELAYED,
        hopCount: Int? = null
    ) {
        val existing = peerStore[nodeId]
        val observation = PeerObservation(
            nodeId = nodeId,
            transport = transport,
            rssiDbm = rssiDbm,
            reachability = reachability,
            hopCount = hopCount
        )
        val now = System.currentTimeMillis()
        val hasSession = MeshCoreBridge.hasSession(nodeId)
        val isVerified = MeshCoreBridge.isPeerVerified(nodeId)

        peerStore[nodeId] = RadarProjection.mergePeerObservation(
            existing = existing,
            observation = observation,
            nowMillis = now,
            hasSession = hasSession,
            isVerified = isVerified
        )
        publishPeers()
    }

    private fun refreshPeerSessionState(nodeId: String) {
        peerStore[nodeId]?.let {
            peerStore[nodeId] = it.copy(
                hasSecureSession = MeshCoreBridge.hasSession(nodeId),
                isVerified = MeshCoreBridge.isPeerVerified(nodeId)
            )
            publishPeers()
        }
    }

    fun prunePeers() {
        val now = System.currentTimeMillis()
        val cutoff = now - PEER_STALE_MILLIS
        val before = peerStore.size
        peerStore.values.removeIf { it.lastSeenMillis < cutoff }

        // Expire direct reachability for peers not heard directly within DIRECT_LINK_EXPIRY_MILLIS
        var directExpired = false
        peerStore.replaceAll { _, peer ->
            val updated = RadarProjection.recheckPeerExpiry(peer, now)
            if (updated !== peer) directExpired = true
            updated
        }

        if (peerStore.size != before || directExpired) publishPeers()
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
        encrypted: Boolean = false,
        groupId: String? = null
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
                encrypted = encrypted,
                groupId = groupId
            )
        )
    }

    private fun upsertMessage(message: MessageEntity) {
        messageStore[message.msgId] = message
        publishMessages()
        schedulePersist()
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
    // Persistence
    // -----------------------------------------------------------------------

    /**
     * Queues a write of the message history and the core's sealed state.
     *
     * Coalesced deliberately: a burst of packets should cost one write, not one
     * per packet. The flag is cleared before the work runs, so anything that
     * arrives while a write is in flight schedules another rather than being
     * folded into a write that has already read its snapshot.
     */
    private fun schedulePersist() {
        if (!persistPending.compareAndSet(false, true)) return
        try {
            persistExecutor.execute {
                persistPending.set(false)
                persistNow()
            }
        } catch (e: Exception) {
            // Executor shut down, most likely during a wipe.
            persistPending.set(false)
            Log.w(TAG, "Could not schedule a state write.", e)
        }
    }

    /**
     * Writes everything out immediately, on the calling thread. Used when the
     * app is going to the background and may not get another chance.
     */
    fun persistNow() {
        try {
            EncryptedMessageStore.save(context, messageStore.values)
            SecureStateStore.persist(context)
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist state.", e)
        }
    }

    // -----------------------------------------------------------------------
    // Panic wipe
    // -----------------------------------------------------------------------

    /**
     * Destroys every session key, group key, verification, and stored message on
     * this device.
     *
     * This is what someone reaches for when their phone is about to be taken.
     * It cannot recall what has already been transmitted, and it does not hide
     * that MeshLine is installed — but it does mean the device can no longer
     * read group traffic or show what was said.
     *
     * The mesh identity is deliberately kept: it is not secret in the way the
     * rest of this is, and destroying it would also destroy every safety number
     * a contact has already verified, silently turning the user into a stranger
     * everyone would have to re-verify.
     */
    fun panicWipe() {
        MeshCoreBridge.wipeSecureState()

        messageStore.clear()
        groupStore.clear()
        peerStore.clear()
        clearCustody()

        EncryptedMessageStore.wipe(context)
        SecureStateStore.wipe(context)

        publishMessages()
        publishPeers()
        _groupsFlow.value = emptyList()
        Log.w(TAG, "Secure state wiped on user request.")
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
