# XX-Launcher — Developer Manual

A complete technical reference for the codebase: what every file does, how the
data flows, and a full audit of dead code, defects, and hygiene issues.

**Audited at:** commit `d5bbf4d` ("PiercingXX Launcher v0.7") — 2026-08-02.
§10 is that audit and stays as written; every other section is kept current
against the tree, which has since taken the `com.piercingxx.xxlauncher`
rename, the toolchain bump, the theme-sync sender, and home-slot renaming.
**Status:** audit complete; all findings in §10.1–10.2 fixed in the working tree
**App version:** `0.7` (`versionCode 70`)
**Toolchain:** AGP 8.9.1, Kotlin 2.1.20, Gradle 8.11.1, JDK 17+ to build (21 in practice), `jvmTarget` 17, `compileSdk`/`targetSdk` 35, `minSdk` 24

---

## Table of contents

1. [What the app is](#1-what-the-app-is)
2. [Build and run](#2-build-and-run)
3. [Project layout](#3-project-layout)
4. [Architecture overview](#4-architecture-overview)
5. [Persistence model](#5-persistence-model)
6. [Module-by-module reference](#6-module-by-module-reference)
7. [Resource reference](#7-resource-reference)
8. [Manifest, permissions, and services](#8-manifest-permissions-and-services)
9. [Test suite](#9-test-suite)
10. [Audit — findings](#10-audit--findings)
11. [Known limitations (by design)](#11-known-limitations-by-design)
12. [Maintenance playbook](#12-maintenance-playbook)

---

## 1. What the app is

XX-Launcher is a text-only Android home screen. There are no icons and
no wallpaper: the home screen is a vertically-centered column of app labels
(up to 8 "slots"), with an optional widget block (clock / date / weather /
battery) centered in the upper half of the screen.

Interaction is gesture-driven:

| Gesture | Action |
|---|---|
| Swipe up | Open the app drawer (a bottom sheet with search) |
| Swipe down | Expand notifications, web search, or nothing (configurable) |
| Swipe left | Launch the configured app (default: camera) |
| Swipe right | Launch the configured app (default: dialer) |
| Double-tap | Lock the screen (needs the accessibility service) |
| Long-press home background | Open launcher settings |
| Tap a slot | Launch the app, drop the folder open inline, or (empty slot) open the app picker |
| Long-press a slot | Open the app picker for that slot (plus Change Label, move, rearrange, folder options, new folder, and clear rows) |
| Long-press a drawer row | Item action menu (add to home, hide, pin, rename, folder, uninstall…) |

Settings → Gestures → "Gestures" shows this table in-app.

The app is self-contained. The **only** outbound network request in the entire
codebase is the Open-Meteo weather lookup in `WeatherHelper.kt`.

It is also the family's theme sender: every theme change broadcasts
`xx.launcher.THEME_CHANGED` to the nine PiercingXX apps, which repaint to
match. See `ThemeBroadcaster.kt` in §6. That is on-device IPC, not network.

---

## 2. Build and run

```sh
# Debug APK -> app/build/outputs/apk/debug/
./gradlew assembleDebug

# JVM unit tests (no device needed)
./gradlew testDebugUnitTest

# Static analysis
./gradlew lint          # report: app/build/reports/lint-results-debug.html

# Install to a connected device
./gradlew installDebug
```

There is a system JDK now (Temurin 17.0.20.1 on `PATH`), but the family
builds under Temurin 21 — AGP 8.9.1 is happy on either, and `jvmTarget` stays
17 regardless:

```sh
export ANDROID_HOME=$HOME/Android/Sdk
JAVA_HOME=~/tools/jdk-21.0.12.1+1 ./gradlew installDebug
```

`local.properties` (git-ignored) must point at the Android SDK:

```properties
sdk.dir=/home/piercingxx/Android/Sdk
```

**Instrumented tests destroy launcher state.** `./gradlew connectedDebugAndroidTest`
uninstalls the app afterwards, wiping folders and settings. To keep the install:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

Re-installing the launcher also drops its HOME role. Restore it with:

```sh
adb shell cmd role add-role-holder --user 0 android.app.role.HOME com.piercingxx.xxlauncher
```

### Signing

`app/debug.keystore` used to be committed so every machine produced an
identically-signed debug build. It is gone: `.gitignore` now says
`*.keystore` and no signing material lives in the repo, so debug builds carry
whichever `~/.android/debug.keystore` AGP generated on the machine. A
different machine means a signature conflict on `installDebug` — uninstall
first. There is still no release signing config; `assembleRelease` produces an
unsigned APK with `minifyEnabled false`.

---

## 3. Project layout

```
XX-Launcher/
├── build.gradle              root build script (plugin versions, clean task)
├── settings.gradle           repositories + :app module
├── gradle.properties         androidx flag, JVM args
├── local.properties          (git-ignored) SDK path
├── gradlew / gradlew.bat     Gradle wrapper
├── README.md                 user-facing overview
├── MANUAL.md                 this document
├── LICENSE                   proprietary
├── docs/images/              README screenshots
└── app/
    ├── build.gradle          module config + dependencies
    ├── proguard-rules.pro    empty (release is not minified)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/piercingxx/xxlauncher/…   (24 Kotlin files)
        │   └── res/                  (layouts, values, xml, anim, font, drawable)
        ├── test/java/com/piercingxx/xxlauncher/       6 JVM unit-test classes
        └── androidTest/java/com/piercingxx/xxlauncher/ 2 instrumented test classes
```

Source size: **~5,200 lines of Kotlin** across 24 files plus ~870 lines of
resource XML. `.gradle/` and `app/build/` are generated, git-ignored, and
account for essentially all of the repository's working-tree footprint.

---

## 4. Architecture overview

There is no DI framework, no MVVM layer, and no fragments outside of the
settings screen. Activities read and write repositories directly. Shared state
lives on the `Application` object.

```
                       LauncherApplication
             (lazy singletons, process-lifetime)
   ┌──────────────┬─────────────┬──────────────┬──────────────┐
   │ SettingsRepo │  AppRepo    │ FolderManager│ ThemeManager │
   │ SharedPrefs  │ LauncherApps│ Room (SQLite)│ preset colors│
   └──────┬───────┴──────┬──────┴───────┬──────┴──────┬───────┘
          │              │              │             │
   ┌──────┴──────────────┴──────────────┴─────────────┴───────┐
   │ MainActivity   AppDrawerActivity   AppPickerActivity      │
   │ SettingsActivity   PinItemActivity                        │
   └───────────────────────────────────────────────────────────┘
          │                        │                  │
   WidgetContainer          ItemActionMenu       BackupManager
   (clock/date/weather/     (long-press action    (JSON export /
    battery, custom View)    sheets)               import)

   Background services (system-bound, not started by the app):
     GestureAccessibilityService  -> lock screen, open recents
     AppMuteListenerService       -> "Disable for…" notification muting
```

Key design decisions:

- **`LauncherApps`, not `PackageManager`, enumerates apps.** It is the only
  API that covers work profiles and reports first-install time, and it is the
  correct API for a launcher. `AppRepository` registers a `LauncherApps.Callback`
  so install/uninstall events refresh the list automatically.
- **Home slots live in SharedPreferences, not the database.** They are a fixed,
  tiny, 1-indexed array (max 8) and are read on every render; prefs are simpler
  and synchronous. The database exists only for folders.
- **Apps are identified by a string key**, not a component: `"package|userToken"`
  for apps, `"package|shortcutId|userToken"` for Android pinned shortcuts.
  `userToken` is `"personal"` for this user and `u{serial}` for every other
  profile (`"managed"` is still accepted and migrated). Serial numbers are
  stable on-device; `"personal"`/`"managed"` remain the backup-portability
  fallback when a matching profile is missing.
- **Theming is applied imperatively**, not through the Android theme system.
  Every view gets `setTextColor` / `setBackgroundColor` from
  `ThemeManager.getCurrentColors()` and `applyLauncherFont()` walks view trees
  to install the typeface. This is why dialogs need the explicit
  `applyLauncherTheme()` helper after `show()`.
- **Rows are built by hand, not with RecyclerView adapters.** The lists are
  small (tens of rows), and hand-built `TextView`s keep alignment, padding, and
  font application in one place.

### Lifecycle of a home-screen render

```
MainActivity.onResume
  ├─ applyTheme()            background + status/nav bar colors, bar visibility
  ├─ collapseFolder()        close any inline-expanded folder
  ├─ widgetContainer.rebuild()   re-read widget order, re-register receivers
  ├─ renderHomeSlots()       for slot in 1..slotCount:
  │                            read SlotEntry from prefs
  │                            build a TextView, wire tap + long-press
  ├─ applyLauncherFont()     walk the tree, install the typeface
  └─ maybeShowDefaultLauncherPrompt()
```

---

## 5. Persistence model

### 5.1 SharedPreferences (`launcher_prefs`)

One file, shared by `SettingsRepository` and the `PreferenceFragmentCompat`
(`preferenceManager.sharedPreferencesName` is set explicitly so the settings UI
writes to the same file the repositories read).

| Key | Type | Default | Notes |
|---|---|---|---|
| `slot_count` | String | `"4"` | Written as a String by `ListPreference`; reader falls back to `getInt` |
| `slot_label_N` … `slot_shortcut_N` | String/Int | — | Six keys per slot, N = 1..8 |
| `text_alignment` | String | `center` | `left` / `center` / `right` |
| `date_time_mode` | String | `date_time` | `off` / `date_only` / `date_time` |
| `status_bar_visible` | Boolean | `false` | |
| `widgets_order_csv` | String | all four | Ordered CSV; position = display order |
| `widget_tap_<widget>` | String | `""` | `"pkg\|activity\|user"` override |
| `weather_temp_unit` | String | `fahrenheit` | |
| `weather_cached_summary` / `_at` | String / Long | — | 15-minute weather cache |
| `auto_show_keyboard` | Boolean | `false` | |
| `sort_mode` | String | `default` | see `AppDrawerActivity.sortApps` |
| `swipe_left_app` / `swipe_right_app` | String? | null | `"pkg\|activity\|user\|shortcutId"` |
| `swipe_left_enabled` / `swipe_right_enabled` | Boolean | `true` | |
| `swipe_down_action` | String | `notifications` | |
| `double_tap_lock`, `home_to_recents` | Boolean | `false` | |
| `theme_preset` | String | `amoled` | key into `ThemeManager.presets`, or `custom` |
| `custom_bg_color` | String | `FF111827` | ARGB hex, stored as a String |
| `appearance_mode` | String | `dark` | drives `AppCompatDelegate` night mode |
| `font_family` | String | `jetbrains_mono_nerd` | |
| `text_size_scale` | Int | `100` | percent; `SeekBarPreference` writes an Int |
| `hidden_apps_set` | StringSet | ∅ | app keys |
| `pinned_apps_ordered` | String | `""` | ordered CSV of app keys |
| `muted_apps_set` | StringSet | ∅ | `"package\|untilEpochMillis"`, pruned on write |
| `rename_<appKey>` | String | — | user-assigned label |
| `first_run_seeded`, `hide_default_launcher_prompt` | Boolean | `false` | |

Both `slot_count` and `text_size_scale` have **dual-type readers** (`getString`
falling back to `getInt`, and `getInt` falling back to `getFloat`) because the
preference widgets and older builds disagreed about the stored type. The
`runCatching` wrappers are load-bearing, not defensive noise.

### 5.2 Room database (`launcher.db`)

| Table | Purpose |
|---|---|
| `folders` | `id` (autogen), `name`, `sortOrder` |
| `folder_members` | composite PK (`folderId`, `appId`), plus `sortOrder` |

`appId` is the app key described above. Members are resolved against
`LauncherApps` at read time for labels only. A failed or empty lookup (locked
work profile, quiet mode, binder error) keeps the stored row. Uninstalled
packages are dropped from folders in `AppRepository.onPackageRemoved` via
`FolderManager.removePackage`.

`sortOrder` is normalised to `0..n-1` on every read so pre-migration rows
(all zero) keep a stable alphabetical order.

**Migrations:** `MIGRATION_1_2` adds `folder_members.sortOrder`.
`MIGRATION_2_3` drops the unused `home_slots` table (see finding **D-1**).
Both are exported on `AppDatabase` and exercised by `FolderMigrationTest`
(instrumented): a hand-built v1 database with `folders`, `folder_members`
(no `sortOrder`), and `home_slots` migrates to v3.

User-profile tokens are `personal` for this user and `u{serial}` for every
other profile. The old `"managed"` spelling still resolves to the first extra
profile, and existing prefs/folder keys are rewritten to the serial token on
first load.

### 5.3 Backup JSON

`BackupManager` serialises every user-configurable value with Gson.
`version` is checked for **exact** equality with `BACKUP_VERSION` (1) — an
older or newer file is rejected outright rather than migrated. Missing
collection fields on a v1 file (Gson leaves them `null`, ignoring Kotlin
defaults) are coalesced to empty so import does not NPE.

Folders are exported **by name**, not id. Import **replaces** live state:
every folder is wiped and recreated, `rename_*` keys, widget tap overrides,
and mute entries are replaced (mutes are stored as `"package|untilEpochMillis"`
in `mutedApps`; files written before that field have none). Slots and
hidden/pinned lists are rewritten from the file. Slot folder references are
remapped through the new name→id map. After a successful import,
`SettingsActivity` reapplies night mode and the 1×1 wallpaper and republishes
the theme.

---

## 6. Module-by-module reference

### `com.piercingxx.xxlauncher` (root)

#### `LauncherApplication.kt` (30 lines)
Holds the four repositories as `by lazy` singletons and applies the persisted
night mode on process start. `LauncherApplication.from(context)` is the
accessor used everywhere.

#### `MainActivity.kt` (~700 lines)
The home screen and the HOME intent target.

- **Rendering** — `renderHomeSlots()` clears and rebuilds `homeSlotsContainer`,
  reading each `SlotEntry` from prefs. Temporarily unresolvable packages
  (locked work profile) stay on the slot; uninstall pruning is
  `onPackageRemoved` only. Rows are `WRAP_CONTENT` width so the tap target
  hugs the label rather than spanning the screen.
- **Inline folders** — `toggleFolder()` inserts member rows *directly below*
  the folder's slot inside the same `LinearLayout`, at a smaller text size and
  with symmetric padding so centred rows stay centred. Because the container is
  vertically centred, the list visibly grows around the folder.
  `collapseFolder()` removes exactly the views it added.
- **Gestures** — `HomeGestureListener` extends `SimpleOnGestureListener`;
  `onFling` picks an axis by comparing |dx| and |dy| against a 100 px threshold.
- **Launch animations** — `swipeAnimOptions()` builds an `ActivityOptions`
  bundle. This is load-bearing: cross-task launches only honour animations
  passed in the launch's options bundle, and only when the launch opens a
  *fresh* task. Bringing an existing task to the front always plays the system
  default; overriding that needs privileged permissions a third-party launcher
  cannot hold. `overridePendingTransition` is ignored for these launches.
- **Back handling** — an `OnBackPressedCallback` claims back so edge swipes
  collapse a folder instead of playing the system's predictive-back animation.
  This requires **both** the callback and `android:enableOnBackInvokedCallback="true"`
  in the manifest.
- **First run** — `seedFirstRunIfNeeded()` sets the flag *before* seeding, so a
  failed seed is not retried.
- **Default-launcher prompt** — uses `RoleManager.ROLE_HOME` on API 29+,
  falling back to `Settings.ACTION_HOME_SETTINGS`.
- **Accessibility** — swipe gestures have no TalkBack equivalent, so
  "Open app drawer" and "Open launcher settings" are registered as custom
  accessibility actions on the home container.

#### `AppDrawerActivity.kt` (~400 lines)
A translucent bottom sheet with a bottom-anchored search field.

- The top ~15 % of the screen (`topSpacer`) is left transparent; tapping it
  closes the sheet, and over-scrolling the list past the top by 80 dp also
  closes it.
- `renderList()` is the core. Behaviour depends on the query:
  - `!query` → render only a "Web: …" DuckDuckGo row.
  - non-empty query → match **all** apps including hidden ones (hidden apps are
    findable by search but never browsable).
  - empty query → browse list excludes hidden apps, apps already on a home
    slot, and folder members — unless explicitly pinned.
- Exactly one search result auto-launches; a leading space suppresses that.
- Enter launches the first result, or falls back to web search.
- Row suffixes: `↗` shortcut, `⊙` pinned, `•` installed in the last hour,
  `⧉` work profile. Content descriptions spell these out for TalkBack.
- Folder rows expand inline; the anchor row is located by
  `getTag(R.id.tag_folder_id)` so an async member load cannot insert rows in
  the wrong place.
- `sortApps()` implements the five sort modes; only `default` preserves the
  user's manual pinned order.

#### `AppPickerActivity.kt` (~220 lines)
A themed dialog-style list used for three jobs: choosing a home-slot target
(apps + folders + "clear"), choosing a swipe-gesture target, and choosing a
widget tap action. The caller distinguishes them via the `EXTRA_ALLOW_FOLDERS`
/ `EXTRA_ALLOW_CLEAR` / `EXTRA_CLEAR_LABEL` extras and reads the selection back
out of the result intent.

It also carries the home-slot long-press rows, each gated by its own
`EXTRA_ALLOW_*` flag and each answered by an `EXTRA_*` in the result rather
than acted on here: move up/down, Rearrange, **Change Label**, folder options,
and clear. The rename row is deliberately a result, not an action — the rename
dialog and its propagation live back in `MainActivity` /
`ItemActionMenu.showRenameForSlot()`, where the app list is.

#### `SettingsActivity.kt` (~430 lines)
Hosts `SettingsFragment : PreferenceFragmentCompat` over `R.xml.preferences`.

- Preference rows are themed as they attach via an
  `OnChildAttachStateChangeListener`, so recycled rows stay in theme.
- Non-declarative preferences are wired by key in `onCreatePreferences`:
  default-launcher role request, hidden-apps list, the widget configuration
  dialog, backup export/import, font import, custom colour, and the two swipe
  app pickers.
- `showWidgetConfigDialog()` builds a checkbox + ↑/↓ + "Tap…" row per widget
  and rebuilds itself in place after every change.
- Export/import use `ActivityResultContracts.CreateDocument` /
  `OpenDocument`; both report success and failure by toast, and a successful
  import additionally warns that permissions and custom fonts are not restored.

#### `PinItemActivity.kt`
The standard `CONFIRM_PIN_SHORTCUT` confirmation activity (API 26+). Shows a
themed confirmation dialog before accepting a pinned-shortcut request — so apps
cannot silently inject drawer rows — rejects widget requests, and refreshes the
app list. `finish()` is deferred to the dialog's dismiss listener.

### `com.piercingxx.xxlauncher.data`

#### `AppRepository.kt` (~280 lines)
- `AppInfo` — the app model. `key` is the stable identity used by hidden,
  pinned, renamed, and folder state. `matches()` is a forgiving matcher:
  case-insensitive substring on the *displayed* label, plus a
  diacritic-and-separator-stripped pass so `eink` matches `E-Ink`.
- `loadApps()` enumerates every launchable activity across all user profiles,
  excluding this package and every other installed home app, then layers
  persisted rename labels on top.
- `loadPinnedShortcuts()` adds Android pinned shortcuts as drawer rows when the
  launcher holds shortcut-host permission.
- `pruneRemovedPackage()` drops an uninstalled package from pins, hidden apps,
  and home slots.
- `launch()` goes through `LauncherApps.startMainActivity` /
  `startShortcut` so work-profile apps launch into the right user.

#### `SettingsRepository.kt` (~300 lines)
Typed accessors over the prefs file, plus the `SlotEntry` data class. Every
property is a `var` with a getter/setter pair; there is no caching, so the
settings UI and the repositories can never disagree.

#### `AppDatabase.kt` (~120 lines)
Room entities, DAOs, migrations, and the double-checked singleton.

#### `RenamePropagator.kt` (61 lines)
Pure label-propagation rules for renames, and the reason a rename shows up in
the same place everywhere. A rename is stored once in the rename map, keyed
like `AppInfo.key`, but every home `SlotEntry` also carries its own label copy
from pick time; these helpers keep the copies in agreement across home,
drawer, search, and folders. A blank rename clears the override and restores
the app's real label. `holdsSameItem()` is the guard `MainActivity`'s async
label refresh re-checks before writing, so a rename, move, or clear landing
mid-lookup cannot drop the slot's old occupant on top of its replacement.
No Android imports, so it is unit-tested directly.

#### `DefaultLayoutSeeder.kt` (~260 lines)
Builds the out-of-the-box home screen on first launch:

| Slot | Contents |
|---|---|
| Notes | Google Keep |
| Audio *(folder)* | Audiobookshelf, YouTube Music |
| Comms *(folder)* | Phone, Text, Gmail, Synology Chat, Cloud Softphone |
| Calendar | Google Calendar |
| Tools *(folder)* | Waterfox, Calculator, Camera, Synology Photos |

Swipe-left is bound to "Skippy" **by label** because it installs as a PWA and
its package name varies; swipe-right is bound to the resolved camera app. A
list of preinstalled Google/OEM apps is hidden out of the box so they only
appear via search.

The planning logic is split behind an `AppResolver` interface so it is fully
unit-testable without a device: `plan()` is pure, and `applyIfNeeded()` does
the I/O. Unresolvable apps are skipped and empty folders are never created.
`applyIfNeeded()` refuses to run if any slot or folder already exists.

### `com.piercingxx.xxlauncher.folder`

#### `FolderManager.kt`
All folder operations, each on `Dispatchers.IO` and each returning a `Result`.
`getMembers()` resolves member keys to live apps and renumbers `sortOrder`
without deleting rows that fail to resolve. `removePackage()` is the uninstall
path. `replaceAll()` is the backup-restore wipe-and-recreate. `moveInList()` is
a free `internal` function (swap with neighbour, null at the ends) so it can
be unit-tested directly. Removing a folder's last member deletes the folder
and clears any slot pointing at it.

### `com.piercingxx.xxlauncher.menu`

#### `ItemActionMenu.kt` (~630 lines)
Every long-press action sheet. Menu contents are assembled conditionally: app
info, change label, "Add to Home Screen" (first empty visible slot, growing
the slot row up to the maximum of 8 when full), hide/show, and "Disable
for…" always; pin/unpin, move up/down, and "Add to Folder" only for drawer
rows; folder-member reordering,
"Rearrange Apps", and "Remove from Folder" only inside a folder; delete
shortcut or uninstall last.

`showRenameForSlot()` is the home-slot half of "Change Label": it resolves a
`SlotEntry` back to its drawer row through `RenamePropagator.renameKey()` so
the rename runs through `AppRepository.rename()` and lands everywhere. If the
app list has not loaded yet it writes the rename label straight to prefs and
refreshes — the home screen's own label pass picks it up on the next render.

`showRearrangeDialog()` is the most involved: it rebuilds its rows in place
after every move so the sheet stays open for a run of adjustments, dims rather
than hides the end-of-list arrows so row widths stay stable, and only the
"Done" button dismisses (the neutral "Sort A–Z" button is re-wired after
`show()` so it does not auto-close).

### `com.piercingxx.xxlauncher.theme`

#### `ThemeManager.kt` (87 lines)
Seven built-in presets in display order — AMOLED Night, Graphite, Forest
Night, Ocean Drift, Burgundy, Paper, Mist, per BRAND-GUIDE §3.3 — plus a
`custom` mode whose text colour is chosen by luminance (>182 → ink).
`applyWallpaper()` mirrors the background colour onto the system wallpaper as a
1×1 bitmap so app-switch animations blend with the launcher.
`setAppearanceMode()` drives `AppCompatDelegate` night mode. `publish()` hands
the effective theme to `ThemeBroadcaster`; call it after every change that
moves the theme, and it also runs once from `LauncherApplication` on start so
freshly installed or rebooted family apps converge.

#### `ThemeBroadcaster.kt`
Sender side of the family theme-sync contract. Broadcasts
`xx.launcher.THEME_CHANGED` with `THEME_NAME` (the preset *display* name, or
`"Custom"`) and `BACKGROUND` (the resolved ARGB, always present — the only way
a receiver can honour Custom). Manifest receivers stopped getting implicit
broadcasts at Android O, so one explicit copy goes out per package via
`Intent.setPackage`, to the nine family apps in `FAMILY_PACKAGES`, restricted
by the signature permission `com.piercingxx.xxlauncher.permission.THEME_SYNC`.
Family apps must `uses-permission` that name (same signing key) or they will
not receive the broadcast. Absent packages drop it; there is no reply.
`payloads()` is the pure fan-out, so plain JUnit covers the mapping and the
per-package delivery without Robolectric — only `broadcast()` touches the
platform. `DISPLAY_NAMES` and `ThemeManager.presets` share their keys and
have to change together.

#### `FontHelper.kt` (~130 lines)
Font keys, a process-wide typeface cache, recursive typeface application over a
view tree (preserving each view's bold/italic style), and custom TTF/OTF
import. Import validates the extension, copies through the cache directory,
verifies the file actually parses as a typeface, and only then promotes it into
`filesDir`.

#### `DialogTheming.kt` (64 lines)
`AlertDialog.applyLauncherTheme()` — must be called **after** `show()` because
the button bar does not exist before then. Paints a rounded background with a
hairline stroke (so the sheet is visible even when it matches the screen
colour), recolours the whole decor tree, and installs a hierarchy-change
listener so lazily-created list rows are themed as they land.

### `com.piercingxx.xxlauncher.widgets`

#### `WidgetContainer.kt` (~250 lines)
A custom `LinearLayout` that rebuilds itself from `settings.widgetsOrder`.
Registers `ACTION_TIME_TICK` and `ACTION_BATTERY_CHANGED` receivers only when
the corresponding widget exists, and unregisters on detach. Weather is cached
for 15 minutes; a cached reading always wins over "Loading…" so refreshes are
invisible, and a failed refresh leaves the stale reading in place. Each widget
supports a user-configured tap override, falling back to a sensible default
(clock → alarms, date → calendar, weather → weather app).

### `com.piercingxx.xxlauncher.weather`

#### `WeatherHelper.kt` (108 lines)
Keyless Open-Meteo current-weather lookup over `HttpURLConnection` with 5 s
timeouts. Location comes from the best (most recent) last-known fix across the
network, passive, and GPS providers — no active location request is ever made.
`parseWeatherSummary()` and `toWeatherLabel()` are `internal` so they can be
unit-tested with the real `org.json` on the JVM.

### `com.piercingxx.xxlauncher.backup`

#### `BackupManager.kt`
Versioned JSON export/import. See §5.3. `parseBackupJson` / `normalized()` are
`internal` so sparse v1 files and the mute list are JVM-testable.

### `com.piercingxx.xxlauncher.accessibility` / `com.piercingxx.xxlauncher.notification`

#### `GestureAccessibilityService.kt` (46 lines)
Exists only so `performGlobalAction` is available for lock-screen and recents.
It ignores every event and does not retrieve window content
(`canRetrieveWindowContent="false"`).

#### `AppMuteListenerService.kt` (55 lines)
Implements "Disable for…": while a package's mute deadline is in the future,
every notification it posts is cancelled. On connect it sweeps anything posted
while the listener was down.

### `com.piercingxx.xxlauncher.util`

#### `SystemActions.kt`
Context/Activity/View extensions: user-profile token serialisation
(`personal` / `u{serial}`, with `"managed"` as a fallback), toasts,
notification-drawer expansion (via reflection on `StatusBarManager` — there is
no public API), dialer/camera/alarm/calendar/weather/web-search launchers,
app-info and uninstall intents, e-ink detection (≤30 Hz
refresh disables animations), keyboard show/hide, and status/navigation bar
visibility.

The nav-bar helper deliberately **does nothing under gesture navigation**:
hiding the pill turns the system's swipe-up into a reveal-bars swipe, which
would steal the app-drawer gesture. Under gesture nav the bottom edge stays
Android's; swipes elsewhere belong to the launcher.

---

## 7. Resource reference

| Resource | Purpose |
|---|---|
| `layout/activity_home.xml` | Two overlaid full-height columns: widgets centred in the top half, slots centred in the bottom two thirds |
| `layout/activity_app_drawer.xml` | 15 % transparent spacer + sheet, `fillViewport` scroll with bottom gravity, search pinned at the bottom |
| `layout/activity_app_picker.xml` | Title + scrolling row container |
| `layout/preference_theme_strip.xml` | Title + horizontally scrolling swatch strip |
| `values/themes.xml` | `Theme.Launcher` and its Home / Drawer / Dialog / Settings variants |
| `values/attrs.xml` | `homeBackgroundColor`, `homeTextColor` theme attributes |
| `values/colors.xml` | Icon background and the pre-theme default home colours |
| `values/ids.xml` | `tag_folder_id`, used as a view tag key |
| `values/arrays.xml` | Entries/values for every `ListPreference` |
| `values/strings.xml` | All user-facing text |
| `xml/preferences.xml` | The settings screen |
| `xml/accessibility_config.xml` | Accessibility service declaration |
| `xml/backup_rules.xml`, `xml/data_extraction_rules.xml` | Cloud backup / device transfer scope (prefs + `launcher.db`) |
| `anim/slide_up.xml`, `slide_down.xml` | Drawer open/close |
| `anim/slide_in_left.xml`, `slide_in_right.xml` | Swipe-launch directional animations |
| `font/*.ttf` | JetBrains Mono, JetBrains Mono Nerd, Space Mono |
| `drawable/ic_launcher_foreground.xml`, `mipmap-anydpi-v26/ic_launcher.xml` | Adaptive launcher icon (the XX logomark on the Ink ground) |

Note that `TextAppearance.Launcher.SearchResult` is the only text appearance
still referenced from a layout; every other text style is applied
programmatically from `ThemeManager`.

---

## 8. Manifest, permissions, and services

| Permission | Why |
|---|---|
| `ACCESS_COARSE_LOCATION` | Weather widget (last-known fix only; runtime-requested) |
| `INTERNET` | Open-Meteo request |
| `EXPAND_STATUS_BAR` | Swipe-down notification drawer |
| `SET_WALLPAPER` | Mirror the theme colour onto the wallpaper |
| `REQUEST_DELETE_PACKAGES` | "Uninstall" from the item action menu |
| `com.piercingxx.xxlauncher.permission.THEME_SYNC` | Signature permission declared and held by the launcher; `sendBroadcast` requires receivers to hold it too |

`<queries>` declares the intents the launcher resolves against under Android 11+
package visibility: MAIN/LAUNCHER (which makes every launchable app visible),
DIAL, CALL, and http/https VIEW.

| Component | Export | Notes |
|---|---|---|
| `MainActivity` | exported | HOME + DEFAULT categories, `singleTask`, portrait |
| `AppDrawerActivity` | internal | translucent, `adjustResize`, **no** fixed orientation (translucent activities may not fix orientation on API 26/27) |
| `AppPickerActivity` | internal | dialog theme, portrait |
| `PinItemActivity` | exported | `CONFIRM_PIN_SHORTCUT`, excluded from recents |
| `SettingsActivity` | exported | referenced by the accessibility service's settings link |
| `GestureAccessibilityService` | internal | `BIND_ACCESSIBILITY_SERVICE` |
| `AppMuteListenerService` | internal | `BIND_NOTIFICATION_LISTENER_SERVICE` |

Both services are opt-in: the user must enable them in system settings, and the
app routes them there on first use (`ACTION_ACCESSIBILITY_SETTINGS`,
`ACTION_NOTIFICATION_LISTENER_SETTINGS`).

---

## 9. Test suite

### JVM unit tests — `app/src/test/`

| Class | Covers |
|---|---|
| `AppInfoMatchTest` | Search matching: case-insensitivity, separator and diacritic stripping, blank query, renamed labels |
| `AppKeyTest` | Two- and three-part keys, `managed` → serial rewrite, embedded swipe/widget user tokens |
| `BackupDataTest` | Sparse v1 JSON does not NPE; mute list round-trips; restore payload is the file's keys only |
| `DefaultLayoutSeederTest` | Seeding plan against a fake resolver: full install, nothing installed, package fallbacks, empty-folder skipping |
| `MoveInListTest` | `moveInList` swap semantics, end-of-list failure, immutability |
| `RenamePropagatorTest` | Rename keys for apps, shortcuts and folders; blank-resets-to-real-label; the `holdsSameItem` guard against a stale async label write |
| `ThemeBroadcasterTest` | Preset key → display name, the Custom fallback, the action/extra/permission constants against the family receivers, and one payload per family package |
| `WeatherHelperTest` | Open-Meteo payload parsing and weather-code mapping |

These run anywhere. `org.json:json` is a test dependency because the Android
SDK's `org.json` stub throws on every call.

### Instrumented tests — `app/src/androidTest/`

| Class | Covers |
|---|---|
| `LauncherSmokeTest` | Every activity launches; home renders `slotCount` rows; home is portrait-locked; drawer search field is shown; slot writes survive a fresh repository |
| `FolderOrderTest` | Folder member ordering against the live on-device database (a fresh install creates v3; this is not a migration test) |
| `FolderMigrationTest` | `MigrationTestHelper`: v1 `folders` / `folder_members` / `home_slots` → v3 keeps members, adds `sortOrder`, drops `home_slots` |

`FolderOrderTest` needs at least three launchable apps on the device and
cleans up a leftover test folder from an interrupted run before starting.

**Not covered by tests:** `ItemActionMenu`'s dialogs, `WidgetContainer`,
theming, font import, and `ThemeBroadcaster.sendBroadcast` itself (only the
pure fan-out and the permission constant are locked).

---

## 10. Audit — findings

Every item below was verified against the source. Status reflects the state
**after** the cleanup pass in this change.

### 10.1 Dead code

| ID | Finding | Location | Status |
|---|---|---|---|
| D-1 | **`HomeSlot` entity, `HomeSlotDao` (6 queries), and `AppDatabase.homeSlotDao()` are entirely unused.** Home slots live in SharedPreferences. A whole `home_slots` SQLite table was being created and migrated for nothing. | `data/AppDatabase.kt` | **Removed** (DB v2→v3, `MIGRATION_2_3` drops the table) |
| D-2 | `FolderDao.clearFolder()` unused — deletion goes through `delete(folder)`. | `data/AppDatabase.kt:72` | **Removed** |
| D-3 | `SettingsRepository.registerListener()` / `unregisterListener()` — no caller ever observes preference changes. | `data/SettingsRepository.kt:74` | **Removed** |
| D-4 | `SettingsRepository.setSlotLabel()` unused; labels are written through `setSlot()`. | `data/SettingsRepository.kt:225` | **Removed** |
| D-5 | `SettingsRepository.weatherEnabled` unused; `WidgetContainer` checks `widgetsOrder` directly. | `data/SettingsRepository.kt:112` | **Removed** |
| D-6 | `SlotEntry.isShortcut` unused (the `AppInfo.isShortcut` of the same name *is* used, which masked it). | `data/SettingsRepository.kt:18` | **Removed** |
| D-7 | `AppRepository.getApp()` unused. | `data/AppRepository.kt:227` | **Removed** |
| D-8 | `AppInfo.originalLabel` is written at all six construction sites and **never read**. | `data/AppRepository.kt:33` | **Removed** |
| D-9 | `GestureAccessibilityService.isRunning` unused (callers null-check `instance` via `lockScreen()`/`openRecents()`). | `accessibility/GestureAccessibilityService.kt:32` | **Removed** |
| D-10 | `BackupManager`'s `context` constructor parameter is never used. | `backup/BackupManager.kt:65` | **Removed** |
| D-11 | Six unused imports: `ComponentName` + `Dispatchers` (ItemActionMenu), `Canvas` + `Color` + `Build` (ThemeManager), `Process` (AppRepository). | various | **Removed** |
| D-12 | **A duplicated KDoc block** — `swipeAnimOptions()` carries two consecutive doc comments saying the same thing, the first a superseded draft of the second. | `MainActivity.kt:459-472` | **Removed** (kept the complete second block) |
| D-13 | `res/xml/shortcuts.xml` declares a static "Settings" app shortcut that **is never referenced from the manifest** (no `android.app.shortcuts` meta-data), so it has never shipped. It would also be invisible in practice: this app's own icon is excluded from its drawer. | `res/xml/shortcuts.xml` | **Deleted** |
| D-14 | `res/drawable/ic_settings.xml` was used only by the dead `shortcuts.xml`. | `res/drawable/ic_settings.xml` | **Deleted** |
| D-15 | Five unused string-arrays: `widget_entries`, `widget_values`, `default_widgets` (superseded by the custom widget dialog), `theme_presets`, `theme_preset_values` (superseded by `ThemePreviewPreference` reading `ThemeManager.presets`). | `res/values/arrays.xml` | **Deleted** |
| D-16 | Seven unused strings: `weather_permission_rationale`, `weather_refresh`, `folder_member_count`, `search_no_results`, `toast_renamed`, `accessibility_home_slot`, `accessibility_folder_member_count`. | `res/values/strings.xml` | **Deleted** |
| D-17 | Four unused text-appearance styles: `TextAppearance.Launcher.HomeSlot`, `.Widget`, `.Widget.Clock`, `.FolderMember` — all superseded by programmatic theming. | `res/values/themes.xml` | **Deleted** |
| D-18 | `declare-styleable name="LauncherPreference"` with a custom `summary` attribute — unused, and shadowing a framework attribute name in the app namespace is a hazard. | `res/values/attrs.xml` | **Deleted** |
| D-19 | `<queries>` declares `android.intent.action.CALENDAR`, which is **not a real Android action**. The calendar is opened via `ACTION_VIEW` on a `CalendarContract` URI. Dead declaration. | `AndroidManifest.xml:23` | **Removed** |

### 10.2 Defects

| ID | Severity | Finding | Status |
|---|---|---|---|
| **B-1** | Medium | **Folder members store 3-part shortcut keys but are parsed as 2-part.** `FolderManager.getMembers()` does `appId.substringBefore("\|")` / `substringAfter("\|")`, so a pinned shortcut added to a folder (`"pkg\|shortcutId\|user"`) yields `userToken = "shortcutId\|user"`. That is not `"managed"`, so it silently resolves to the *personal* profile. For a work-profile shortcut the wrong user is used. `BackupManager.importFromJson()` has the identical parse. | **Fixed** — both sites now split on `\|` and take first/last, which is correct for 2- and 3-part keys alike. Shortcut *identity* inside folders remains unsupported (see L-1). |
| **B-2** | Medium | **The weather widget can wedge permanently.** `refreshWeather()` sets `weatherLoading = true` and only clears it at the end of the coroutine. `onDetachedFromWindow()` cancels that coroutine, so the flag is never cleared — and `refreshWeather()` starts with `if (weatherLoading) return`. Any subsequent refresh on that instance is a no-op. | **Fixed** — the flag is now cleared in a `finally` block. |
| **B-3** | Medium | **Widget broadcast receivers are never unregistered when a widget is turned off.** `rebuild()` nulls the views but `registerReceivers()` only ever *adds*; `timeTickRegistered` / `batteryRegistered` stay true, so the app keeps waking for `ACTION_TIME_TICK` / `ACTION_BATTERY_CHANGED` to update views that no longer exist. | **Fixed** — `rebuild()` now unregisters receivers whose widget is gone. |
| **B-4** | Medium | **Latent crash on restore-after-process-death.** The backup export/import callbacks use `viewLifecycleOwner.lifecycleScope`. `registerForActivityResult` results can be delivered before the fragment's view exists, and `viewLifecycleOwner` throws `IllegalStateException` in that window. | **Fixed** — switched to the fragment's own `lifecycleScope`. |
| **B-5** | Medium | **Action-sheet coroutines outlive the activity.** `ItemActionMenu` uses a bare `MainScope()` that is never cancelled. A folder rearrange or add-to-folder sheet launched just before the activity finishes will call `AlertDialog.show()` on a dead window → `BadTokenException`. | **Fixed** — the menu now uses the host activity's `lifecycleScope` when the context is a `LifecycleOwner`, falling back to `MainScope()` otherwise. |
| **B-6** | Low | **Reducing the home slot count hides apps from the drawer.** `homeSlotKeys()` scans all 8 slots regardless of `slotCount`, so an app assigned to slot 6 and then hidden by lowering the count to 4 disappears from the browse list entirely — visible neither on the home screen nor in the drawer. | **Fixed** — only slots that actually render are excluded from browsing. |
| **B-7** | Low | **Pinned-shortcut home slots do not survive a backup.** `BackupSlot` has no `shortcutId` field, so a slot pointing at an Android pinned shortcut restores as a plain package launch, which then silently fails and clears the slot. | **Fixed** — `shortcutId` is now round-tripped (nullable, so existing v1 backups still import). |
| **B-8** | Low | **`GLOBAL_ACTION_LOCK_SCREEN` requires API 28 but `minSdk` is 24.** It is an inlined `int`, so there is no `NoSuchFieldError`; on API 24–27 `performGlobalAction(8)` just returns false and the user gets the "enable the accessibility service" toast, which is misleading. | **Fixed** — guarded by an SDK check with an explanatory comment. |
| **B-9** | Low | **Two format strings use multiple non-positional substitutions.** `accessibility_home_slot_filled` (`%s`, `%d`) and `accessibility_folder_row` (`%s`, `%d`) — aapt2 warns on every build, and translations cannot reorder the arguments. | **Fixed** — converted to positional (`%1$s`, `%2$d`). |
| **B-10** | Low | **README states version `0.61`**; the build is `0.7` (`versionCode 70`). | **Fixed** |
| **B-11** | Low | **`preferences.xml` hardcodes `android:summary="1.0"` on the version row.** It is overwritten at runtime from `BuildConfig.VERSION_NAME`, but the stale literal flashes on first bind and is wrong if the lookup ever fails. | **Fixed** — literal removed. |
| **B-12** | Low | **Hardcoded UI string** `"Select App"` in `activity_app_picker.xml`. | **Fixed** — moved to `@string/picker_title`. |
| **B-13** | Low | **Deprecated Gradle API.** The root `clean` task uses `rootProject.buildDir`, deprecated in Gradle 8 and slated for removal in 9. | **Fixed** — uses `layout.buildDirectory`. |

### 10.3 Hygiene and non-defects

These were reviewed and are either intentional, harmless, or deliberately left
alone. They are recorded so the next audit does not re-litigate them.

| ID | Note |
|---|---|
| H-1 | `overridePendingTransition` is deprecated (2 warnings). The replacement, `overrideActivityTransition`, is API 34+ while `minSdk` is 24, and for the drawer's *same-task* transitions the deprecated call is still the only thing that works across the supported range. **Left as-is.** |
| H-2 | `ClickableViewAccessibility` lint (4). `MainActivity`'s touch listener is a gesture detector, and the same actions are already exposed as explicit `ViewCompat` accessibility actions; the drawer's listener returns `false` and never consumes a click. **Suppressed with a justifying comment** rather than adding a fake `performClick()`. |
| H-3 | `LockedOrientationActivity` / `DiscouragedApi` lint (3+3): the home, picker, and settings activities are portrait-locked. That is a deliberate product decision for a phone launcher. **Left as-is.** |
| H-4 | 11 `GradleDependency` warnings (AppCompat 1.6.1→1.7.1, Room 2.6.1→2.8.4, Material 1.11→1.14, coroutines 1.7.3→1.9.0, etc.). Dependency upgrades are a separate, testable change, not a cleanup. **Left as-is — worth scheduling.** *Since:* Room moved to 2.7.2 with the toolchain bump; AppCompat, Material and coroutines are still on the audited versions. |
| H-5 | `KaptUsageInsteadOfKsp`: Room still uses kapt. Migrating to KSP is a real build-speed win and a self-contained change. **Left as-is — worth scheduling.** |
| H-6 | `SettingsActivity` imports `androidx.recyclerview.widget.RecyclerView` but RecyclerView is not a declared dependency — it arrives transitively through `androidx.preference`. It compiles and runs, but a preference-library bump could break it. **Documented, not changed.** |
| H-7 | **Gson ignores Kotlin default values.** Gson constructs `BackupData` through `Unsafe`, bypassing the constructor, so a field missing from the JSON becomes `null` even though the Kotlin type is non-null — not the declared default. In practice a malformed backup fails inside the caller's `runCatching` and surfaces as "Restore failed", so the blast radius is a rejected import rather than a crash. A proper fix means moving to `kotlinx.serialization` or adding explicit null handling. **Documented, not changed.** |
| H-8 | `AppRepository` never unregisters its `LauncherApps.Callback` and never cancels its `CoroutineScope`. This is correct: the repository is `Application`-scoped and lives for the process. |
| H-9 | `accessibility_config.xml` requests `typeWindowStateChanged` events that the service discards. Harmless (it cannot retrieve window content), but it does mean the system delivers events for nothing. Some OEM builds refuse to list a service that requests no event types, so this is left alone. |
| H-10 | `MainActivity` uses ViewBinding; every other activity uses `findViewById`. Inconsistent but not wrong. |
| H-11 | `app/debug.keystore` is committed. This is the public, universally-known Android debug keystore — no secret is exposed and it cannot sign a release build. **Intentional**, per the `.gitignore` comment. *Since:* reversed. The keystore was removed from the repo and `.gitignore` now excludes `*.keystore` outright. See §2, Signing. |
| H-12 | `.gradle/` and `app/build/` are generated and git-ignored. Removable at any time with `./gradlew clean`; they are not tracked and never entered version control. |
| H-13 | `MainActivity.seedFirstRunIfNeeded()` sets `firstRunSeeded = true` *before* seeding, so a failed seed never retries. Deliberate: retrying on every launch would be worse than an empty home screen. |
| H-14 | `preferences.xml` hardcodes English preference titles instead of using `@string` resources, while `strings.xml` is fully populated for everything else. Not an error — the app ships English-only — but it is the one place localisation would break. |

### 10.4 Verification

After the cleanup:

```
JAVA_HOME=~/.jdks/jdk-17.0.20+8 ./gradlew clean assembleDebug testDebugUnitTest lint
BUILD SUCCESSFUL in 32s
```

| Check | Before | After |
|---|---|---|
| Build | success | success |
| JVM unit tests | 20 passed | **20 passed**, 0 failed, 0 skipped |
| Kotlin warnings | 2 | 2 (both `overridePendingTransition`, see H-1) |
| aapt2 resource warnings | 2 | **0** |
| Lint warnings | 59 | **31** |
| Kotlin source lines | ~2,950 | ~2,810 |

Lint categories eliminated outright: `UnusedResources` (18), `InlinedApi` (1),
`ClickableViewAccessibility` (4), `HardcodedText` (1), `UnusedAttribute` (3 of 5),
`PluralsCandidate` (2 of 4). Every one of the 31 remaining warnings is
catalogued in §10.3 as an accepted trade-off or scheduled work.

**Not verified on-device.** No phone was attached during this pass, so the
instrumented suite (`LauncherSmokeTest`, `FolderOrderTest`) did not run, and the
new `MIGRATION_2_3` was not exercised against a live v2 database. The migration
was instead checked statically against Room's generated validator
(`AppDatabase_Impl.createAllTables`), which now expects exactly `folders` and
`folder_members` — precisely what dropping `home_slots` leaves behind, with the
other two tables untouched from v2. **Run the instrumented suite on a device
before shipping**, since `FolderOrderTest` opens the real database and is the
test that would catch a bad migration:

```sh
JAVA_HOME=~/tools/jdk-21.0.12.1+1 ./gradlew connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

Still not run. A Pixel 6 under GrapheneOS now carries the launcher, but the
instrumented suite has not been pointed at it, so `MIGRATION_2_3` remains
statically checked only.

### 10.5 Files deleted

```
app/src/main/res/xml/shortcuts.xml         (D-13)
app/src/main/res/drawable/ic_settings.xml  (D-14)
```

No other file was removed; everything else was an edit in place. `.gradle/` and
`app/build/` were rebuilt from clean and contain only current output.

---

## 11. Known limitations (by design)

| ID | Limitation |
|---|---|
| L-1 | **Android pinned shortcuts cannot be true folder members.** A shortcut added to a folder is stored under its 3-part key but resolved through `LauncherApps.getActivityList()`, so the row launches the shortcut's *host app* rather than the shortcut. Supporting this properly means teaching `FolderManager.getMembers()` to resolve shortcuts, which is a feature, not a fix. |
| L-2 | **Cross-task launch animations only work for fresh task opens.** Bringing an app already in recents to the front always plays the system default. Overriding that requires `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`, which a third-party launcher cannot hold. Exhaustively verified on-device; do not retry. |
| L-3 | **The gesture-navigation pill is left alone.** Hiding it would convert Android's swipe-up into a reveal-bars swipe and break the app-drawer gesture. The bottom edge belongs to the system under gesture nav. |
| L-4 | **Backup does not restore runtime permissions or imported custom fonts.** The user is told this by toast after a successful import. |
| L-5 | **Backup format version is checked for exact equality.** There is no migration path between backup versions; a v1 file will be rejected outright by a future v2 build. |
| L-6 | **The notification drawer is expanded by reflection** on `android.app.StatusBarManager#expandNotificationsPanel`. There is no public API. If a future Android release blocks it, swipe-down silently does nothing (the call is exception-wrapped). |
| L-7 | **Weather uses only last-known location.** No active location request is made, so a device that has not had a fix recently shows "Weather unavailable". |
| L-8 | **Release builds are unsigned and unminified.** `proguard-rules.pro` is empty and there is no release signing config. |

---

## 12. Maintenance playbook

### Adding a home-screen setting
1. Add the key + typed accessor to `SettingsRepository`.
2. Add the widget to `res/xml/preferences.xml` (and entries/values to
   `arrays.xml` for a `ListPreference`).
3. If it needs code (not just a stored value), wire it by key in
   `SettingsFragment.onCreatePreferences`.
4. Add the field to `BackupData` **and** to both the export and import halves of
   `BackupManager` — they are two independent lists and drift silently.
5. Read it in `MainActivity.onResume`'s render path.

### Adding a widget type
1. Add the key to `SettingsRepository.ALL_WIDGETS`.
2. Add a `when` branch in `WidgetContainer.rebuild()`.
3. If it needs a broadcast, extend `syncReceivers()` **and** add a matching
   `unregisterX()` helper — `syncReceivers()` runs on every `rebuild()` and is
   responsible for both registering and dropping receivers, and
   `onDetachedFromWindow()` must drop it too.

### Changing the database schema
1. Bump `@Database(version = …)` in `AppDatabase.kt`.
2. Add a `Migration` object and register it in `addMigrations(…)`.
3. `FolderMigrationTest` is the migration test (hand-built v1 → v3).
   `FolderOrderTest` only covers live ordering on a current-schema database.

### Before every release
```sh
./gradlew clean assembleDebug testDebugUnitTest lint
```
Bump `versionName` and set `versionCode = versionName × 100` in
`app/build.gradle`, and update the version line in `README.md`.
