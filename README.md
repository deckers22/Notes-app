# Notes

A simple, offline Android notes app (Kotlin + Jetpack Compose).

## Features

- **Quick notes** – tap **+** and start typing; empty notes are not kept.
- **Saving** – a save button in the editor, plus automatic saving when you go back, press
  home, switch apps or turn off the screen.
- **Undo / redo** while editing the note text.
- **Hidden notes** – hide notes behind a PIN. Hidden notes disappear from the main list and
  are stored encrypted (AES-256-GCM). They lock again when the app goes to the background.
- **Fingerprint unlock** as an alternative to typing the PIN (optional, in Settings).
- **Search**, **pin to top**, **colour labels**, **dark mode** (system / light / dark).
- **Trash** – deleted notes can be restored for 30 days; "Undo" right after deleting.

Long-press a note in the list for pin / hide / delete. The lock icon opens the hidden notes.

## Security notes

- The PIN is never stored. It derives a key (PBKDF2) that unwraps the random key used to
  encrypt hidden notes; that wrapped key is additionally protected by the Android Keystore.
- **A forgotten PIN cannot be recovered.** Settings offers "Forgot PIN", which erases the
  hidden notes and removes the PIN.
- Android backup is disabled, because the encryption key cannot leave the device.
- Screenshots are blocked while the hidden notes are unlocked.

## Build

Requires JDK 17 and the Android SDK (API 35).

```
./gradlew assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # unit tests
```

Every push also builds a debug APK in GitHub Actions (workflow **Build**, artifact
`notes-debug-apk`).

## Project layout

```
app/src/main/java/com/deckers22/notes/
  data/       Room database, Note entity, repository
  security/   PIN vault, encryption, fingerprint prompt
  editor/     undo/redo history
  settings/   theme preference
  ui/         Compose screens, view model, theme
```
