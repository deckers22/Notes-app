package com.deckers22.notes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deckers22.notes.backup.BackupCodec
import com.deckers22.notes.backup.BackupException
import com.deckers22.notes.backup.ImportSummary
import com.deckers22.notes.data.Note
import com.deckers22.notes.security.VaultLockedException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** The "Export backup" and "Import backup" rows of the settings screen, with their dialogs. */
@Composable
fun BackupSettings(vm: NotesViewModel, showMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val hasPin by vm.vault.hasPin.collectAsStateWithLifecycle()
    val unlocked by vm.vault.unlocked.collectAsStateWithLifecycle()

    var exportDialogOpen by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // Text of the chosen backup file, while its password is being asked.
    var importFileText by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    // Decoded backup with hidden notes, waiting for the choice to unlock or to skip them.
    var importNeedsVault by remember { mutableStateOf<List<Note>?>(null) }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            vm.discardPendingBackup()
        } else {
            scope.launch {
                val written = vm.writePendingBackup(uri)
                showMessage(
                    when {
                        written == null -> "Could not save the backup file."
                        written.hiddenLeftOut > 0 ->
                            "Backup saved: ${notes(written.noteCount)}. ${notes(written.hiddenLeftOut, "locked hidden")} left out."
                        else -> "Backup saved: ${notes(written.noteCount)}."
                    },
                )
            }
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = vm.readBackupFile(uri)
                if (text == null) {
                    showMessage("This file is not a Notes backup.")
                } else {
                    importError = null
                    importFileText = text
                }
            }
        }
    }

    fun runImport(notes: List<Note>, includeHidden: Boolean) {
        scope.launch {
            val text = try {
                importSummaryText(vm.importBackup(notes, includeHidden))
            } catch (e: VaultLockedException) {
                "Hidden notes are locked. Nothing was imported."
            }
            showMessage(text)
        }
    }

    ListItem(
        headlineContent = { Text("Export backup") },
        supportingContent = { Text("Save your notes to a password-protected file.") },
        modifier = Modifier.clickable { exportDialogOpen = true },
    )
    ListItem(
        headlineContent = { Text("Import backup") },
        supportingContent = {
            Text("Add the notes from a backup file. If a note exists in both, the most recently edited version is kept.")
        },
        modifier = Modifier.clickable { openFile.launch(arrayOf("*/*")) },
    )

    if (exportDialogOpen) {
        ExportDialog(
            busy = busy,
            hiddenNotesHint = when {
                !hasPin -> null
                unlocked -> "Hidden notes are included."
                else -> "Hidden notes are locked and will be left out."
            },
            onUnlock = if (hasPin && !unlocked) ({ vm.withVault {} }) else null,
            onExport = { password ->
                busy = true
                scope.launch {
                    val backup = try {
                        vm.prepareBackup(password)
                    } finally {
                        busy = false
                    }
                    exportDialogOpen = false
                    if (backup.noteCount == 0) {
                        vm.discardPendingBackup()
                        showMessage(
                            if (backup.hiddenLeftOut > 0) "Only locked hidden notes: nothing to back up." else "There are no notes to back up.",
                        )
                    } else {
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        saveFile.launch("notes-backup-$date.json")
                    }
                }
            },
            onDismiss = { if (!busy) exportDialogOpen = false },
        )
    }

    val fileText = importFileText
    if (fileText != null) {
        ImportPasswordDialog(
            busy = busy,
            error = importError,
            onImport = { password ->
                busy = true
                importError = null
                scope.launch {
                    try {
                        val notes = vm.decodeBackup(fileText, password)
                        importFileText = null
                        if (notes.any { it.hidden } && !vm.vault.isUnlocked) {
                            importNeedsVault = notes
                        } else {
                            runImport(notes, includeHidden = true)
                        }
                    } catch (e: BackupException) {
                        when (e.reason) {
                            BackupException.Reason.WRONG_PASSWORD -> importError = "Wrong password."
                            BackupException.Reason.UNSUPPORTED_VERSION -> {
                                importFileText = null
                                showMessage("This backup was made by a newer version of the app.")
                            }
                            BackupException.Reason.NOT_A_BACKUP -> {
                                importFileText = null
                                showMessage("The backup file is damaged.")
                            }
                        }
                    } finally {
                        busy = false
                    }
                }
            },
            onDismiss = { if (!busy) importFileText = null },
        )
    }

    val waiting = importNeedsVault
    if (waiting != null) {
        val hiddenCount = waiting.count { it.hidden }
        AlertDialog(
            onDismissRequest = { importNeedsVault = null },
            title = { Text("Import hidden notes?") },
            text = {
                Text(
                    "This backup contains ${notes(hiddenCount, "hidden")}. " +
                        (if (hasPin) "Unlock your hidden notes" else "Set a PIN for hidden notes") +
                        " to import them as well, or import only the other notes.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    importNeedsVault = null
                    vm.withVault { runImport(waiting, includeHidden = true) }
                }) { Text(if (hasPin) "Unlock and import" else "Set PIN and import") }
            },
            dismissButton = {
                TextButton(onClick = {
                    importNeedsVault = null
                    runImport(waiting, includeHidden = false)
                }) { Text("Skip hidden notes") }
            },
        )
    }
}

private fun notes(count: Int, kind: String = ""): String =
    "$count ${if (kind.isEmpty()) "" else "$kind "}${if (count == 1) "note" else "notes"}"

private fun importSummaryText(summary: ImportSummary): String = buildString {
    append(
        if (summary.written == 0) {
            "Nothing new in this backup."
        } else {
            "Imported ${notes(summary.written)} (${summary.added} new, ${summary.updated} updated)."
        },
    )
    if (summary.hiddenSkipped > 0) append(" ${notes(summary.hiddenSkipped, "hidden")} skipped.")
}

@Composable
private fun PasswordField(value: String, onValueChange: (String) -> Unit, label: String, enabled: Boolean) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ExportDialog(
    busy: Boolean,
    hiddenNotesHint: String?,
    onUnlock: (() -> Unit)?,
    onExport: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    val longEnough = password.length >= BackupCodec.MIN_PASSWORD_LENGTH
    val matches = password == repeat

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose a password for the backup file (at least ${BackupCodec.MIN_PASSWORD_LENGTH} characters). " +
                        "Without this password the backup cannot be opened, so keep it somewhere safe.",
                )
                PasswordField(password, { password = it }, "Backup password", enabled = !busy)
                PasswordField(repeat, { repeat = it }, "Repeat password", enabled = !busy)
                if (longEnough && repeat.isNotEmpty() && !matches) {
                    Text(
                        "The passwords are not the same.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (hiddenNotesHint != null) {
                    Text(hiddenNotesHint, style = MaterialTheme.typography.bodySmall)
                }
                if (onUnlock != null) {
                    TextButton(onClick = onUnlock, enabled = !busy) { Text("Unlock to include them") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onExport(password) }, enabled = longEnough && matches && !busy) {
                Text(if (busy) "Encrypting…" else "Export")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
private fun ImportPasswordDialog(
    busy: Boolean,
    error: String?,
    onImport: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the password of this backup file.")
                PasswordField(password, { password = it }, "Backup password", enabled = !busy)
                if (error != null) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(password) }, enabled = password.isNotEmpty() && !busy) {
                Text(if (busy) "Opening…" else "Import")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
