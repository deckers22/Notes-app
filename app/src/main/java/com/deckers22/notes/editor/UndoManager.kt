package com.deckers22.notes.editor

data class TextSnapshot(val text: String, val cursor: Int)

/**
 * Undo/redo history for a text field. Single-character edits of the same kind that follow
 * each other quickly are grouped, so one undo removes a burst of typing instead of one letter.
 */
class UndoManager(
    initial: TextSnapshot,
    private val maxEntries: Int = 200,
    private val groupTimeoutMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class EditKind { INSERT, DELETE, BULK }

    private val undoStack = ArrayDeque<TextSnapshot>()
    private val redoStack = ArrayDeque<TextSnapshot>()
    private var current = initial
    private var lastEditAt = 0L
    private var lastEditKind: EditKind? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Call on every change of the field, including cursor-only moves. */
    fun onChanged(new: TextSnapshot) {
        if (new.text == current.text) {
            current = new
            return
        }
        val now = clock()
        val kind = when (new.text.length - current.text.length) {
            1 -> EditKind.INSERT
            -1 -> EditKind.DELETE
            else -> EditKind.BULK
        }
        val continuesGroup = kind != EditKind.BULK &&
            kind == lastEditKind &&
            now - lastEditAt <= groupTimeoutMs &&
            undoStack.isNotEmpty()
        if (!continuesGroup) {
            undoStack.addLast(current)
            if (undoStack.size > maxEntries) undoStack.removeFirst()
        }
        redoStack.clear()
        current = new
        lastEditAt = now
        lastEditKind = kind
    }

    fun undo(): TextSnapshot? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        current = previous
        lastEditKind = null
        return previous
    }

    fun redo(): TextSnapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        current = next
        lastEditKind = null
        return next
    }
}
