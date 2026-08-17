package org.meshline.app.security

/**
 * Persists this device's long-lived mesh identity secret.
 *
 * The 32-byte Ed25519 secret is the node's identity: lose it and the device
 * becomes a stranger to every peer that knows it, every message previously
 * addressed to it becomes undeliverable, and every safety number a contact has
 * already verified stops matching. It is therefore stored encrypted under a
 * hardware-backed Keystore key via [KeystoreVault], which keeps the raw key
 * material off the filesystem.
 *
 * The identity deliberately survives [org.meshline.app.db.StoreAndForwardManager]'s
 * panic wipe. Wiping is about making a seized device useless for reading
 * traffic, not about becoming unrecognisable to the people who verified you.
 */
object IdentityStore {

    private const val PREFS_NAME = "meshline_identity"
    private const val KEY_CIPHERTEXT = "identity_secret_wrapped"
    private const val KEYSTORE_ALIAS = "meshline_identity_wrapping_key"
    private const val SECRET_BYTES = 32

    /**
     * Returns the stored identity secret, or null if this is a first run or the
     * stored blob can no longer be decrypted.
     */
    fun load(context: android.content.Context): ByteArray? =
        KeystoreVault.loadWrapped(context, PREFS_NAME, KEY_CIPHERTEXT, KEYSTORE_ALIAS)
            ?.takeIf { it.size == SECRET_BYTES }

    /** Encrypts and stores the identity secret. Returns false if it could not be persisted. */
    fun save(context: android.content.Context, secret: ByteArray): Boolean {
        require(secret.size == SECRET_BYTES) {
            "Identity secret must be $SECRET_BYTES bytes, was ${secret.size}"
        }
        return KeystoreVault.saveWrapped(
            context,
            PREFS_NAME,
            KEY_CIPHERTEXT,
            KEYSTORE_ALIAS,
            secret
        )
    }

    /** Forgets this device's mesh identity. The next start generates a new one. */
    fun clear(context: android.content.Context) {
        KeystoreVault.clear(context, PREFS_NAME, KEY_CIPHERTEXT, KEYSTORE_ALIAS)
    }
}
