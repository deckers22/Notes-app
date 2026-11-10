package com.deckers22.notes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Writes are blocking on purpose: the repository runs them in order on one background thread. */
@Dao
interface NoteDao {
    @Query("SELECT * FROM notes")
    fun observeAll(): Flow<List<Note>>

    @Query("SELECT * FROM notes")
    fun getAll(): List<Note>

    @Query("SELECT * FROM notes WHERE id = :id")
    fun get(id: String): Note?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(note: Note)

    /** Runs as one transaction: either all notes are written or none. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(notes: List<Note>)

    @Query("UPDATE notes SET pinned = :pinned WHERE id = :id")
    fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE notes SET deletedAt = :deletedAt WHERE id = :id")
    fun setDeletedAt(id: String, deletedAt: Long?)

    @Query("DELETE FROM notes WHERE id = :id")
    fun delete(id: String)

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL AND (hidden = 0 OR :includeHidden)")
    fun emptyTrash(includeHidden: Boolean)

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL AND deletedAt < :cutoff")
    fun purgeTrashedBefore(cutoff: Long)

    @Query("DELETE FROM notes WHERE hidden = 1")
    fun deleteHidden()
}
