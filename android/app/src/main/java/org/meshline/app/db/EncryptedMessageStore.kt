package org.meshline.app.db

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.meshline.app.security.KeystoreVault

/**
 * Message history, encrypted at rest.
 *
 * Messages were previously held only in memory, which was private in the narrow
 * sense that nothing was written down, but meant the entire conversation
 * vanished whenever Android reclaimed the process — including messages that
 * arrived while the app was backgrounded. Writing them as plaintext would have
 * been worse: a message is decrypted on arrival, so a plaintext store hands an
 * examined phone exactly what the transport encryption protected in flight.
 *
 * So the history is persisted, and every byte of it is encrypted under a
 * hardware-backed Keystore key. The file is rewritten whole rather than appended
 * to, which keeps a deleted message actually deleted instead of leaving it in a
 * log behind the current state.
 */
object EncryptedMessageStore {

    private const val TAG = "MeshMessageStore"
    private const val STORE_FILE = "mesh_messages.bin"
    private const val KEYSTORE_ALIAS = "meshline_message_store_key"

    /**
     * How many messages are kept. A phone relaying for a crowd will see a lot of
     * traffic, and an unbounded history is both a storage problem and, after a
     * seizure, a larger disclosure than anyone intended.
     */
    const val MAX_STORED_MESSAGES = 2000

    /** Reads the stored history. Returns empty when there is none or it is unreadable. */
    fun load(context: Context): List<MessageEntity> {
        val file = storeFile(context)
        if (!file.exists()) return emptyList()

        val plaintext = try {
            KeystoreVault.unwrap(KEYSTORE_ALIAS, file.readBytes())
        } catch (e: Exception) {
            Log.w(TAG, "Could not read message history.", e)
            null
        }

        if (plaintext == null) {
            // Unreadable means the key is gone or the file is damaged. Remove it
            // rather than leaving something we will fail on at every launch.
            file.delete()
            return emptyList()
        }

        return try {
            parse(String(plaintext, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Message history is malformed; discarding it.", e)
            file.delete()
            emptyList()
        } finally {
            plaintext.fill(0)
        }
    }

    /**
     * Writes the history, keeping only the most recent [MAX_STORED_MESSAGES].
     * The temporary-file dance stops a kill mid-write from destroying history
     * that was already safely stored.
     */
    fun save(context: Context, messages: Collection<MessageEntity>): Boolean {
        val trimmed = messages
            .sortedBy { it.timestampMillis }
            .takeLast(MAX_STORED_MESSAGES)

        return try {
            val json = serialize(trimmed).toByteArray(Charsets.UTF_8)
            val wrapped = KeystoreVault.wrap(KEYSTORE_ALIAS, json)
            json.fill(0)

            if (wrapped == null) {
                Log.e(TAG, "Message history could not be encrypted; not writing it.")
                return false
            }

            val target = storeFile(context)
            val temp = File(target.parentFile, "$STORE_FILE.tmp")
            temp.writeBytes(wrapped)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not write message history.", e)
            false
        }
    }

    /** Deletes the history and the key protecting it. */
    fun wipe(context: Context) {
        storeFile(context).delete()
        File(storeFile(context).parentFile, "$STORE_FILE.tmp").delete()
        KeystoreVault.deleteKey(KEYSTORE_ALIAS)
    }

    private fun storeFile(context: Context) = File(context.applicationContext.filesDir, STORE_FILE)

    internal fun serialize(messages: Collection<MessageEntity>): String {
        val array = JSONArray()
        messages.forEach { message ->
            array.put(
                JSONObject().apply {
                    put("msg_id", message.msgId)
                    put("sender_id", message.senderId)
                    put("recipient_id", message.recipientId)
                    put("payload_text", message.payloadText)
                    put("ttl", message.ttl)
                    put("timestamp_millis", message.timestampMillis)
                    put("packet_type", message.packetType)
                    put("status", message.status.name)
                    put("encrypted", message.encrypted)
                    put("group_id", message.groupId ?: JSONObject.NULL)
                }
            )
        }
        return array.toString()
    }

    internal fun parse(json: String): List<MessageEntity> {
        val array = JSONArray(json)
        val out = ArrayList<MessageEntity>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            out += MessageEntity(
                msgId = obj.getString("msg_id"),
                senderId = obj.getString("sender_id"),
                recipientId = obj.getString("recipient_id"),
                payloadText = obj.getString("payload_text"),
                ttl = obj.getInt("ttl"),
                timestampMillis = obj.getLong("timestamp_millis"),
                packetType = obj.getString("packet_type"),
                status = runCatching { MessageStatus.valueOf(obj.getString("status")) }
                    .getOrDefault(MessageStatus.SENT),
                encrypted = obj.optBoolean("encrypted", false),
                groupId = if (obj.isNull("group_id")) null else obj.optString("group_id")
                    .takeIf { it.isNotEmpty() }
            )
        }
        return out
    }
}
