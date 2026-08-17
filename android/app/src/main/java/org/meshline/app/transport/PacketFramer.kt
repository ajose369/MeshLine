package org.meshline.app.transport

import java.security.SecureRandom

/**
 * Splits mesh packets across BLE writes and puts them back together.
 *
 * A GATT write carries `MTU - 3` bytes, and the MTU is 23 until something
 * negotiates otherwise — 20 usable bytes. The smallest packet MeshLine produces
 * is 212 bytes and a group invite runs past 330, so without this every packet
 * was truncated to its first 20 bytes, failed its signature check, and was
 * dropped in silence. Negotiating a larger MTU helps but does not remove the
 * need for this: the negotiated value varies by device and by peer, and a
 * full-size group invite exceeds even the 517-byte ceiling Android allows.
 *
 * # Frame layout
 *
 * ```text
 * 0      version (1 byte)
 * 1..4   message id (4 bytes) — groups the fragments of one packet
 * 5..6   fragment index (2 bytes, big endian)
 * 7..8   fragment count (2 bytes, big endian)
 * 9..    payload slice
 * ```
 *
 * # Trust
 *
 * Everything here runs on **unauthenticated** input. Signature verification
 * happens in the native core, which cannot see a packet until reassembly has
 * finished, so this buffer is filled by anyone in radio range. Every limit below
 * exists for that reason: without them, a peer that sends fragment 400 of 500
 * and then goes quiet would pin memory indefinitely, and a few of those would
 * exhaust the heap of a phone that is also trying to relay for a crowd.
 */
object PacketFraming {

    const val VERSION: Byte = 1
    const val HEADER_BYTES = 9

    /** The default ATT MTU, used until a larger one is negotiated. */
    const val DEFAULT_MTU = 23

    /** The largest MTU Android will negotiate. */
    const val MAX_MTU = 517

    /** ATT opcode plus attribute handle, deducted from the MTU. */
    const val ATT_WRITE_OVERHEAD = 3

    /**
     * Mirrors `MAX_PACKET_BYTES` in the native core. A reassembly that would
     * exceed what the core will ever parse is abandoned rather than completed.
     */
    const val MAX_PACKET_BYTES = 8 * 1024

    /**
     * Bounds the fragment table for one packet.
     *
     * This has to be at least `MAX_PACKET_BYTES / payloadCapacity(DEFAULT_MTU)`
     * — 745 at the time of writing — or the largest packets become
     * undeliverable precisely when the MTU is smallest, which is the case this
     * class exists to handle. The cost of the headroom is one pointer array per
     * part-built packet, and the real memory bound is [MAX_PACKET_BYTES]
     * multiplied by [MAX_REASSEMBLIES_TOTAL] regardless.
     */
    const val MAX_FRAGMENTS = 1024

    /** Concurrent part-built packets kept per peer, and in total. */
    const val MAX_REASSEMBLIES_PER_DEVICE = 4
    const val MAX_REASSEMBLIES_TOTAL = 32

    /** How long an incomplete packet is held before it is abandoned. */
    const val REASSEMBLY_TIMEOUT_MILLIS = 30_000L

    private val random = SecureRandom()

    /** Usable payload bytes per fragment at a given negotiated MTU. */
    fun payloadCapacity(mtu: Int): Int =
        (mtu.coerceAtLeast(DEFAULT_MTU) - ATT_WRITE_OVERHEAD - HEADER_BYTES).coerceAtLeast(1)

    /**
     * Splits a packet into frames sized for `mtu`.
     *
     * Returns an empty list for an empty or oversized packet: there is nothing
     * useful to send, and silently transmitting a truncated prefix is what this
     * class exists to stop.
     */
    fun fragment(packet: ByteArray, mtu: Int): List<ByteArray> {
        if (packet.isEmpty() || packet.size > MAX_PACKET_BYTES) return emptyList()

        val capacity = payloadCapacity(mtu)
        val count = (packet.size + capacity - 1) / capacity
        if (count > MAX_FRAGMENTS) return emptyList()

        val messageId = random.nextInt()
        val frames = ArrayList<ByteArray>(count)

        for (index in 0 until count) {
            val start = index * capacity
            val end = minOf(start + capacity, packet.size)
            val frame = ByteArray(HEADER_BYTES + (end - start))

            frame[0] = VERSION
            frame[1] = (messageId ushr 24).toByte()
            frame[2] = (messageId ushr 16).toByte()
            frame[3] = (messageId ushr 8).toByte()
            frame[4] = messageId.toByte()
            frame[5] = (index ushr 8).toByte()
            frame[6] = index.toByte()
            frame[7] = (count ushr 8).toByte()
            frame[8] = count.toByte()

            packet.copyInto(frame, HEADER_BYTES, start, end)
            frames += frame
        }
        return frames
    }
}

/**
 * Rebuilds packets from frames, one instance per transport.
 *
 * Keyed by peer as well as message id, so two peers using the same message id —
 * by chance or on purpose — cannot corrupt each other's packets.
 *
 * Not thread-safe on its own; [BleTransportManager] serialises access. Callers
 * must pass a monotonic `nowMillis` so eviction cannot be manipulated by a
 * device whose clock is wrong.
 */
class PacketReassembler {

    private class Partial(
        val count: Int,
        val fragments: Array<ByteArray?>,
        var received: Int,
        var bytes: Int,
        var lastUpdateMillis: Long
    )

    private val partials = LinkedHashMap<String, Partial>()

    /** Part-built packets currently held, for tests and diagnostics. */
    val pendingCount: Int get() = partials.size

    /**
     * Accepts one frame. Returns the complete packet when this frame finished
     * it, or null when more are needed or the frame was unusable.
     *
     * A malformed frame is dropped without comment, and a frame that
     * contradicts one already held destroys the whole part-built packet rather
     * than being merged into it — a peer that changes its mind about a packet's
     * length mid-transfer is either broken or hostile, and neither deserves the
     * benefit of the doubt.
     */
    fun accept(deviceKey: String, frame: ByteArray, nowMillis: Long): ByteArray? {
        evictExpired(nowMillis)

        if (frame.size <= PacketFraming.HEADER_BYTES) return null
        if (frame[0] != PacketFraming.VERSION) return null

        val messageId = (frame[1].toInt() and 0xFF shl 24) or
            (frame[2].toInt() and 0xFF shl 16) or
            (frame[3].toInt() and 0xFF shl 8) or
            (frame[4].toInt() and 0xFF)
        val index = (frame[5].toInt() and 0xFF shl 8) or (frame[6].toInt() and 0xFF)
        val count = (frame[7].toInt() and 0xFF shl 8) or (frame[8].toInt() and 0xFF)

        if (count < 1 || count > PacketFraming.MAX_FRAGMENTS) return null
        if (index >= count) return null

        val payload = frame.copyOfRange(PacketFraming.HEADER_BYTES, frame.size)
        if (payload.size > PacketFraming.MAX_PACKET_BYTES) return null

        // The common case: a packet that fits one frame needs no bookkeeping.
        if (count == 1) return payload

        val key = "$deviceKey/$messageId"
        val existing = partials[key]

        val partial = if (existing == null) {
            makeRoom(deviceKey, nowMillis)
            Partial(
                count = count,
                fragments = arrayOfNulls(count),
                received = 0,
                bytes = 0,
                lastUpdateMillis = nowMillis
            ).also { partials[key] = it }
        } else {
            if (existing.count != count) {
                partials.remove(key)
                return null
            }
            existing
        }

        // A repeated fragment is not an error — BLE retries — but it must not be
        // counted twice or allowed to grow the byte total.
        if (partial.fragments[index] == null) {
            if (partial.bytes + payload.size > PacketFraming.MAX_PACKET_BYTES) {
                partials.remove(key)
                return null
            }
            partial.fragments[index] = payload
            partial.received++
            partial.bytes += payload.size
        }
        partial.lastUpdateMillis = nowMillis

        if (partial.received < partial.count) return null

        partials.remove(key)
        val packet = ByteArray(partial.bytes)
        var offset = 0
        for (fragment in partial.fragments) {
            fragment ?: return null
            fragment.copyInto(packet, offset)
            offset += fragment.size
        }
        return packet
    }

    /** Forgets anything in flight for a peer, on disconnect. */
    fun forgetDevice(deviceKey: String) {
        partials.keys.removeAll { it.startsWith("$deviceKey/") }
    }

    fun clear() {
        partials.clear()
    }

    private fun evictExpired(nowMillis: Long) {
        partials.entries.removeAll {
            nowMillis - it.value.lastUpdateMillis > PacketFraming.REASSEMBLY_TIMEOUT_MILLIS
        }
    }

    /**
     * Enforces the per-device and global ceilings by dropping the least recently
     * updated entry. Per-device first, so one noisy peer cannot evict everyone
     * else's part-built packets.
     */
    private fun makeRoom(deviceKey: String, nowMillis: Long) {
        val prefix = "$deviceKey/"
        while (partials.keys.count { it.startsWith(prefix) } >= PacketFraming.MAX_REASSEMBLIES_PER_DEVICE) {
            val oldest = partials.entries
                .filter { it.key.startsWith(prefix) }
                .minByOrNull { it.value.lastUpdateMillis } ?: break
            partials.remove(oldest.key)
        }
        while (partials.size >= PacketFraming.MAX_REASSEMBLIES_TOTAL) {
            val oldest = partials.entries.minByOrNull { it.value.lastUpdateMillis } ?: break
            partials.remove(oldest.key)
        }
    }
}
