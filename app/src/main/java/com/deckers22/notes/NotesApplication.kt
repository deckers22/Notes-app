package com.deckers22.notes

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.deckers22.notes.data.NotesDatabase
import com.deckers22.notes.data.NotesRepository
import com.deckers22.notes.security.VaultManager
import com.deckers22.notes.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class NotesApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var vault: VaultManager
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var repository: NotesRepository
        private set

    override fun onCreate() {
        super.onCreate()
        vault = VaultManager(this)
        settings = SettingsStore(this)
        repository = NotesRepository(NotesDatabase.create(this).noteDao(), vault, appScope)
        repository.purgeOldTrash()

        // Lock the hidden notes as soon as the whole app goes to the background. This fires
        // shortly after the last screen stops, so the editor has already saved by then, and
        // it does not fire on screen rotation.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = vault.lock()
        })
    }
}
