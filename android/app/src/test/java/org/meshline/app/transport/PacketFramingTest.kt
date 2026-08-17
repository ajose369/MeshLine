package org.meshline.app.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These exist because the transport silently truncated every packet to 20 bytes
 * before this layer was written. The smallest real MeshLine packet is 212 bytes,
 * so nothing the app sent could ever verify at the far end, and the failure was
 * invisible — unverifiable traffic is dropped without comment by design.
 */
class PacketFramingTest {

    private val alice = "AA:BB:CC:DD:EE:01"
    private val bob = "AA:BB:CC:DD:EE:02"

    private fun packet(size: Int): ByteArray = ByteArray(size) { (it % 251).toByte() }

    /** Feeds every frame through a reassembler, returning the completed packet. */
    private fun roundTrip(
        packet: ByteArray,
        mtu: Int,
        device: String = alice,
        reassembler: PacketReassembler = PacketReassembler()
    ): ByteArray? {
        var completed: ByteArray? = null
        PacketFraming.fragment(packet, mtu).forEach { frame ->
            reassembler.accept(device, frame, 0L)?.let { completed = it }
        }
        return completed
    }

    @Test
    fun `a real packet survives the default mtu`() {
        // 23 is what the MTU is until something negotiates otherwise, and 212 is
        // the smallest packet the core produces.
        val original = packet(212)
        assertArrayEquals(original, roundTrip(original, PacketFraming.DEFAULT_MTU))
    }

    @Test
    fun `the default mtu really does need many frames`() {
        val frames = PacketFraming.fragment(packet(212), PacketFraming.DEFAULT_MTU)
        assertTrue("expected multiple frames, got ${frames.size}", frames.size > 1)
        frames.forEach {
            assertTrue(
                "frame of ${it.size} exceeds what a 23-byte MTU can carry",
                it.size <= PacketFraming.DEFAULT_MTU - PacketFraming.ATT_WRITE_OVERHEAD
            )
        }
    }

    @Test
    fun `a negotiated mtu carries a handshake in one frame`() {
        // 372 bytes is handshake message 2, the largest thing in a normal
        // exchange. At the negotiated maximum it should not need splitting.
        val frames = PacketFraming.fragment(packet(372), PacketFraming.MAX_MTU)
        assertEquals(1, frames.size)
    }

    @Test
    fun `packets round trip across the mtu range`() {
        // Sizes taken from the real packet types, plus the extremes.
        for (size in listOf(1, 212, 217, 257, 336, 372, 1300, PacketFraming.MAX_PACKET_BYTES)) {
            for (mtu in listOf(23, 64, 185, 247, 517)) {
                val original = packet(size)
                assertArrayEquals(
                    "size=$size mtu=$mtu",
                    original,
                    roundTrip(original, mtu)
                )
            }
        }
    }

    @Test
    fun `every frame fits the negotiated mtu`() {
        for (mtu in listOf(23, 64, 185, 247, 517)) {
            PacketFraming.fragment(packet(2000), mtu).forEach {
                assertTrue(it.size <= mtu - PacketFraming.ATT_WRITE_OVERHEAD)
            }
        }
    }

    @Test
    fun `a full frame at the maximum mtu fits a single att write`() {
        // The transport bounds inbound writes by this value. If a frame at the
        // negotiated maximum exceeds it, every full-size frame on a
        // well-negotiated link is silently rejected.
        val maxWrite = PacketFraming.MAX_MTU - PacketFraming.ATT_WRITE_OVERHEAD
        val largest = PacketFraming.fragment(packet(2000), PacketFraming.MAX_MTU)
            .maxOf { it.size }
        assertTrue("frame of $largest exceeds the $maxWrite byte write budget", largest <= maxWrite)
    }

    @Test
    fun `frames arriving out of order still reassemble`() {
        val original = packet(600)
        val frames = PacketFraming.fragment(original, 64).reversed()
        val reassembler = PacketReassembler()

        var completed: ByteArray? = null
        frames.forEach { reassembler.accept(alice, it, 0L)?.let { done -> completed = done } }
        assertArrayEquals(original, completed)
    }

    @Test
    fun `a repeated frame does not corrupt the packet`() {
        val original = packet(600)
        val frames = PacketFraming.fragment(original, 64)
        val reassembler = PacketReassembler()

        var completed: ByteArray? = null
        frames.forEach { frame ->
            // BLE retries; the same frame arriving twice must be idempotent.
            reassembler.accept(alice, frame, 0L)?.let { completed = it }
            reassembler.accept(alice, frame, 0L)?.let { completed = it }
        }
        assertArrayEquals(original, completed)
    }

    @Test
    fun `two peers sending at once do not corrupt each other`() {
        val fromAlice = packet(400)
        val fromBob = ByteArray(400) { (255 - (it % 251)).toByte() }
        val reassembler = PacketReassembler()

        val aliceFrames = PacketFraming.fragment(fromAlice, 64)
        val bobFrames = PacketFraming.fragment(fromBob, 64)

        var aliceDone: ByteArray? = null
        var bobDone: ByteArray? = null
        // Interleaved, as two peers writing concurrently would be.
        for (i in 0 until maxOf(aliceFrames.size, bobFrames.size)) {
            aliceFrames.getOrNull(i)?.let { reassembler.accept(alice, it, 0L)?.let { d -> aliceDone = d } }
            bobFrames.getOrNull(i)?.let { reassembler.accept(bob, it, 0L)?.let { d -> bobDone = d } }
        }

        assertArrayEquals(fromAlice, aliceDone)
        assertArrayEquals(fromBob, bobDone)
    }

    @Test
    fun `an incomplete packet yields nothing`() {
        val frames = PacketFraming.fragment(packet(600), 64)
        val reassembler = PacketReassembler()
        frames.dropLast(1).forEach { assertNull(reassembler.accept(alice, it, 0L)) }
        assertEquals(1, reassembler.pendingCount)
    }

    // -----------------------------------------------------------------------
    // Hostile input. Everything here arrives before any signature is checked.
    // -----------------------------------------------------------------------

    @Test
    fun `garbage frames are dropped without throwing`() {
        val reassembler = PacketReassembler()
        val junk = listOf(
            ByteArray(0),
            ByteArray(1),
            ByteArray(PacketFraming.HEADER_BYTES),
            ByteArray(PacketFraming.HEADER_BYTES + 1) { 0xFF.toByte() },
            ByteArray(200) { 0x00 },
            ByteArray(200) { 0xFF.toByte() }
        )
        junk.forEach { assertNull(reassembler.accept(alice, it, 0L)) }
    }

    @Test
    fun `a frame with the wrong version is ignored`() {
        val frame = PacketFraming.fragment(packet(50), 517).single()
        frame[0] = 99
        assertNull(PacketReassembler().accept(alice, frame, 0L))
    }

    @Test
    fun `a fragment index beyond the count is refused`() {
        val frame = PacketFraming.fragment(packet(600), 64).first()
        // index = 9999, count unchanged
        frame[5] = 0x27
        frame[6] = 0x0F
        assertNull(PacketReassembler().accept(alice, frame, 0L))
    }

    @Test
    fun `a peer that changes the fragment count mid packet loses the whole thing`() {
        val reassembler = PacketReassembler()
        val frames = PacketFraming.fragment(packet(600), 64)

        reassembler.accept(alice, frames[0], 0L)
        assertEquals(1, reassembler.pendingCount)

        // Same message id, contradictory count.
        val lying = frames[1].copyOf()
        lying[7] = 0x00
        lying[8] = 0x02
        assertNull(reassembler.accept(alice, lying, 0L))
        assertEquals(
            "a contradictory frame must destroy the reassembly, not merge into it",
            0,
            reassembler.pendingCount
        )
    }

    @Test
    fun `abandoned reassemblies are evicted after the timeout`() {
        val reassembler = PacketReassembler()
        reassembler.accept(alice, PacketFraming.fragment(packet(600), 64).first(), 0L)
        assertEquals(1, reassembler.pendingCount)

        // A peer that sends one fragment and goes quiet must not pin memory.
        reassembler.accept(
            bob,
            PacketFraming.fragment(packet(600), 64).first(),
            PacketFraming.REASSEMBLY_TIMEOUT_MILLIS + 1
        )
        assertEquals(1, reassembler.pendingCount)
    }

    @Test
    fun `one peer cannot exhaust the reassembly table`() {
        val reassembler = PacketReassembler()
        // Far more concurrent part-built packets than the per-device ceiling.
        repeat(50) {
            reassembler.accept(alice, PacketFraming.fragment(packet(600), 64).first(), 0L)
        }
        assertTrue(
            "expected at most ${PacketFraming.MAX_REASSEMBLIES_PER_DEVICE}, got ${reassembler.pendingCount}",
            reassembler.pendingCount <= PacketFraming.MAX_REASSEMBLIES_PER_DEVICE
        )
    }

    @Test
    fun `a flood from many peers stays within the global ceiling`() {
        val reassembler = PacketReassembler()
        repeat(200) { i ->
            reassembler.accept(
                "DE:AD:BE:EF:00:${i % 256}",
                PacketFraming.fragment(packet(600), 64).first(),
                0L
            )
        }
        assertTrue(
            "expected at most ${PacketFraming.MAX_REASSEMBLIES_TOTAL}, got ${reassembler.pendingCount}",
            reassembler.pendingCount <= PacketFraming.MAX_REASSEMBLIES_TOTAL
        )
    }

    @Test
    fun `one noisy peer cannot evict another peer's partial packet`() {
        val reassembler = PacketReassembler()
        val victimFrames = PacketFraming.fragment(packet(600), 64)
        reassembler.accept(alice, victimFrames.first(), 0L)

        // Bob opens far more reassemblies than his own share allows.
        repeat(50) {
            reassembler.accept(bob, PacketFraming.fragment(packet(600), 64).first(), 0L)
        }

        // Alice's packet must still complete.
        var completed: ByteArray? = null
        victimFrames.drop(1).forEach {
            reassembler.accept(alice, it, 0L)?.let { done -> completed = done }
        }
        assertNotNull("a noisy peer must not be able to starve a quiet one", completed)
    }

    @Test
    fun `a packet larger than the core will parse is refused outright`() {
        assertTrue(
            PacketFraming.fragment(packet(PacketFraming.MAX_PACKET_BYTES + 1), 517).isEmpty()
        )
    }

    @Test
    fun `an empty packet produces no frames`() {
        assertTrue(PacketFraming.fragment(ByteArray(0), 517).isEmpty())
    }

    @Test
    fun `forgetting a device clears only that device`() {
        val reassembler = PacketReassembler()
        reassembler.accept(alice, PacketFraming.fragment(packet(600), 64).first(), 0L)
        reassembler.accept(bob, PacketFraming.fragment(packet(600), 64).first(), 0L)
        assertEquals(2, reassembler.pendingCount)

        reassembler.forgetDevice(alice)
        assertEquals(1, reassembler.pendingCount)
    }

    @Test
    fun `the fragment ceiling covers the largest packet at the smallest mtu`() {
        // If this ever stops holding, the biggest packets become undeliverable
        // exactly when the MTU is worst — the case fragmentation exists for.
        val worstCase = PacketFraming.MAX_PACKET_BYTES /
            PacketFraming.payloadCapacity(PacketFraming.DEFAULT_MTU) + 1
        assertTrue(
            "MAX_FRAGMENTS=${PacketFraming.MAX_FRAGMENTS} cannot carry $worstCase fragments",
            PacketFraming.MAX_FRAGMENTS >= worstCase
        )
    }

    @Test
    fun `payload capacity never goes below one byte`() {
        // A peer reporting a nonsensical MTU must not cause a divide-by-zero or
        // an infinite fragment count.
        for (mtu in listOf(0, 1, 5, 12, 23)) {
            assertTrue(PacketFraming.payloadCapacity(mtu) >= 1)
        }
    }
}
