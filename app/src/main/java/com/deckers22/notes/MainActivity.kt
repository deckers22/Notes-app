package com.deckers22.notes

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.deckers22.notes.ui.NotesApp
import kotlinx.coroutines.launch

// FragmentActivity (rather than ComponentActivity) is required by the biometric prompt.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // While hidden notes are unlocked, keep them out of screenshots and the recents preview.
        val vault = (application as NotesApplication).vault
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vault.unlocked.collect { unlocked ->
                    if (unlocked) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }
            }
        }

        setContent { NotesApp() }
    }
}
