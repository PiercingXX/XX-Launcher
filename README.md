# XX-Launcher

> Your phone as a list of words. No icons, no wallpaper, no grid — and every
> other XX app takes its colors from here.

| Home | Folders | Drawer Search |
|------|---------|---------------|
| ![Home](docs/images/pixel-10-pro.png) | ![Folders](docs/images/folder-dropdown.png) | ![Drawer Search](docs/images/drawer-search.png) |

### Theme presets

![Theme presets](docs/images/theme-presets.jpg)

## Features ⚙️

- **Text-based home screen** — up to 8 app slots plus inline folders that
  expand in place, centered layout, no icons
- **Fast app drawer** — bottom-anchored search, swipe up to open, `!query`
  jumps straight to DuckDuckGo, Enter launches the top hit
- **Folders** — named folders that live in home slots and drop open inline,
  contents in whatever order you arrange them
- **Gestures** — swipe left/right to launch apps, swipe down for notifications
  or web search, double-tap to lock. Lock and recents go through the bundled
  accessibility service; nothing else does
- **Widgets** — clock (12/24h follows system), date, weather, battery.
  Reorderable, with per-widget tap actions
- **Rename anything** — long-press a home slot → **Change Label**, or rename
  from a drawer search result. Same rename either way (see below)
- **Theming** — seven presets plus a custom background color,
  light/dark/system appearance, adjustable text size
- **Fonts** — bundled JetBrains Mono, JetBrains Mono Nerd, and Space Mono, or
  import your own TTF/OTF
- **Quiet by default** — hide apps from the drawer, per-app notification
  muting, work-profile support
- **Backup/restore** — full JSON export and import of every setting
- **Pinned shortcuts** — Android pinned-shortcut support in the drawer and
  home slots

Layout, folders, renames and settings live in one private `SharedPreferences`
file. The only thing this app fetches over the network is weather — Open-Meteo,
coarse location, no API key, no account. There is no analytics code to disable.

## Theme sync: this app is the sender 🌀

Every other XX app on the phone is a receiver. The launcher is the one that
decides.

Pick a preset in **Settings → Theme** and `ThemeBroadcaster` sends one explicit
`xx.launcher.THEME_CHANGED` per family package (manifest receivers stopped
getting implicit broadcasts in Android O, so it fans out rather than
broadcasting once):

| Extra | Type | Meaning |
|-------|------|---------|
| `xx.launcher.extra.THEME_NAME` | String | Preset display name, or `Custom` |
| `xx.launcher.extra.BACKGROUND` | Int | Resolved background ARGB — always sent |

Eight names in the contract: **AMOLED Night** (`#000000`), **Graphite**
(`#131316`), **Forest Night**, **Ocean Drift**, **Burgundy**, **Paper**,
**Mist**, and **Custom**. Receivers match the name case-insensitively; the ARGB
is always included because it is the only way a receiver can honor Custom.
Verified end-to-end on a Pixel 6 running GrapheneOS: pick a preset here, the
siblings repaint.

Adding a new family app means adding its package to
`ThemeBroadcaster.FAMILY_PACKAGES` and shipping a `ThemeSyncReceiver` on the
other end. The fan-out is pure Kotlin, so plain JUnit covers the mapping and
the per-package delivery without Robolectric.

## Renaming 🛠️

Long-press an occupied home slot and choose **Change Label**. Previously a
rename was only reachable from a drawer search result — and the drawer hides
apps already sitting on a home slot, so an app on the home screen could not be
renamed at all. That hole is closed.

A rename writes to the rename map *and* to every home slot holding that app, so
home, drawer, search and folders agree on the name. Blank input clears the
rename and restores the app's real label. The propagation rules are pure
functions in `data/RenamePropagator.kt`, gated on the slot still holding the
same item — a slow label lookup can no longer write a stale occupant back over
a slot that changed underneath it.

## Building 🧪

Gradle 8.11.1, AGP 8.9.1, Kotlin 2.1.20. `compileSdk`/`targetSdk` 35,
`minSdk` 24, JVM target 17 (JDK 17 or newer to build).

```sh
./gradlew assembleDebug          # APK lands in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # 38 unit tests, no device needed
./gradlew installDebug
```

Then set XX-Launcher as your default launcher when prompted, or via
Settings → Default Launcher.

The instrumented tests in `app/src/androidTest/` run against a real device —
and Gradle **uninstalls the app when they finish**, wiping its folders and
settings. Never point them at a phone you actually use unless you pass:

```sh
./gradlew connectedDebugAndroidTest \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

`MANUAL.md` is the developer reference: file-by-file, data flow, persistence
model.

## Version

0.7 (`versionCode` 70)

## License

Proprietary — see [LICENSE](LICENSE). Bundled fonts are under their own terms.
