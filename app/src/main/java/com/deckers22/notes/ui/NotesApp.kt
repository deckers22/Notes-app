package com.deckers22.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.deckers22.notes.ui.theme.NotesTheme
import java.util.UUID

private object Routes {
    const val NOTES = "notes"
    const val VAULT = "vault"
    const val TRASH = "trash"
    const val SETTINGS = "settings"
    const val EDITOR = "editor/{id}?hidden={hidden}"

    fun editor(id: String, hidden: Boolean) = "editor/$id?hidden=$hidden"
}

/** Goes back, but only once per screen (a double tap must not pop two screens). */
private fun NavController.popFrom(entry: NavBackStackEntry) {
    if (currentBackStackEntry?.id == entry.id) popBackStack()
}

@Composable
fun NotesApp(vm: NotesViewModel = viewModel()) {
    val themeMode by vm.settings.themeMode.collectAsStateWithLifecycle()
    NotesTheme(themeMode) {
        val nav = rememberNavController()
        val unlocked by vm.vault.unlocked.collectAsStateWithLifecycle()
        val hasPin by vm.vault.hasPin.collectAsStateWithLifecycle()

        // When the hidden notes get locked, leave every screen that shows them.
        LaunchedEffect(unlocked) {
            if (!unlocked) {
                vm.onVaultLocked()
                val vaultIsOpen = runCatching { nav.getBackStackEntry(Routes.VAULT) }.isSuccess
                if (vaultIsOpen) nav.popBackStack(Routes.VAULT, inclusive = true)
            }
        }

        NavHost(navController = nav, startDestination = Routes.NOTES) {
            composable(Routes.NOTES) {
                NotesListScreen(
                    vm = vm,
                    vaultMode = false,
                    onOpenNote = { nav.navigate(Routes.editor(it, hidden = false)) },
                    onNewNote = { nav.navigate(Routes.editor(UUID.randomUUID().toString(), hidden = false)) },
                    onOpenVault = { vm.withVault { nav.navigate(Routes.VAULT) { launchSingleTop = true } } },
                    onOpenTrash = { nav.navigate(Routes.TRASH) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) },
                    onBack = {},
                )
            }
            composable(Routes.VAULT) { entry ->
                NotesListScreen(
                    vm = vm,
                    vaultMode = true,
                    onOpenNote = { nav.navigate(Routes.editor(it, hidden = true)) },
                    onNewNote = { nav.navigate(Routes.editor(UUID.randomUUID().toString(), hidden = true)) },
                    onOpenVault = {},
                    onOpenTrash = {},
                    onOpenSettings = {},
                    onBack = { nav.popFrom(entry) },
                )
            }
            composable(
                route = Routes.EDITOR,
                arguments = listOf(
                    navArgument("id") { type = NavType.StringType },
                    navArgument("hidden") { type = NavType.BoolType; defaultValue = false },
                ),
            ) { entry ->
                EditorScreen(
                    vm = vm,
                    noteId = entry.arguments?.getString("id").orEmpty(),
                    startHidden = entry.arguments?.getBoolean("hidden") ?: false,
                    onClose = { nav.popFrom(entry) },
                )
            }
            composable(Routes.TRASH) { entry ->
                TrashScreen(vm = vm, onBack = { nav.popFrom(entry) })
            }
            composable(Routes.SETTINGS) { entry ->
                SettingsScreen(vm = vm, onBack = { nav.popFrom(entry) })
            }
        }

        if (vm.vaultRequest != null) {
            if (hasPin) {
                UnlockDialog(
                    vault = vm.vault,
                    onUnlocked = { vm.finishVaultRequest(unlocked = true) },
                    onDismiss = { vm.finishVaultRequest(unlocked = false) },
                )
            } else {
                SetupPinDialog(
                    vault = vm.vault,
                    onDone = { vm.finishVaultRequest(unlocked = true) },
                    onDismiss = { vm.finishVaultRequest(unlocked = false) },
                )
            }
        }
    }
}
