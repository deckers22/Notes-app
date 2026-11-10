package com.deckers22.notes.backup

import com.deckers22.notes.data.Note
import com.deckers22.notes.security.VaultCrypto
import java.security.GeneralSecurityException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class BackupException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_A_BACKUP, UNSUPPORTED_VERSION, WRONG_PASSWORD }
}

/**
 * Reads and writes backup files. A backup is a small JSON envelope around the notes, which
 * are encrypted (AES-256-GCM) with a key derived from the backup password (PBKDF2). Notes
 * are passed in and returned readable; hidden notes are not vault-encrypted inside the file,
 * the backup password protects them instead.
 */
object BackupCodec {
    const val MIN_PASSWORD_LENGTH = 8

    // The file leaves the device, so the password can be attacked offline: use a high count.
    const val ITERATIONS = 600_000
    private const val MAX_ITERATIONS = 10_000_000
    private const val FORMAT = "notes-backup"
    private const val VERSION = 1

    fun encode(
        notes: List<Note>,
        password: String,
        exportedAt: Long = System.currentTimeMillis(),
        iterations: Int = ITERATIONS,
    ): String {
        val payload = JSONObject()
            .put("exportedAt", exportedAt)
            .put("notes", JSONArray().apply { notes.forEach { put(noteToJson(it)) } })
        val salt = VaultCrypto.randomBytes(16)
        val key = VaultCrypto.deriveKey(password, salt, iterations)
        val data = VaultCrypto.encrypt(key, payload.toString().toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("kdf", "PBKDF2WithHmacSHA256")
            .put("iterations", iterations)
            .put("salt", VaultCrypto.encode(salt))
            .put("data", VaultCrypto.encode(data))
            .toString()
    }

    /** Quick check, without the password, whether [text] is a backup file of this app. */
    fun looksLikeBackup(text: String): Boolean = try {
        JSONObject(text).optString("format") == FORMAT
    } catch (e: JSONException) {
        false
    }

    /** Throws [BackupException] when the file or the password is not right. */
    fun decode(text: String, password: String): List<Note> {
        val salt: ByteArray
        val data: ByteArray
        val iterations: Int
        try {
            val envelope = JSONObject(text)
            if (envelope.optString("format") != FORMAT) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
            if (envelope.getInt("version") > VERSION) throw BackupException(BackupException.Reason.UNSUPPORTED_VERSION)
            iterations = envelope.getInt("iterations")
            salt = VaultCrypto.decode(envelope.getString("salt"))
            data = VaultCrypto.decode(envelope.getString("data"))
        } catch (e: JSONException) {
            throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        }
        if (iterations !in 1..MAX_ITERATIONS || data.size <= VaultCrypto.IV_BYTES) {
            throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        }

        val plain = try {
            VaultCrypto.decrypt(VaultCrypto.deriveKey(password, salt, iterations), data)
        } catch (e: GeneralSecurityException) {
            throw BackupException(BackupException.Reason.WRONG_PASSWORD)
        }
        return try {
            val notes = JSONObject(String(plain, Charsets.UTF_8)).getJSONArray("notes")
            List(notes.length()) { noteFromJson(notes.getJSONObject(it)) }
        } catch (e: JSONException) {
            throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        }
    }

    private fun noteToJson(note: Note): JSONObject {
        val json = JSONObject()
            .put("id", note.id)
            .put("title", note.title)
            .put("content", note.content)
            .put("hidden", note.hidden)
            .put("pinned", note.pinned)
            .put("color", note.color)
            .put("checklist", note.checklist)
            .put("createdAt", note.createdAt)
            .put("updatedAt", note.updatedAt)
        note.deletedAt?.let { json.put("deletedAt", it) }
        return json
    }

    private fun noteFromJson(json: JSONObject): Note {
        val id = json.getString("id")
        if (id.isBlank()) throw JSONException("Note without id")
        val updatedAt = json.getLong("updatedAt")
        return Note(
            id = id,
            title = json.optString("title"),
            content = json.optString("content"),
            hidden = json.optBoolean("hidden"),
            pinned = json.optBoolean("pinned"),
            color = json.optInt("color").coerceAtLeast(0),
            createdAt = json.optLong("createdAt", updatedAt),
            updatedAt = updatedAt,
            deletedAt = if (json.isNull("deletedAt")) null else json.getLong("deletedAt"),
            checklist = json.optBoolean("checklist"),
        )
    }
}
