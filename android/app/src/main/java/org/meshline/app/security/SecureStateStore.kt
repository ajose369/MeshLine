package org.meshline.app.security

import android.content.Context
import android.util.Log
import java.io.File
import org.meshline.app.bridge.MeshCoreBridge

/**
 * Persists the native core's session keys, group keys, and verification
 * decisions across restarts.
 *
 * Without this, every app launch started from nothing: no sessions, so a fresh
 * three-message Noise handshake with every peer before a word could be sent; no
 * group keys, so every group had to be rebuilt; and no record of which safety
 * numbers the user had already checked, so verification was meaningless. On a
 * lossy radio in a crowd, that is the difference between working and not.
 *
 * The core seals its own state before it ever crosses the JNI boundary, under a
 * 32-byte key held here in the Android Keystore. This class therefore only moves
 * an opaque blob between the core and a file — it never sees a session key, and
 * neither does anything else in Kotlin.
 */
object SecureStateStore {

    private const val TAG = "MeshSecureState"
    private const val STATE_FILE = "mesh_secure_state.bin"
    private const val PREFS_NAME = "meshline_state"
    private const val KEY_VAULT_SECRET = "vault_key_wrapped"
    private const val KEYSTORE_ALIAS = "meshline_state_wrapping_key"
    private const val VAULT_KEY_BYTES = 32

    /**
     * Hands the core its previously sealed state, if any.
     *
     * A blob that will not open is deleted rather than retried: it means the
     * Keystore key is gone or the file is damaged, and in both cases the honest
     * outcome is to start clean and re-handshake.
     */
    fun restore(context: Context): Boolean {
        val file = stateFile(context)
        if (!file.exists()) return false

        val key = vaultKey(context) ?: return false
        return try {
            val blob = file.readBytes()
            val restored = MeshCoreBridge.importState(key, blob)
            if (!restored) {
                Log.w(TAG, "Stored secure state could not be opened; discarding it.")
                file.delete()
            }
            restored
        } catch (e: Exception) {
            Log.w(TAG, "Could not read stored secure state.", e)
            false
        } finally {
            key.fill(0)
        }
    }

    /**
     * Asks the core to seal its current state and writes it out.
     *
     * The write goes to a temporary file first: a process killed mid-write must
     * not be able to leave a half-written blob where a complete one used to be,
     * because that would silently lose every session on the device.
     */
    fun persist(context: Context): Boolean {
        val key = vaultKey(context) ?: return false
        return try {
            val blob = MeshCoreBridge.exportState(key)
            if (blob == null) {
                Log.w(TAG, "Mesh core produced no state to persist.")
                return false
            }
            val target = stateFile(context)
            val temp = File(target.parentFile, "$STATE_FILE.tmp")
            temp.writeBytes(blob)
            if (!temp.renameTo(target)) {
                // renameTo will not overwrite on some filesystems.
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    Log.e(TAG, "Could not move new secure state into place.")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not persist secure state.", e)
            false
        } finally {
            key.fill(0)
        }
    }

    /**
     * Destroys the stored state and the key that protected it.
     *
     * Deleting the wrapping key matters as much as deleting the file: without
     * it, any copy of the file that was already taken off the device stays
     * unreadable forever.
     */
    fun wipe(context: Context) {
        stateFile(context).delete()
        File(stateFile(context).parentFile, "$STATE_FILE.tmp").delete()
        KeystoreVault.clear(context, PREFS_NAME, KEY_VAULT_SECRET, KEYSTORE_ALIAS)
    }

    private fun stateFile(context: Context) = File(context.applicationContext.filesDir, STATE_FILE)

    private fun vaultKey(context: Context): ByteArray? = KeystoreVault.getOrCreateSecret(
        context,
        PREFS_NAME,
        KEY_VAULT_SECRET,
        KEYSTORE_ALIAS,
        VAULT_KEY_BYTES
    )
}
