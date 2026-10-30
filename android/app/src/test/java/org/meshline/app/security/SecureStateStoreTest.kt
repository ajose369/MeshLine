package org.meshline.app.security

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The file half of the nonce safety design: a commit is only ever issued after
 * [SecureStateStore.writeDurably] returned true, so it must not return true unless
 * the new contents are in place, and must not damage the old ones when it fails.
 */
@RunWith(RobolectricTestRunner::class)
class SecureStateStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("secure-state").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aWriteReplacesTheOldContentsAndLeavesNoTemporaryFile() {
        val target = File(dir, "state.bin")
        target.writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(SecureStateStore.writeDurably(target, byteArrayOf(4, 5)))

        assertArrayEquals(byteArrayOf(4, 5), target.readBytes())
        assertFalse(File(dir, "state.bin.tmp").exists())
    }

    @Test
    fun aFailedWriteLeavesTheOldContentsAlone() {
        val target = File(dir, "state.bin")
        target.writeBytes(byteArrayOf(1, 2, 3))
        // A directory where the temporary file belongs makes the write fail.
        assertTrue(File(dir, "state.bin.tmp").mkdir())

        assertThrows(Exception::class.java) {
            SecureStateStore.writeDurably(target, byteArrayOf(4, 5))
        }

        assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
    }
}
