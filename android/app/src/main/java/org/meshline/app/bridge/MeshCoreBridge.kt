package org.meshline.app.bridge

import android.content.Context
import android.util.Log
import org.meshline.app.security.IdentityStore
import org.meshline.app.security.SecureStateStore

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

    /** For the durable writes this bridge makes before releasing anything. */
    @Volatile
    private var appContext: Context? = null

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

    /**
     * A packet ready to transmit, with the id it carries on the wire. That id is
     * the one to store in history and the outbox: it is what the recipient's ACK
     * will name, and an id invented locally could never match one.
     */
    class CreatedPacket(val msgIdHex: String, val bytes: ByteArray)

    /** True only when the core is loaded and a mesh identity is active. */
    fun isReady(): Boolean = status == Status.READY

    /**
     * Loads or creates this device's mesh identity and starts the core.
     * Safe to call repeatedly; only the first call does work.
     */
    @Synchronized
    fun initialise(context: Context): Boolean {
        if (status == Status.READY) return true
        appContext = context.applicationContext
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

    /**
     * Build a distress packet, attaching a position only if one was measured.
     *
     * Nulls mean "no fix". Zero is a real coordinate in the Gulf of Guinea,
     * so absence must be carried explicitly - passing 0f previously made
     * every fixless SOS claim a position off the coast of Africa.
     */
    fun createSos(
        message: String,
        latitude: Double?,
        longitude: Double?,
        accuracyMeters: Float?,
    ): ByteArray? = guarded {
        val hasFix = latitude != null && longitude != null
        nativeCreateSos(
            message,
            hasFix,
            latitude?.toFloat() ?: 0f,
            longitude?.toFloat() ?: 0f,
            accuracyMeters?.toInt() ?: 0,
        )
    }

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
     *
     * Null also means the nonce reservation could not be made durable. May write
     * to disk, so call it off the main thread.
     */
    fun createChat(recipientIdHex: String, message: String): ByteArray? = guarded {
        ensureNonceReservation()
        nativeCreateChat(recipientIdHex, message)
    }

    /** [createSos], with the packet's canonical id. */
    fun createSosPacket(
        message: String,
        latitude: Double?,
        longitude: Double?,
        accuracyMeters: Float?,
    ): CreatedPacket? = withPacketId(createSos(message, latitude, longitude, accuracyMeters))

    /** [createChat], with the packet's canonical id. Null still means "not sent". */
    fun createChatPacket(recipientIdHex: String, message: String): CreatedPacket? =
        withPacketId(createChat(recipientIdHex, message))

    /** [createGroupChat], with the packet's canonical id. Null still means "not sent". */
    fun createGroupChatPacket(groupIdHex: String, message: String): CreatedPacket? =
        withPacketId(createGroupChat(groupIdHex, message))

    /** The canonical id of a packet this device built. Null if it does not parse. */
    fun packetId(packet: ByteArray): String? = guarded { nativePacketId(packet) }

    private fun withPacketId(packet: ByteArray?): CreatedPacket? {
        if (packet == null) return null
        val id = packetId(packet) ?: return null
        return CreatedPacket(id, packet)
    }

    fun beginHandshake(peerIdHex: String): ByteArray? =
        guarded { nativeBeginHandshake(peerIdHex) }

    fun hasSession(peerIdHex: String): Boolean =
        guarded { nativeHasSession(peerIdHex) } ?: false

    // -----------------------------------------------------------------------
    // Out-of-band verification
    // -----------------------------------------------------------------------

    /**
     * The safety number to compare with a peer in person, as twelve
     * space-separated groups of five digits. Null when there is no session, in
     * which case there is no authenticated key to derive it from and the UI must
     * not show anything that looks like one.
     */
    fun safetyNumber(peerIdHex: String): String? =
        guarded { nativeSafetyNumber(peerIdHex) }

    /** Records the user's decision after they compared safety numbers. */
    fun setPeerVerified(peerIdHex: String, verified: Boolean): Boolean =
        guarded { nativeSetPeerVerified(peerIdHex, verified) } ?: false

    fun isPeerVerified(peerIdHex: String): Boolean =
        guarded { nativeIsPeerVerified(peerIdHex) } ?: false

    // -----------------------------------------------------------------------
    // Groups
    // -----------------------------------------------------------------------

    /** Creates a group with this device as admin. Returns its id, or null on failure. */
    fun createGroup(name: String): String? = guarded { nativeCreateGroup(name) }

    /**
     * Adds a peer to a group, returning the invite packet to send. Null means
     * the invite was not created — most often because there is no pairwise
     * session with that peer yet, which is a precondition rather than a bug.
     */
    fun inviteToGroup(groupIdHex: String, peerIdHex: String): ByteArray? = guarded {
        ensureNonceReservation()
        nativeInviteToGroup(groupIdHex, peerIdHex)
    }

    /**
     * Builds an encrypted group message, or null when this device holds no key
     * for the group. Null must be treated as "not sent".
     */
    fun createGroupChat(groupIdHex: String, message: String): ByteArray? =
        guarded { nativeCreateGroupChat(groupIdHex, message) }

    /**
     * Removes a member and rotates the group key, returning JSON describing the
     * invites to send and any members that could not be reached.
     */
    fun removeFromGroup(groupIdHex: String, peerIdHex: String): String? = guarded {
        ensureNonceReservation()
        nativeRemoveFromGroup(groupIdHex, peerIdHex)
    }

    /** Rotates a group key without changing membership. */
    fun rekeyGroup(groupIdHex: String): String? = guarded {
        ensureNonceReservation()
        nativeRekeyGroup(groupIdHex)
    }

    fun leaveGroup(groupIdHex: String): Boolean =
        guarded { nativeLeaveGroup(groupIdHex) } ?: false

    fun groupsJson(): String? = guarded { nativeGroupsJson() }

    // -----------------------------------------------------------------------
    // Secure state at rest
    // -----------------------------------------------------------------------

    /**
     * Seals sessions, group keys, and verifications under a caller-held key.
     * Only [SecureStateStore] may call this: export, durable write and
     * [commitState] have to happen as one step.
     */
    fun exportState(vaultKey: ByteArray): ByteArray? = guarded { nativeExportState(vaultKey) }

    /** The blob from the last [exportState] is on disk, and only then call this. */
    fun commitState() {
        guarded { nativeCommitState() }
    }

    /** Restores state sealed by [exportState]. False means it could not be opened. */
    fun importState(vaultKey: ByteArray, blob: ByteArray): Boolean =
        guarded { nativeImportState(vaultKey, blob) } ?: false

    /** Forgets every session, group key, and verification held by the core. */
    fun wipeSecureState() {
        guarded { nativeWipeSecureState() }
    }

    /**
     * Verifies and processes an inbound frame, returning a JSON description of
     * what to do next. A null return means the packet failed authentication and
     * must be dropped.
     *
     * For an ACK, mark `acked_message_id` delivered, and only when it is present:
     * the core sets it only for an ACK signed by the node the message was sent
     * to. `msg_id` is the ACK packet's own id and matches no message.
     *
     * Anything this returns is safe to show and to ACK: the replay state that
     * accepted it is durable first, so a crash cannot make a message that was
     * shown acceptable again. May write to disk, so call it off the main thread.
     * A failed write is logged and the message still delivered, because losing
     * a message on a full disk is worse than a replay that also needs a crash;
     * see docs/decisions/2026-10-31-session-nonce-safety.md.
     */
    fun processIncoming(rawPacket: ByteArray): String? = guarded {
        val result = nativeProcessIncoming(rawPacket)
        if (nativeHasUncommittedReceives() && !persistSecureState()) {
            Log.e(TAG, "Replay state is not durable; a crash could reopen this message.")
        }
        result
    }

    fun activePinsJson(): String? = guarded { nativeActivePinsJson() }

    fun updateBattery(batteryLevel: Int, isCharging: Boolean) {
        guarded { nativeUpdateBattery(batteryLevel, isCharging) }
    }

    /**
     * Tops up the durable nonce reservation before a pairwise encrypt. If the
     * write fails the core refuses the encrypt, so failing here is failing closed.
     */
    private fun ensureNonceReservation() {
        if (nativeNeedsNonceReservation() && !persistSecureState()) {
            Log.w(TAG, "Could not reserve nonces durably.")
        }
    }

    private fun persistSecureState(): Boolean =
        appContext?.let { SecureStateStore.persist(it) } ?: false

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
    private external fun nativeCreateSos(
        message: String,
        hasLocation: Boolean,
        lat: Float,
        lon: Float,
        accuracyMeters: Int,
    ): ByteArray?
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
    private external fun nativePacketId(rawPacket: ByteArray): String?
    private external fun nativeHasSession(peerIdHex: String): Boolean
    private external fun nativeProcessIncoming(rawPacket: ByteArray): String?
    private external fun nativeActivePinsJson(): String?
    private external fun nativeUpdateBattery(batteryLevel: Int, isCharging: Boolean)
    private external fun nativeSafetyNumber(peerIdHex: String): String?
    private external fun nativeSetPeerVerified(peerIdHex: String, verified: Boolean): Boolean
    private external fun nativeIsPeerVerified(peerIdHex: String): Boolean
    private external fun nativeCreateGroup(name: String): String?
    private external fun nativeInviteToGroup(groupIdHex: String, peerIdHex: String): ByteArray?
    private external fun nativeCreateGroupChat(groupIdHex: String, message: String): ByteArray?
    private external fun nativeRemoveFromGroup(groupIdHex: String, peerIdHex: String): String?
    private external fun nativeRekeyGroup(groupIdHex: String): String?
    private external fun nativeLeaveGroup(groupIdHex: String): Boolean
    private external fun nativeGroupsJson(): String?
    private external fun nativeExportState(vaultKey: ByteArray): ByteArray?
    private external fun nativeImportState(vaultKey: ByteArray, blob: ByteArray): Boolean
    private external fun nativeCommitState()
    private external fun nativeNeedsNonceReservation(): Boolean
    private external fun nativeHasUncommittedReceives(): Boolean
    private external fun nativeWipeSecureState()
}
