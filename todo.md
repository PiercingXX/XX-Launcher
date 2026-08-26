# Review follow-up

Work list from the full-app review of `main` @ `7486b09`. All items implemented.

## Bugs

- [x] **1. Do not prune layout on a failed lookup**
  - `FolderManager.getMembers()` no longer deletes members when `LauncherApps.getActivityList` returns empty or throws.
  - Resolve for display only; keep the stored key (including shortcut ids) when the package is not enumerable.
  - `MainActivity.renderHomeSlots()` no longer clears a slot on a failed install check. Failed launch of a filled slot does not clear it either.
  - Uninstall pruning: `AppRepository.onPackageRemoved` drops slots, pins, hidden apps, and folder members (including shortcut keys). Empty folders are deleted.

- [x] **2. Restore is a replace, not a merge**
  - Wipe folders, all `rename_*` keys, widget tap keys, and mute entries before applying a backup.
  - Recreate folders from the file.
  - Include muted packages in the JSON (`mutedApps`).
  - After Gson `fromJson`, null-coalesce collections so a v1 file missing lists still loads. `BACKUP_VERSION` stays 1.
  - On successful import, `SettingsActivity` calls `setAppearanceMode`, `applyWallpaper`, and `publish()`.
  - JVM tests: sparse v1 JSON, mute round-trip, restore payload is the file's keys only.

- [x] **3. Home-slot shortcut keys hide the host app**
  - `AppDrawerActivity.homeSlotKeys()` uses `RenamePropagator.renameKey` so a shortcut on home excludes that shortcut, not the host app.

- [x] **4. Expanded drawer folders duplicate members**
  - Render generation + cancel the previous member-load job. Stale coroutines do not insert.

- [x] **5. Default-launcher dialog stacks**
  - Track the showing dialog; persist `hideDefaultLauncherPrompt` on cancel / tap-outside; do not restack after “Set default” in the same session.

- [x] **6. Failed swipe target must not fall through to camera/dialer**
  - Camera (left) / dialer (right) only when the configured target is null or blank.

## Suggestions

- [x] **7. Stable per-profile user tokens**
  - `personal` for this user, `u{serial}` for others; `"managed"` still resolves and is migrated on first load.
  - `AppInfo.isWorkProfile` is any non-personal token.

- [x] **8. Sign the theme broadcast**
  - Signature permission `com.piercingxx.xxlauncher.permission.THEME_SYNC`; `sendBroadcast(intent, permission)`.
  - Family apps must `uses-permission` the same name (same signing key). Constant locked in `ThemeBroadcasterTest`.

- [x] **9. Actually test Room migrations**
  - `MIGRATION_1_2` / `MIGRATION_2_3` exported; instrumented `FolderMigrationTest` with `MigrationTestHelper`.
  - `FolderOrderTest` KDoc no longer claims it covers schema migration.

- [x] **10. Lock / recents feedback**
  - Hide “Double Tap to Lock” below API 28.
  - Distinct toast for unsupported SDK vs missing accessibility service.
  - Home-to-recents toasts and deep-links to accessibility settings when `openRecents()` returns false.

## Docs and ship

- [x] Update `MANUAL.md` (prune-on-read, replace restore, signed theme IPC, serial profile tokens, tests).
- [x] `./gradlew testDebugUnitTest` — 47 passed.
- [x] Commit and push.
