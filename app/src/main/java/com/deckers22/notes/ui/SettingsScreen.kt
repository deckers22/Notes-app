package com.deckers22.notes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deckers22.notes.security.canUseBiometrics
import com.deckers22.notes.security.findFragmentActivity
import com.deckers22.notes.security.showBiometricPrompt
import com.deckers22.notes.settings.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(vm: NotesViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val themeMode by vm.settings.themeMode.collectAsStateWithLifecycle()
    val hasPin by vm.vault.hasPin.collectAsStateWithLifecycle()
    val biometricEnabled by vm.vault.biometricEnabled.collectAsStateWithLifecycle()
    var changePinOpen by remember { mutableStateOf(false) }
    var setupPinOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun enableBiometric() {
        val activity = context.findFragmentActivity() ?: return
        if (!canUseBiometrics(context)) {
            message("Add a fingerprint in the phone's settings first.")
            return
        }
        // Needs the unlocked vault: the PIN dialog is shown first when necessary.
        vm.withVault {
            val cipher = runCatching { vm.vault.newBiometricEnrollCipher() }.getOrNull()
            if (cipher == null) {
                message("Fingerprint unlock is not available on this device.")
                return@withVault
            }
            showBiometricPrompt(
                activity = activity,
                title = "Confirm fingerprint",
                cipher = cipher,
                onSuccess = {
                    val result = runCatching { vm.vault.finishEnableBiometric(it) }
                    message(if (result.isSuccess) "Fingerprint unlock is on." else "Could not turn on fingerprint unlock.")
                },
                onError = { error -> if (error != null) message(error) },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle("Appearance")
            for (mode in ThemeMode.entries) {
                ListItem(
                    headlineContent = { Text(mode.label) },
                    leadingContent = { RadioButton(selected = mode == themeMode, onClick = null) },
                    modifier = Modifier.clickable { vm.settings.setThemeMode(mode) },
                )
            }

            HorizontalDivider()
            SectionTitle("Hidden notes")
            if (!hasPin) {
                ListItem(
                    headlineContent = { Text("Set a PIN") },
                    supportingContent = { Text("Needed before you can hide notes. Hidden notes are encrypted with it.") },
                    modifier = Modifier.clickable { setupPinOpen = true },
                )
            } else {
                ListItem(
                    headlineContent = { Text("Change PIN") },
                    modifier = Modifier.clickable { changePinOpen = true },
                )
                ListItem(
                    headlineContent = { Text("Unlock with fingerprint") },
                    supportingContent = { Text("Use your fingerprint instead of typing the PIN.") },
                    trailingContent = { Switch(checked = biometricEnabled, onCheckedChange = null) },
                    modifier = Modifier.clickable {
                        if (biometricEnabled) vm.vault.disableBiometric() else enableBiometric()
                    },
                )
                ListItem(
                    headlineContent = { Text("Forgot PIN: erase hidden notes", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("Deletes all hidden notes and removes the PIN.") },
                    modifier = Modifier.clickable { confirmReset = true },
                )
            }

            HorizontalDivider()
            SectionTitle("Saving")
            ListItem(
                headlineContent = { Text("Notes are saved automatically") },
                supportingContent = {
                    Text("When you go back, press home, switch apps or turn off the screen. The save button in the editor saves right away.")
                },
            )
        }
    }

    if (setupPinOpen) {
        SetupPinDialog(
            vault = vm.vault,
            onDone = { setupPinOpen = false; message("PIN set.") },
            onDismiss = { setupPinOpen = false },
        )
    }
    if (changePinOpen) {
        ChangePinDialog(
            vault = vm.vault,
            onDone = { changePinOpen = false; message("PIN changed.") },
            onDismiss = { changePinOpen = false },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Erase hidden notes?") },
            text = { Text("All hidden notes, including hidden notes in the trash, are deleted for good and the PIN is removed. Your other notes are not affected.") },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; vm.resetVault(); message("Hidden notes erased.") }) {
                    Text("Erase", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
