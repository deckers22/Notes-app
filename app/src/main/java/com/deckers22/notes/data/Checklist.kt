package com.deckers22.notes.data

data class ChecklistItem(val text: String, val checked: Boolean = false)

/**
 * A checklist is stored in the note content as one item per line, each starting with
 * "[ ] " (open) or "[x] " (ticked). Plain JVM code, so it is unit-testable.
 */
object Checklist {
    private const val OPEN = "[ ] "
    private const val DONE = "[x] "

    fun parse(content: String): List<ChecklistItem> =
        if (content.isEmpty()) emptyList() else content.split('\n').map(::parseLine)

    private fun parseLine(line: String): ChecklistItem = when {
        line.startsWith(DONE, ignoreCase = true) -> ChecklistItem(line.substring(DONE.length), checked = true)
        line.startsWith(OPEN) -> ChecklistItem(line.substring(OPEN.length))
        line.equals(DONE.trimEnd(), ignoreCase = true) -> ChecklistItem("", checked = true)
        line == OPEN.trimEnd() -> ChecklistItem("")
        else -> ChecklistItem(line) // a line without marker, e.g. from an edited backup file
    }

    fun format(items: List<ChecklistItem>): String =
        items.joinToString("\n") { (if (it.checked) DONE else OPEN) + it.text.replace('\n', ' ') }

    /** Turns note text into checklist content: every non-blank line becomes an open item. */
    fun fromText(text: String): String {
        val items = text.lines().filter(String::isNotBlank).map { ChecklistItem(it.trim()) }
        return format(items.ifEmpty { listOf(ChecklistItem("")) })
    }

    /** Turns checklist content back into note text, one item per line, without the ticks. */
    fun toText(content: String): String = parse(content).joinToString("\n") { it.text }

    /** Short readable form for the notes list. */
    fun preview(content: String): String =
        parse(content).filter { it.text.isNotBlank() }
            .joinToString("\n") { (if (it.checked) "☑ " else "☐ ") + it.text.trim() }

    fun isBlank(content: String): Boolean = parse(content).all { it.text.isBlank() }
}
