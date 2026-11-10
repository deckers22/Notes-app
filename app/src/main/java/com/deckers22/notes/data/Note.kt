package com.deckers22.notes.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A note. For hidden notes the database row holds [title] and [content] encrypted; the
 * repository decrypts them before they reach the UI.
 */
@Entity(tableName = "notes")
data class Note(
    @PrimaryKey val id: String,
    val title: String = "",
    val content: String = "",
    val hidden: Boolean = false,
    val pinned: Boolean = false,
    val color: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    /** True when [content] is a checklist in the line format of [Checklist]. */
    @ColumnInfo(defaultValue = "0") val checklist: Boolean = false,
)

/** The note text without checklist markers, for searching. */
fun Note.plainContent(): String = if (checklist) Checklist.toText(content) else content

