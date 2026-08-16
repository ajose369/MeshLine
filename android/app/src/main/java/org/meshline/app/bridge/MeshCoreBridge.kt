package org.meshline.app.bridge

import android.content.Context
import android.util.Log
import org.meshline.app.security.IdentityStore

/**
 * Kotlin façade over the `meshline_ffi` native library.
 *
 * There is deliberately **no fallback implementation**. An earlier version of
 * this bridge dropped to a "pure Kotlin mode" that emitted unsigned, unencrypted
 * pipe-delimited packets when the native library was missing. That silently
 * turned an app advertising signed, encrypted emergency messaging into one that
 * broadcast plaintext, with no indication to the user. A mesh node that cannot
 * sign or verify is not a degraded node, it is a dangerous one, so every method
 * here fails closed and [isReady] gates the entire UI.
 */
object MeshCoreBridge {

    private const val TAG = "MeshCoreBridge"

    /** Why the native core is unavailable, if it is. */
    enum class Status { UNINITIALISED, READY, LIBRARY_MISSING, INIT_FAILED }

    @Volatile
    var status: Status = Status.UNINITIALISED
        private set

    private val libraryLoaded: Boolean = try {
        System.loadLibrary("meshline_ffi")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(
            TAG,
            "Native library meshline_ffi is missing from this build. " +
                "The mesh cannot sign, verify, or encrypt and will stay disabled.",
            e
        )
        false
    }

    /** True only when the core is loaded and a mesh identity is active. */
    fun isReady(): Boolean = status == Status.READY

    /**
     * Loads or creates this device's mesh identity and starts the core.
     * Safe to call repeatedly; only the first call does work.
     */
    @Synchronized
    fun initialise(context: Context): Boolean {
        if (status == Status.READY) return true
        if (!libraryLoaded) {
            status = Status.LIBRARY_MISSING
            return false
        }

        return try {
            val stored = IdentityStore.load(context)
            val inUse = nativeInit(stored)

            if (inUse == null || inUse.size != 32) {
                Log.e(TAG, "Native core failed to initialise a mesh identity.")
                status = Status.INIT_FAILED
                return false
            }

            // First run, or the stored blob could not be decrypted: persist the
            // identity the core actually adopted so it survives a restart.
            if (stored == null || !stored.contentEquals(inUse)) {
                if (!IdentityStore.save(context, inUse)) {
                    Log.e(TAG, "Mesh identity could not be persisted; it would change on restart.")
                    status = Status.INIT_FAILED
                    return false
                }
            }
            // Do not leave key material sitting in a heap array.
            inUse.fill(0)
            stored?.fill(0)

            status = Status.READY
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native symbol missing.", e)
            status = Status.LIBRARY_MISSING
            false
        } catch (e: Exception) {
            Log.e(TAG, "Mesh core initialisation failed.", e)
            status = Status.INIT_FAILED
            false
        }
    }

    /** A short, human-facing explanation for the UI when the mesh is unavailable. */
    fun unavailableReason(): String = when (status) {
        Status.READY -> ""
        Status.LIBRARY_MISSING ->
            "This build is missing its secure messaging core, so MeshLine cannot " +
                "send or verify messages. Please reinstall from an official release."
        Status.INIT_FAILED ->
            "MeshLine could not set up this device's secure identity. " +
                "Restart the app; if it keeps failing, reinstall."
        Status.UNINITIALISED -> "MeshLine is still starting up."
    }

    // -----------------------------------------------------------------------
    // Guarded API. Every one of these returns null/false rather than throwing,
    // and returns nothing usable unless the core is READY.
    // -----------------------------------------------------------------------

    fun nodeIdHex(): String? = guarded { nativeNodeIdHex() }

    fun publicKey(): ByteArray? = guarded { nativePublicKey() }

    fun createSos(message: String, latitude: Float, longitude: Float): ByteArray? =
        guarded { nativeCreateSos(message, latitude, longitude) }

    fun createPin(
        pinId: ByteArray,
        pinType: Int,
        latitude: Float,
        longitude: Float,
        label: String,
        expiresInSecs: Long
    ): ByteArray? = guarded {
        nativeCreatePin(pinId, pinType, latitude, longitude, label, expiresInSecs)
    }

    /**
     * Builds an encrypted chat packet, or null when no session exists with the
     * recipient. Callers must treat null as "not sent" and offer a handshake,
     * never as a cue to send the text some other way.
     */
    fun createChat(recipientIdHex: String, message: String): ByteArray? =
        guarded { nativeCreateChat(recipientIdHex, message) }

    fun beginHandshake(peerIdHex: String): ByteArray? =
        guarded { nativeBeginHandshake(peerIdHex) }

    fun hasSession(peerIdHex: String): Boolean =
        guarded { nativeHasSession(peerIdHex) } ?: false

    /**
     * Verifies and processes an inbound frame, returning a JSON description of
     * what to do next. A null return means the packet failed authentication and
     * must be dropped.
     */
    fun processIncoming(rawPacket: ByteArray): String? =
        guarded { nativeProcessIncoming(rawPacket) }

    fun activePinsJson(): String? = guarded { nativeActivePinsJson() }

    fun updateBattery(batteryLevel: Int, isCharging: Boolean) {
        guarded { nativeUpdateBattery(batteryLevel, isCharging) }
    }

    private inline fun <T> guarded(body: () -> T): T? {
        if (!isReady()) return null
        return try {
            body()
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Native call failed; disabling mesh core.", e)
            status = Status.LIBRARY_MISSING
            null
        } catch (e: Exception) {
            Log.e(TAG, "Native call threw.", e)
            null
        }
    }

    // -----------------------------------------------------------------------
    // Native declarations. Implemented in crates/meshline-ffi.
    // -----------------------------------------------------------------------

    private external fun nativeInit(secret: ByteArray?): ByteArray?
    private external fun nativeIsReady(): Boolean
    private external fun nativeNodeIdHex(): String?
    private external fun nativePublicKey(): ByteArray?
    private external fun nativeCreateSos(message: String, lat: Float, lon: Float): ByteArray?
    private external fun nativeCreatePin(
        pinIdBytes: ByteArray,
        pinTypeVal: Int,
        lat: Float,
        lon: Float,
        label: String,
        expiresInSecs: Long
    ): ByteArray?
    private external fun nativeCreateChat(recipientIdHex: String, message: String): ByteArray?
    private external fun nativeBeginHandshake(peerIdHex: String): ByteArray?
    private external fun nativeHasSession(peerIdHex: String): Boolean
    private external fun nativeProcessIncoming(rawPacket: ByteArray): String?
    private external fun nativeActivePinsJson(): String?
    private external fun nativeUpdateBattery(batteryLevel: Int, isCharging: Boolean)
}
