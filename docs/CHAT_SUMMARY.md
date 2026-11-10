# Chat summary

Running summary of the conversation, kept lean so a new chat can pick up from here.
Branch: `ccr-fa459b3f-faju4j` (continues the work from `ccr-08fb97d1-3amr84`).

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

Session 2 choices:

| Topic | Choice |
| --- | --- |
| Next features | Export/import backup and checklists |
| Backup file | Always encrypted with a backup password; includes hidden notes when unlocked |
| Import | Merge: new notes are added, for notes in both the most recently edited wins |
| Checklist | A note can be switched between text and checklist (editor menu) |
| Ticked items | Stay in place, struck through |
| Phone test | Not tried on a phone yet |

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

Session 2 (backup and checklists):

- Backup password is at least 8 characters; there is no way to recover a forgotten one.
- The backup also contains notes that are in the trash. App settings (theme, PIN) are not
  in it. File name `notes-backup-YYYY-MM-DD.json` (JSON envelope, encrypted content).
- Hidden notes that are locked at export time are left out; the export dialog offers to
  unlock first. On import, hidden notes need the PIN (or a new PIN); they can be skipped.
- Import decides per note on "last edited" time; pin/colour/trash state comes along with
  the winning version.
- Checklist items are stored in the note text as lines starting with `[ ] ` or `[x] `.
- Text to checklist: every non-blank line becomes an item. Checklist to text: the ticks
  are dropped. Switching starts a fresh undo history.
- Items are added with Enter or "Add item", removed with the X; no drag-to-reorder.

## State

- v1 implemented: list, editor, hidden notes, trash, settings (see `README.md`).
- Unit tests for encryption and undo history in `app/src/test`.
- Session 2: Google hosts are now reachable from the cloud session. With the Android SDK
  (platform 35, build-tools 35.0.0) installed, `./gradlew testDebugUnitTest assembleDebug`
  succeeds there without code changes: debug APK built, 15 unit tests pass.
- Maven Central sometimes answers 429 (rate limit) in the cloud session; re-running the
  build with `--max-workers=1` until the libraries are cached gets past it.
- Session 2 added checklists and backup export/import (app version 1.1, database
  version 2 with a migration that adds the `checklist` column).
- 35 unit tests pass (encryption, undo history, checklist format, backup file, merge rules).
- Still not tested on a device or emulator (the cloud session has no KVM, so no emulator):
  the new screens (checklist editor, backup dialogs, file picker) compile but have not been
  seen running. The APK also comes out of GitHub Actions (`.github/workflows/build.yml`).

## Next step

- The user tries the APK on a phone and reports problems; then picks the next idea.

## Ideas not built

- Cloud backup, reminders, labels/folders, home-screen widget, Dutch translation,
  save-while-typing, drag-to-reorder checklist items.
