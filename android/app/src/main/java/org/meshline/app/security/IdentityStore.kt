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
 * Persists this device's long-lived mesh identity secret.
 *
 * The 32-byte Ed25519 secret is the node's identity: lose it and the device
 * becomes a stranger to every peer that knows it, and every message previously
 * addressed to it becomes undeliverable. It is therefore stored encrypted under
 * an AES key held in the Android Keystore, which keeps the raw key material off
 * the filesystem and inside hardware-backed storage where the device provides it.
 *
 * Only the wrapped ciphertext lands in SharedPreferences; the wrapping key
 * cannot be exported from the Keystore at all.
 */
object IdentityStore {

    private const val TAG = "MeshIdentityStore"
    private const val PREFS_NAME = "meshline_identity"
    private const val KEY_CIPHERTEXT = "identity_secret_wrapped"
    private const val KEYSTORE_ALIAS = "meshline_identity_wrapping_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /**
     * Returns the stored identity secret, or null if this is a first run or the
     * stored blob can no longer be decrypted.
     */
    fun load(context: Context): ByteArray? {
        val prefs = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_CIPHERTEXT, null) ?: return null

        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            if (blob.size <= IV_BYTES) {
                Log.w(TAG, "Stored identity blob is truncated; discarding.")
                return null
            }
            val iv = blob.copyOfRange(0, IV_BYTES)
            val ciphertext = blob.copyOfRange(IV_BYTES, blob.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey() ?: return null,
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // A wiped Keystore (factory reset, credential change, or restored
            // backup) makes the blob permanently unreadable. Treat it as a
            // first run rather than crashing the app on launch.
            Log.w(TAG, "Could not decrypt stored identity; a new one will be generated.", e)
            null
        }
    }

    /** Encrypts and stores the identity secret. Returns false if it could not be persisted. */
    fun save(context: Context, secret: ByteArray): Boolean {
        require(secret.size == 32) { "Identity secret must be 32 bytes, was ${secret.size}" }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey() ?: return false)
            val ciphertext = cipher.doFinal(secret)
            val blob = cipher.iv + ciphertext

            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CIPHERTEXT, Base64.encodeToString(blob, Base64.NO_WRAP))
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist mesh identity.", e)
            false
        }
    }

    /** Forgets this device's mesh identity. The next start generates a new one. */
    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_CIPHERTEXT)
            .apply()
        try {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
                .deleteEntry(KEYSTORE_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete wrapping key.", e)
        }
    }

    private fun wrappingKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        existing ?: generateWrappingKey()
    } catch (e: Exception) {
        Log.e(TAG, "Keystore unavailable.", e)
        null
    }

    private fun generateWrappingKey(): SecretKey {
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_PROVIDER
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not requiring user authentication: the relay
                // service must be able to sign and relay while the screen is
                // locked, which is exactly when a disaster mesh matters most.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }
}
