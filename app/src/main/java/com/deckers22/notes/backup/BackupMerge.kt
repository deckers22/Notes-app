package com.deckers22.notes.backup

import com.deckers22.notes.data.Note

data class ImportSummary(val added: Int, val updated: Int, val unchanged: Int, val hiddenSkipped: Int) {
    val written: Int get() = added + updated
}

class ImportPlan(val toWrite: List<Note>, val summary: ImportSummary)

/**
 * Decides what an import writes. Notes that are not in the app yet are added. A note that
 * exists in both is replaced only when the backup's version was edited more recently.
 * Nothing is ever removed.
 */
object BackupMerge {
    fun plan(incoming: List<Note>, includeHidden: Boolean, existingUpdatedAt: (id: String) -> Long?): ImportPlan {
        // A file should not hold the same note twice; if it does, the newest version counts.
        val unique = incoming.groupBy { it.id }.map { (_, versions) -> versions.maxBy { it.updatedAt } }
        val toWrite = mutableListOf<Note>()
        var added = 0
        var updated = 0
        var unchanged = 0
        var hiddenSkipped = 0
        for (note in unique) {
            if (note.hidden && !includeHidden) {
                hiddenSkipped++
                continue
            }
            val existing = existingUpdatedAt(note.id)
            when {
                existing == null -> { added++; toWrite += note }
                note.updatedAt > existing -> { updated++; toWrite += note }
                else -> unchanged++
            }
        }
        return ImportPlan(toWrite, ImportSummary(added, updated, unchanged, hiddenSkipped))
    }
}
