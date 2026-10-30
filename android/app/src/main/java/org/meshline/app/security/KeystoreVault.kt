package org.meshline.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encryption of local data under keys the Android Keystore will not export.
 *
 * Everything this app keeps between runs — the mesh identity, the sealed session
 * and group state, the message history — is wrapped through here. Keeping one
 * implementation rather than three means the awkward parts (a Keystore that can
 * be wiped out from under you, a GCM nonce that must never repeat) are handled
 * once and the same way everywhere.
 *
 * The wrapping keys are generated in the Keystore and never leave it. What lands
 * on the filesystem is only ciphertext, so a phone that is imaged rather than
 * unlocked yields nothing readable.
 */
object KeystoreVault {

    private const val TAG = "MeshKeystoreVault"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /**
     * Encrypts under the Keystore key named [alias], creating it on first use.
     * Output is `iv || ciphertext`. Returns null if the Keystore is unusable,
     * which callers must treat as "could not store", never as "store in the
     * clear instead".
     */
    fun wrap(alias: String, plaintext: ByteArray): ByteArray? {
        val key = keyFor(alias) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.iv + cipher.doFinal(plaintext)
        } catch (e: Exception) {
            Log.e(TAG, "Could not encrypt under '$alias'.", e)
            null
        }
    }

    /**
     * Reverses [wrap]. Returns null when the blob is truncated, was tampered
     * with, or the Keystore key is gone — which happens after a factory reset,
     * a credential change, or a restored backup. All of those mean the same
     * thing to a caller: this data is unrecoverable, start over.
     */
    fun unwrap(alias: String, blob: ByteArray): ByteArray? {
        if (blob.size <= IV_BYTES) {
            Log.w(TAG, "Stored blob for '$alias' is truncated; discarding.")
            return null
        }
        val key = keyFor(alias) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, blob.copyOfRange(0, IV_BYTES))
            )
            cipher.doFinal(blob.copyOfRange(IV_BYTES, blob.size))
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt data stored under '$alias'.", e)
            null
        }
    }

    /** Reads a wrapped blob out of shared preferences. */
    fun loadWrapped(context: Context, prefsName: String, prefKey: String, alias: String): ByteArray? {
        val stored = prefs(context, prefsName).getString(prefKey, null) ?: return null
        return try {
            unwrap(alias, Base64.decode(stored, Base64.NO_WRAP))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Stored value for '$prefKey' is not valid base64; discarding.", e)
            null
        }
    }

    /**
     * Wraps and stores a blob. Uses `commit` rather than `apply` because the
     * caller needs to know the write actually happened before it treats the
     * secret as durable.
     */
    fun saveWrapped(
        context: Context,
        prefsName: String,
        prefKey: String,
        alias: String,
        plaintext: ByteArray
    ): Boolean {
        val wrapped = wrap(alias, plaintext) ?: return false
        return prefs(context, prefsName)
            .edit()
            .putString(prefKey, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .commit()
    }

    /**
     * Returns a persistent random secret of [sizeBytes], generating one on first
     * call. Used for keys that the native core needs as raw bytes — it seals its
     * own state, and this is where the key to that seal lives.
     *
     * The caller owns the returned array and should zero it once used.
     */
    fun getOrCreateSecret(
        context: Context,
        prefsName: String,
        prefKey: String,
        alias: String,
        sizeBytes: Int
    ): ByteArray? {
        loadWrapped(context, prefsName, prefKey, alias)?.let { existing ->
            if (existing.size == sizeBytes) return existing
            Log.w(TAG, "Stored secret '$prefKey' has the wrong size; regenerating.")
            existing.fill(0)
        }

        val fresh = ByteArray(sizeBytes)
        java.security.SecureRandom().nextBytes(fresh)
        if (!saveWrapped(context, prefsName, prefKey, alias, fresh)) {
            fresh.fill(0)
            return null
        }
        return fresh
    }

    /** Removes a stored blob and destroys the key that protected it. */
    fun clear(context: Context, prefsName: String, prefKey: String, alias: String) {
        prefs(context, prefsName).edit().remove(prefKey).commit()
        deleteKey(alias)
    }

    /**
     * Destroys a wrapping key. Anything still encrypted under it becomes
     * permanently unreadable, which is the point when wiping.
     */
    fun deleteKey(alias: String) {
        try {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.deleteEntry(alias)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete wrapping key '$alias'.", e)
        }
    }

    private fun prefs(context: Context, name: String) =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun keyFor(alias: String): SecretKey? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey) ?: generateKey(alias)
    } catch (e: Exception) {
        Log.e(TAG, "Keystore unavailable.", e)
        null
    }

    private fun generateKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not requiring user authentication: the relay
                // service must be able to sign, verify, and relay while the
                // screen is locked, which is exactly when this matters most.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
