package com.deckers22.notes

import com.deckers22.notes.editor.TextSnapshot
import com.deckers22.notes.editor.UndoManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoManagerTest {
    private var now = 0L
    private fun manager(text: String = "", max: Int = 200) =
        UndoManager(TextSnapshot(text, text.length), maxEntries = max, groupTimeoutMs = 1_000) { now }

    private fun UndoManager.type(text: String, gapMs: Long = 100) {
        now += gapMs
        onChanged(TextSnapshot(text, text.length))
    }

    @Test
    fun nothingToUndoInitially() {
        val m = manager("abc")
        assertFalse(m.canUndo)
        assertFalse(m.canRedo)
        assertNull(m.undo())
        assertNull(m.redo())
    }

    @Test
    fun quickTypingIsUndoneAsOneStep() {
        val m = manager()
        m.type("h"); m.type("he"); m.type("hey")
        assertEquals("", m.undo()?.text)
        assertFalse(m.canUndo)
        assertEquals("hey", m.redo()?.text)
    }

    @Test
    fun pauseStartsNewStep() {
        val m = manager()
        m.type("a"); m.type("ab")
        m.type("abc", gapMs = 5_000)
        assertEquals("ab", m.undo()?.text)
        assertEquals("", m.undo()?.text)
    }

    @Test
    fun deletingAfterTypingIsSeparateStep() {
        val m = manager()
        m.type("a"); m.type("ab"); m.type("a")
        assertEquals("ab", m.undo()?.text)
        assertEquals("", m.undo()?.text)
    }

    @Test
    fun pasteIsItsOwnStep() {
        val m = manager()
        m.type("a")
        m.type("a pasted text")
        m.type("a pasted text!")
        assertEquals("a pasted text", m.undo()?.text)
        assertEquals("a", m.undo()?.text)
        assertEquals("", m.undo()?.text)
    }

    @Test
    fun newEditClearsRedo() {
        val m = manager()
        m.type("a")
        m.undo()
        assertTrue(m.canRedo)
        m.type("b")
        assertFalse(m.canRedo)
    }

    @Test
    fun editAfterUndoIsNotMergedIntoOlderStep() {
        val m = manager()
        m.type("a"); m.type("ab", gapMs = 5_000)
        m.undo()
        m.type("ax")
        assertEquals("a", m.undo()?.text)
        assertEquals("", m.undo()?.text)
    }

    @Test
    fun cursorMoveDoesNotCreateStepButIsRemembered() {
        val m = manager("hello")
        m.onChanged(TextSnapshot("hello", 2))
        assertFalse(m.canUndo)
        m.type("heXllo")
        assertEquals(TextSnapshot("hello", 2), m.undo())
    }

    @Test
    fun historyIsCapped() {
        val m = manager(max = 3)
        var text = ""
        repeat(10) { text += "word "; m.type(text, gapMs = 5_000) }
        var steps = 0
        while (m.undo() != null) steps++
        assertEquals(3, steps)
    }
}
