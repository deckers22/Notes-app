# Chat summary

Running summary of the conversation, kept lean so a new chat can pick up from here.
Branch: `ccr-08fb97d1-3amr84`.

## Request

Android notes app: easily add notes; force-save button plus save when leaving the app or
pressing home; hide notes and lock/unlock them behind a PIN; open to other suggestions.

## Decisions (answered by the user)

| Topic | Choice |
| --- | --- |
| Tech stack | Kotlin + Jetpack Compose, Room database |
| PIN model | One PIN; hidden notes leave the main list and live in a PIN-protected section |
| Protection | Hidden notes encrypted on the device; forgotten PIN = notes unrecoverable |
| Extras | Search + pin to top, fingerprint unlock, trash with undo, dark mode + colours, undo/redo while editing |
| This summary | Markdown file in the repo (this file) |
| Building | Try the SDK in the cloud session + GitHub Actions workflow that builds a debug APK |

## Assumptions made

- Package/application id `com.deckers22.notes`, app name "Notes", min Android 8.0 (API 26).
- UI text is English and hard-coded in the Compose code (no translations yet).
- PIN is 4–8 digits. 5 wrong tries, then a delay of 30 s that doubles (max 1 hour).
- Hidden notes lock when the app goes to the background, and via a lock button.
- "Forgot PIN" in Settings erases the hidden notes and the PIN (only possible way out).
- Autosave happens on back, on pause (home, app switch, screen off); new empty notes are
  not saved. No save-while-typing timer.
- Undo/redo covers the note body, not the title; quick typing is undone per burst.
- Trash keeps notes 30 days. Hidden notes in the trash only show while unlocked.
- Android backup is off and screenshots are blocked while hidden notes are unlocked.
- Hiding/unhiding a note removes its "pinned" state.

## State

- v1 implemented: list, editor, hidden notes, trash, settings (see `README.md`).
- Unit tests for encryption and undo history in `app/src/test`.
- The cloud session cannot reach `dl.google.com` (blocked by the environment's network
  policy), so the Android SDK and AndroidX libraries cannot be downloaded there. The app is
  compiled by GitHub Actions instead (`.github/workflows/build.yml`).
- Not tested on a device or emulator yet.

## Ideas not built

- Export/import or cloud backup, checklists, reminders, labels/folders, home-screen widget,
  Dutch translation, save-while-typing.
