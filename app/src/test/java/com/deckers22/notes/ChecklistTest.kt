package com.deckers22.notes

import com.deckers22.notes.data.Checklist
import com.deckers22.notes.data.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecklistTest {
    @Test
    fun formatAndParseRoundTrip() {
        val items = listOf(
            ChecklistItem("Melk"),
            ChecklistItem("Eieren", checked = true),
            ChecklistItem(""),
            ChecklistItem("[x] looks like a marker"),
            ChecklistItem("  spaces kept  ", checked = true),
        )
        assertEquals(items, Checklist.parse(Checklist.format(items)))
    }

    @Test
    fun emptyContentHasNoItems() {
        assertEquals(emptyList<ChecklistItem>(), Checklist.parse(""))
        assertEquals("", Checklist.format(emptyList()))
    }

    @Test
    fun textBecomesOneOpenItemPerNonBlankLine() {
        val content = Checklist.fromText("Melk\n\n  Eieren \nBrood")
        assertEquals(
            listOf(ChecklistItem("Melk"), ChecklistItem("Eieren"), ChecklistItem("Brood")),
            Checklist.parse(content),
        )
    }

    @Test
    fun blankTextBecomesOneEmptyItem() {
        assertEquals(listOf(ChecklistItem("")), Checklist.parse(Checklist.fromText("  \n")))
    }

    @Test
    fun backToTextDropsTheTicks() {
        val content = Checklist.format(listOf(ChecklistItem("Melk", checked = true), ChecklistItem("Brood")))
        assertEquals("Melk\nBrood", Checklist.toText(content))
    }

    @Test
    fun linesWithoutMarkerAreOpenItems() {
        assertEquals(
            listOf(ChecklistItem("plain"), ChecklistItem("done", checked = true), ChecklistItem(""), ChecklistItem("", checked = true)),
            Checklist.parse("plain\n[X] done\n[ ]\n[x]"),
        )
    }

    @Test
    fun lineBreakInsideAnItemCannotBreakTheFormat() {
        val content = Checklist.format(listOf(ChecklistItem("one\ntwo")))
        assertEquals(listOf(ChecklistItem("one two")), Checklist.parse(content))
    }

    @Test
    fun previewShowsTicksAndSkipsEmptyItems() {
        val content = Checklist.format(listOf(ChecklistItem("Melk", checked = true), ChecklistItem(""), ChecklistItem("Brood")))
        assertEquals("☑ Melk\n☐ Brood", Checklist.preview(content))
    }

    @Test
    fun blankness() {
        assertTrue(Checklist.isBlank(""))
        assertTrue(Checklist.isBlank(Checklist.format(listOf(ChecklistItem(""), ChecklistItem(" ", checked = true)))))
        assertFalse(Checklist.isBlank(Checklist.format(listOf(ChecklistItem("x")))))
    }
}
