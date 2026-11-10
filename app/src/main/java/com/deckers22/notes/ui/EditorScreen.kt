package com.deckers22.notes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.deckers22.notes.data.Checklist
import com.deckers22.notes.ui.theme.NoteColors
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(vm: NotesViewModel, noteId: String, startHidden: Boolean, onClose: () -> Unit) {
    LaunchedEffect(noteId) { vm.openEditor(noteId, startHidden) }

    val close = {
        vm.closeEditor() // saves
        onClose()
    }
    BackHandler(onBack = close)

    // Save whenever the app loses the foreground: home button, app switcher, screen off, ...
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.saveEditor() }

    val editor = vm.editor
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var colorPickerOpen by remember { mutableStateOf(false) }
    val background = NoteColors.background(editor?.color ?: 0, MaterialTheme.colorScheme.surface)

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = background,
        topBar = {
            TopAppBar(
                title = {},
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background),
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Save and go back")
                    }
                },
                actions = {
                    if (editor != null) {
                        IconButton(onClick = vm::undo, enabled = editor.canUndo) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                        }
                        IconButton(onClick = vm::redo, enabled = editor.canRedo) {
                            Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                        }
                        IconButton(onClick = {
                            val message = when (vm.saveEditor()) {
                                SaveResult.SAVED -> "Note saved"
                                SaveResult.NOTHING_TO_SAVE -> "Nothing to save yet"
                                SaveResult.FAILED -> "Could not save: hidden notes are locked"
                            }
                            scope.launch {
                                snackbar.currentSnackbarData?.dismiss()
                                snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
                            }
                        }) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(if (editor.pinned) "Unpin" else "Pin to top") },
                                onClick = { menuOpen = false; vm.setEditorPinned(!editor.pinned) },
                            )
                            DropdownMenuItem(
                                text = { Text(if (editor.checklist) "Turn into text" else "Turn into checklist") },
                                onClick = { menuOpen = false; vm.setEditorChecklist(!editor.checklist) },
                            )
                            DropdownMenuItem(
                                text = { Text("Colour") },
                                onClick = { menuOpen = false; colorPickerOpen = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (editor.hidden) "Unhide" else "Hide") },
                                onClick = { menuOpen = false; vm.setEditorHidden(!editor.hidden, onDone = onClose) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete") },
                                onClick = { menuOpen = false; vm.trashEditorNote(); onClose() },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (editor != null) {
            val contentFocus = remember { FocusRequester() }
            LaunchedEffect(editor.id) {
                // New note: start typing right away. (A checklist focuses its own first item.)
                if (!editor.persisted && !editor.checklist) contentFocus.requestFocus()
            }
            Column(Modifier.fillMaxSize().padding(padding)) {
                TextField(
                    value = editor.title,
                    onValueChange = vm::onTitleChange,
                    placeholder = { Text("Title", style = MaterialTheme.typography.titleLarge) },
                    textStyle = MaterialTheme.typography.titleLarge,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                    colors = transparentTextFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (editor.checklist) {
                    ChecklistEditor(
                        items = Checklist.parse(editor.content.text),
                        onChange = vm::onChecklistChange,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                } else {
                    TextField(
                        value = editor.content,
                        onValueChange = vm::onContentChange,
                        placeholder = { Text("Note") },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        colors = transparentTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().weight(1f).focusRequester(contentFocus),
                    )
                }
                Text(
                    text = saveStatus(editor),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }

    if (colorPickerOpen && editor != null) {
        ColorPickerDialog(
            selected = editor.color,
            onSelect = { vm.setEditorColor(it); colorPickerOpen = false },
            onDismiss = { colorPickerOpen = false },
        )
    }
}

private fun saveStatus(editor: EditorState): String {
    val savedAt = editor.lastSavedAt
    return when {
        editor.dirty && (editor.persisted || !editor.isBlank) -> "Unsaved changes"
        savedAt != null -> "Saved at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(savedAt))}"
        editor.persisted -> "Saved"
        else -> ""
    }
}

@Composable
private fun ColorPickerDialog(selected: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note colour") },
        text = {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                for (index in 0 until NoteColors.count) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(NoteColors.background(index, MaterialTheme.colorScheme.surface))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onSelect(index) },
                    ) {
                        if (index == selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "${NoteColors.names[index]} (selected)",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
