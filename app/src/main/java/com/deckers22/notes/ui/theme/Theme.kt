package com.deckers22.notes.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.deckers22.notes.security.findFragmentActivity
import com.deckers22.notes.settings.ThemeMode

val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun NotesTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }

    // Keep status/navigation bar icons readable when the app theme differs from the system's.
    val view = LocalView.current
    val window = context.findFragmentActivity()?.window
    if (window != null) {
        SideEffect {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}

/** Colour labels for notes. Index 0 means "no colour". */
object NoteColors {
    val names = listOf("None", "Red", "Orange", "Yellow", "Green", "Blue", "Purple")

    private val light = listOf(
        Color(0xFFFFDAD6), Color(0xFFFFDDB8), Color(0xFFFFF2B3),
        Color(0xFFD3EFCB), Color(0xFFD0E4FF), Color(0xFFEBDCFF),
    )
    private val dark = listOf(
        Color(0xFF5C2B29), Color(0xFF5E3F12), Color(0xFF554A14),
        Color(0xFF2B4A2C), Color(0xFF1F3F5C), Color(0xFF43305C),
    )

    val count: Int get() = names.size

    /** Background for a note with this colour label, or [default] when it has none. */
    @Composable
    fun background(index: Int, default: Color): Color {
        val palette = if (LocalDarkTheme.current) dark else light
        return palette.getOrNull(index - 1) ?: default
    }
}
