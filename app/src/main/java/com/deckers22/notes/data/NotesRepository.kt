package com.deckers22.notes.data

import com.deckers22.notes.backup.BackupMerge
import com.deckers22.notes.backup.ImportSummary
import com.deckers22.notes.security.VaultManager
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReadableNotes(val notes: List<Note>, val unreadableHidden: Int)

class NotesRepository(
    private val dao: NoteDao,
    private val vault: VaultManager,
    private val appScope: CoroutineScope,
) {
    // One thread for all database access keeps writes in the order they were requested
    // (e.g. "save" followed by "move to trash"), and lets reads see pending writes.
    private val dbThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /** All rows as stored; hidden notes are still encrypted. */
    val storedNotes: Flow<List<Note>> = dao.observeAll()

    /** Returns the readable note, or null when it is hidden and the vault is locked. */
    fun open(stored: Note): Note? {
        if (!stored.hidden) return stored
        val title = vault.decryptOrNull(stored.title) ?: return null
        val content = vault.decryptOrNull(stored.content) ?: return null
        return stored.copy(title = title, content = content)
    }

    /** Throws [com.deckers22.notes.security.VaultLockedException] for a hidden note while locked. */
    private fun seal(note: Note): Note =
        if (note.hidden) {
            note.copy(title = vault.encrypt(note.title), content = vault.encrypt(note.content))
        } else {
            note
        }

    suspend fun getStored(id: String): Note? = withContext(dbThread) { dao.get(id) }

    /**
     * Encrypts (if needed) right away and writes in the background on the app scope, so the
     * save still completes when the screen that asked for it is already gone.
     */
    fun save(note: Note) {
        val sealed = seal(note)
        write { dao.upsert(sealed) }
    }

    fun setPinned(id: String, pinned: Boolean) = write { dao.setPinned(id, pinned) }

    fun moveToTrash(id: String) = write { dao.setDeletedAt(id, System.currentTimeMillis()) }

    fun restoreFromTrash(id: String) = write { dao.setDeletedAt(id, null) }

    fun deleteForever(id: String) = write { dao.delete(id) }

    fun emptyTrash(includeHidden: Boolean) = write { dao.emptyTrash(includeHidden) }

    fun purgeOldTrash() = write {
        dao.purgeTrashedBefore(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TRASH_RETENTION_DAYS))
    }

    fun deleteAllHidden() = write { dao.deleteHidden() }

    /** Moves a note into or out of the vault. Needs the vault to be unlocked. */
    fun setHidden(id: String, hidden: Boolean) = write {
        val stored = dao.get(id) ?: return@write
        if (stored.hidden == hidden) return@write
        val readable = open(stored) ?: return@write
        // If the vault got locked in the meantime, seal() throws and the note stays as it was.
        runCatching { seal(readable.copy(hidden = hidden, pinned = false)) }.onSuccess(dao::upsert)
    }

    // ---- Backup ----

    /** Every note that can be read right now; hidden notes only while the vault is unlocked. */
    suspend fun readableNotes(): ReadableNotes = withContext(dbThread) {
        val stored = dao.getAll()
        val readable = stored.mapNotNull(::open)
        ReadableNotes(readable, unreadableHidden = stored.size - readable.size)
    }

    /**
     * Merges notes from a backup into the database; see [BackupMerge] for the rules. Throws
     * [com.deckers22.notes.security.VaultLockedException] (and writes nothing) when hidden
     * notes have to be imported while the vault is locked.
     */
    suspend fun importNotes(incoming: List<Note>, includeHidden: Boolean): ImportSummary =
        withContext(dbThread) {
            val plan = BackupMerge.plan(incoming, includeHidden) { id -> dao.get(id)?.updatedAt }
            dao.upsertAll(plan.toWrite.map(::seal))
            plan.summary
        }

    private fun write(block: () -> Unit) {
        appScope.launch(dbThread) { block() }
    }

    companion object {
        const val TRASH_RETENTION_DAYS = 30L
    }
}
