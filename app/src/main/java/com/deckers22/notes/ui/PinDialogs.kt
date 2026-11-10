package com.deckers22.notes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deckers22.notes.security.UnlockResult
import com.deckers22.notes.security.VaultManager
import com.deckers22.notes.security.findFragmentActivity
import com.deckers22.notes.security.showBiometricPrompt
import kotlinx.coroutines.launch

const val MIN_PIN_LENGTH = 4
const val MAX_PIN_LENGTH = 8

@Composable
private fun PinField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {},
) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= MAX_PIN_LENGTH && it.all(Char::isDigit)) onValueChange(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ErrorText(message: String?) {
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private fun unlockErrorText(result: UnlockResult): String? = when (result) {
    UnlockResult.Success -> null
    is UnlockResult.WrongPin ->
        "Wrong PIN. ${result.attemptsLeft} ${if (result.attemptsLeft == 1) "try" else "tries"} left before a delay."
    is UnlockResult.LockedOut -> "Too many wrong PINs. Try again in ${formatWait(result.secondsLeft)}."
}

private fun formatWait(seconds: Long): String =
    if (seconds < 60) "$seconds s" else "${(seconds + 59) / 60} min"

/** Asks for the PIN (or fingerprint, when enabled) to unlock the hidden notes. */
@Composable
fun UnlockDialog(vault: VaultManager, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current.findFragmentActivity()
    val biometricEnabled by vault.biometricEnabled.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    fun submit() {
        if (busy || pin.length < MIN_PIN_LENGTH) return
        busy = true
        scope.launch {
            val result = vault.unlock(pin)
            busy = false
            pin = ""
            if (result == UnlockResult.Success) onUnlocked() else error = unlockErrorText(result)
        }
    }

    fun askBiometric() {
        if (activity == null) return
        val cipher = vault.biometricUnlockCipher()
        if (cipher == null) {
            error = "Fingerprint unlock was switched off because the fingerprints on this device changed. Use your PIN."
            return
        }
        showBiometricPrompt(
            activity = activity,
            title = "Unlock hidden notes",
            cipher = cipher,
            onSuccess = { if (vault.unlockWithBiometric(it)) onUnlocked() else error = "Fingerprint unlock failed. Use your PIN." },
            onError = { message -> if (message != null) error = message },
        )
    }

    LaunchedEffect(Unit) {
        if (biometricEnabled) askBiometric()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden notes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PinField(
                    value = pin,
                    onValueChange = { pin = it; error = null },
                    label = "PIN",
                    imeAction = ImeAction.Done,
                    onDone = ::submit,
                    modifier = Modifier.focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) {
                    if (!biometricEnabled) runCatching { focusRequester.requestFocus() }
                }
                ErrorText(error)
                if (biometricEnabled) {
                    OutlinedButton(onClick = ::askBiometric) {
                        Icon(Icons.Filled.Fingerprint, contentDescription = null)
                        Text("  Use fingerprint")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = !busy && pin.length >= MIN_PIN_LENGTH) { Text("Unlock") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** First-time setup of the PIN that protects the hidden notes. */
@Composable
fun SetupPinDialog(vault: VaultManager, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        error = newPinError(pin, confirm)
        if (error != null) return
        busy = true
        scope.launch {
            val result = runCatching { vault.setupPin(pin) }
            busy = false
            if (result.isSuccess) onDone() else error = "Could not set up the PIN on this device."
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set a PIN for hidden notes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Hidden notes are encrypted with this PIN. If you forget it, they cannot be recovered.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PinField(pin, { pin = it; error = null }, "New PIN ($MIN_PIN_LENGTH–$MAX_PIN_LENGTH digits)")
                PinField(confirm, { confirm = it; error = null }, "Repeat PIN", imeAction = ImeAction.Done, onDone = ::submit)
                ErrorText(error)
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = !busy) { Text("Set PIN") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ChangePinDialog(vault: VaultManager, onDone: () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        error = if (current.length < MIN_PIN_LENGTH) "Enter your current PIN." else newPinError(pin, confirm)
        if (error != null) return
        busy = true
        scope.launch {
            val result = vault.unlock(current)
            if (result == UnlockResult.Success) {
                val changed = runCatching { vault.changePin(pin) }
                busy = false
                if (changed.isSuccess) onDone() else error = "Could not change the PIN."
            } else {
                busy = false
                current = ""
                error = unlockErrorText(result)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PinField(current, { current = it; error = null }, "Current PIN")
                PinField(pin, { pin = it; error = null }, "New PIN ($MIN_PIN_LENGTH–$MAX_PIN_LENGTH digits)")
                PinField(confirm, { confirm = it; error = null }, "Repeat new PIN", imeAction = ImeAction.Done, onDone = ::submit)
                ErrorText(error)
            }
        },
        confirmButton = { TextButton(onClick = ::submit, enabled = !busy) { Text("Change") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun newPinError(pin: String, confirm: String): String? = when {
    pin.length < MIN_PIN_LENGTH -> "Use at least $MIN_PIN_LENGTH digits."
    pin != confirm -> "The PINs are not the same."
    else -> null
}
