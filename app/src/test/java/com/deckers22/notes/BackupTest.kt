package com.deckers22.notes

import com.deckers22.notes.backup.BackupCodec
import com.deckers22.notes.backup.BackupException
import com.deckers22.notes.backup.BackupMerge
import com.deckers22.notes.backup.ImportSummary
import com.deckers22.notes.data.Note
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    // Few iterations keep the tests fast; the real default is exercised once below.
    private val fast = 1_000

    private fun note(id: String, updatedAt: Long = 100, hidden: Boolean = false, title: String = "t-$id") =
        Note(id = id, title = title, content = "c-$id", hidden = hidden, createdAt = 1, updatedAt = updatedAt)

    private val sample = listOf(
        Note(
            id = "a", title = "Boodschappen ✓", content = "[ ] Melk\n[x] Eieren", hidden = false, pinned = true,
            color = 3, createdAt = 10, updatedAt = 20, deletedAt = null, checklist = true,
        ),
        Note(
            id = "b", title = "", content = "Geheim \"quoted\" \\ text\nline 2", hidden = true, pinned = false,
            color = 0, createdAt = 30, updatedAt = 40, deletedAt = 50,
        ),
    )

    @Test
    fun roundTripKeepsEveryField() {
        val file = BackupCodec.encode(sample, "correct horse", iterations = fast)
        assertEquals(sample, BackupCodec.decode(file, "correct horse"))
    }

    @Test
    fun roundTripWithDefaultIterations() {
        val file = BackupCodec.encode(sample, "correct horse")
        assertEquals(BackupCodec.ITERATIONS, JSONObject(file).getInt("iterations"))
        assertEquals(sample, BackupCodec.decode(file, "correct horse"))
    }

    @Test
    fun fileDoesNotContainReadableNotes() {
        val file = BackupCodec.encode(sample, "correct horse", iterations = fast)
        assertFalse(file.contains("Geheim"))
        assertFalse(file.contains("Boodschappen"))
        assertFalse(file.contains("Melk"))
    }

    @Test
    fun emptyBackupRoundTrip() {
        val file = BackupCodec.encode(emptyList(), "correct horse", iterations = fast)
        assertEquals(emptyList<Note>(), BackupCodec.decode(file, "correct horse"))
    }

    private fun reasonOf(block: () -> Unit) = assertThrows(BackupException::class.java, block).reason

    @Test
    fun wrongPasswordIsReported() {
        val file = BackupCodec.encode(sample, "correct horse", iterations = fast)
        assertEquals(BackupException.Reason.WRONG_PASSWORD, reasonOf { BackupCodec.decode(file, "wrong horse") })
    }

    @Test
    fun alteredDataIsRejected() {
        val json = JSONObject(BackupCodec.encode(sample, "correct horse", iterations = fast))
        val data = json.getString("data")
        val flipped = (if (data[20] == 'A') 'B' else 'A')
        json.put("data", data.substring(0, 20) + flipped + data.substring(21))
        assertEquals(BackupException.Reason.WRONG_PASSWORD, reasonOf { BackupCodec.decode(json.toString(), "correct horse") })
    }

    @Test
    fun otherFilesAreNotBackups() {
        for (text in listOf("", "hello", "{}", "[1,2]", "{\"format\":\"something-else\"}", "{\"format\":\"notes-backup\"}")) {
            assertEquals(text, BackupException.Reason.NOT_A_BACKUP, reasonOf { BackupCodec.decode(text, "pw") })
        }
        assertFalse(BackupCodec.looksLikeBackup("hello"))
        assertFalse(BackupCodec.looksLikeBackup("{}"))
        assertTrue(BackupCodec.looksLikeBackup(BackupCodec.encode(sample, "correct horse", iterations = fast)))
    }

    @Test
    fun newerFormatVersionIsReported() {
        val json = JSONObject(BackupCodec.encode(sample, "correct horse", iterations = fast)).put("version", 2)
        assertEquals(BackupException.Reason.UNSUPPORTED_VERSION, reasonOf { BackupCodec.decode(json.toString(), "correct horse") })
    }

    @Test
    fun mergeAddsNewAndKeepsNewest() {
        val existing = mapOf("same" to 100L, "olderInApp" to 50L, "newerInApp" to 200L)
        val incoming = listOf(note("new"), note("same"), note("olderInApp"), note("newerInApp"))
        val plan = BackupMerge.plan(incoming, includeHidden = true) { existing[it] }
        assertEquals(listOf("new", "olderInApp"), plan.toWrite.map { it.id })
        assertEquals(ImportSummary(added = 1, updated = 1, unchanged = 2, hiddenSkipped = 0), plan.summary)
    }

    @Test
    fun mergeCanLeaveOutHiddenNotes() {
        val incoming = listOf(note("n"), note("h1", hidden = true), note("h2", hidden = true))
        val without = BackupMerge.plan(incoming, includeHidden = false) { null }
        assertEquals(listOf("n"), without.toWrite.map { it.id })
        assertEquals(ImportSummary(added = 1, updated = 0, unchanged = 0, hiddenSkipped = 2), without.summary)
        val with = BackupMerge.plan(incoming, includeHidden = true) { null }
        assertEquals(3, with.toWrite.size)
    }

    @Test
    fun mergeUsesNewestVersionOfDuplicates() {
        val incoming = listOf(note("d", updatedAt = 5, title = "old"), note("d", updatedAt = 9, title = "new"))
        val plan = BackupMerge.plan(incoming, includeHidden = true) { null }
        assertEquals(listOf("new"), plan.toWrite.map { it.title })
        assertEquals(1, plan.summary.added)
    }
}
