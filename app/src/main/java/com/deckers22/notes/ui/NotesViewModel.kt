package com.deckers22.notes.ui

import android.app.Application
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deckers22.notes.NotesApplication
import com.deckers22.notes.data.Note
import com.deckers22.notes.editor.TextSnapshot
import com.deckers22.notes.editor.UndoManager
import com.deckers22.notes.security.VaultLockedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class SaveResult { SAVED, NOTHING_TO_SAVE, FAILED }

/** The note that is open in the editor, including edits that are not saved yet. */
@Stable
class EditorState(note: Note, persisted: Boolean) {
    val id = note.id
    val createdAt = note.createdAt
    var updatedAt = note.updatedAt

    var title by mutableStateOf(note.title)
    var content by mutableStateOf(TextFieldValue(note.content, TextRange(note.content.length)))
    var hidden by mutableStateOf(note.hidden)
    var pinned by mutableStateOf(note.pinned)
    var color by mutableStateOf(note.color)

    /** True once the note exists in the database. */
    var persisted by mutableStateOf(persisted)
    var dirty by mutableStateOf(false)
    var textChanged = false

    /** Set when the editor screen was left; the next open reloads the note from the database. */
    var closed = false
    var lastSavedAt by mutableStateOf<Long?>(null)

    val history = UndoManager(TextSnapshot(note.content, note.content.length))
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)

    val isBlank: Boolean get() = title.isBlank() && content.text.isBlank()

    fun toNote() = Note(
        id = id,
        title = title.trim(),
        content = content.text,
        hidden = hidden,
        pinned = pinned,
        color = color,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

/** Something that needs the vault to be unlocked first; shown as a PIN dialog by the UI. */
class VaultRequest(val action: () -> Unit)

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as NotesApplication
    private val repository = app.repository
    val vault = app.vault
    val settings = app.settings

    val mainQuery = MutableStateFlow("")
    val vaultQuery = MutableStateFlow("")

    // Readable notes: everything visible, plus hidden notes while the vault is unlocked.
    private val readableNotes: Flow<List<Note>> =
        combine(repository.storedNotes, vault.unlocked) { stored, _ -> stored.mapNotNull(repository::open) }
            .flowOn(Dispatchers.Default)
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val mainNotes: StateFlow<List<Note>> = notesList(hidden = false, query = mainQuery)
    val vaultNotes: StateFlow<List<Note>> = notesList(hidden = true, query = vaultQuery)

    val trashNotes: StateFlow<List<Note>> = readableNotes
        .map { notes -> notes.filter { it.deletedAt != null }.sortedByDescending { it.deletedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun notesList(hidden: Boolean, query: StateFlow<String>): StateFlow<List<Note>> =
        combine(readableNotes, query) { notes, q ->
            val needle = q.trim()
            notes
                .filter { it.deletedAt == null && it.hidden == hidden }
                .filter {
                    needle.isEmpty() ||
                        it.title.contains(needle, ignoreCase = true) ||
                        it.content.contains(needle, ignoreCase = true)
                }
                .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt })
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Vault access ----

    var vaultRequest by mutableStateOf<VaultRequest?>(null)
        private set

    /** Runs [action] now if the vault is unlocked, otherwise after the user unlocks it. */
    fun withVault(action: () -> Unit) {
        if (vault.isUnlocked) action() else vaultRequest = VaultRequest(action)
    }

    fun finishVaultRequest(unlocked: Boolean) {
        val request = vaultRequest
        vaultRequest = null
        if (unlocked && vault.isUnlocked) request?.action?.invoke()
    }

    fun onVaultLocked() {
        if (editor?.hidden == true) editor = null
    }

    fun resetVault() {
        if (editor?.hidden == true) editor = null
        repository.deleteAllHidden()
        vault.reset()
    }

    // ---- List actions ----

    /** Id of the note that was just moved to the trash, so the list can offer "Undo". */
    var recentlyTrashedId by mutableStateOf<String?>(null)
        private set

    fun setPinned(id: String, pinned: Boolean) = repository.setPinned(id, pinned)

    fun setHidden(id: String, hidden: Boolean) = withVault { repository.setHidden(id, hidden) }

    fun moveToTrash(id: String) {
        repository.moveToTrash(id)
        recentlyTrashedId = id
    }

    fun clearRecentlyTrashed(id: String) {
        if (recentlyTrashedId == id) recentlyTrashedId = null
    }

    fun restoreFromTrash(id: String) = repository.restoreFromTrash(id)

    fun deleteForever(id: String) = repository.deleteForever(id)

    fun emptyTrash() = repository.emptyTrash(includeHidden = vault.isUnlocked)

    // ---- Editor ----

    var editor by mutableStateOf<EditorState?>(null)
        private set

    fun openEditor(id: String, startHidden: Boolean) {
        val current = editor
        // Same note still open (e.g. after a screen rotation): keep unsaved edits and undo history.
        if (current != null && current.id == id && !current.closed) return
        editor = null
        viewModelScope.launch {
            val stored = repository.getStored(id)
            editor = if (stored == null) {
                val now = System.currentTimeMillis()
                EditorState(Note(id = id, hidden = startHidden, createdAt = now, updatedAt = now), persisted = false)
            } else {
                // Null when it is a hidden note and the vault is locked: leave the editor closed.
                val readable = repository.open(stored) ?: return@launch
                EditorState(readable, persisted = true)
            }
        }
    }

    fun onTitleChange(title: String) {
        val e = editor ?: return
        if (title == e.title) return
        e.title = title
        e.dirty = true
        e.textChanged = true
    }

    fun onContentChange(value: TextFieldValue) {
        val e = editor ?: return
        val changed = value.text != e.content.text
        e.content = value
        e.history.onChanged(TextSnapshot(value.text, value.selection.start))
        if (changed) {
            e.dirty = true
            e.textChanged = true
            e.syncHistoryFlags()
        }
    }

    fun undo() = applySnapshot { it.history.undo() }

    fun redo() = applySnapshot { it.history.redo() }

    private fun applySnapshot(step: (EditorState) -> TextSnapshot?) {
        val e = editor ?: return
        val snapshot = step(e) ?: return
        e.content = TextFieldValue(snapshot.text, TextRange(snapshot.cursor.coerceIn(0, snapshot.text.length)))
        e.dirty = true
        e.textChanged = true
        e.syncHistoryFlags()
    }

    private fun EditorState.syncHistoryFlags() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    fun setEditorPinned(pinned: Boolean) {
        val e = editor ?: return
        e.pinned = pinned
        e.dirty = true
    }

    fun setEditorColor(color: Int) {
        val e = editor ?: return
        e.color = color
        e.dirty = true
    }

    /**
     * Writes the open note to the database if it has unsaved changes. Used by the save button,
     * when leaving the editor, and when the app goes to the background.
     */
    fun saveEditor(): SaveResult {
        val e = editor ?: return SaveResult.NOTHING_TO_SAVE
        if (!e.dirty) return if (e.persisted) SaveResult.SAVED else SaveResult.NOTHING_TO_SAVE
        if (!e.persisted && e.isBlank) return SaveResult.NOTHING_TO_SAVE // don't create empty notes
        val now = System.currentTimeMillis()
        if (e.textChanged) e.updatedAt = now
        return try {
            repository.save(e.toNote())
            e.persisted = true
            e.dirty = false
            e.textChanged = false
            e.lastSavedAt = now
            SaveResult.SAVED
        } catch (ex: VaultLockedException) {
            SaveResult.FAILED
        }
    }

    fun closeEditor() {
        saveEditor()
        editor?.closed = true
    }

    /** Moves the open note into (or out of) the hidden notes, then closes the editor. */
    fun setEditorHidden(hidden: Boolean, onDone: () -> Unit) = withVault {
        val e = editor ?: return@withVault
        e.hidden = hidden
        e.pinned = false
        e.dirty = true
        closeEditor()
        onDone()
    }

    fun trashEditorNote() {
        val e = editor ?: return
        saveEditor()
        e.closed = true
        if (e.persisted) moveToTrash(e.id)
    }
}
