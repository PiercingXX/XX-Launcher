# XX-Launcher

> Your phone as a list of words. No icons, no wallpaper, no grid — and every
> other XX app takes its colors from here.

| Home | Folders | Drawer Search |
|------|---------|---------------|
| ![Home](docs/images/pixel-10-pro.png) | ![Folders](docs/images/folder-dropdown.png) | ![Drawer Search](docs/images/drawer-search.png) |

### Theme presets

![Theme presets](docs/images/theme-presets.jpg)

A text-only Android home screen: a centered column of app labels, up to 8 slots,
folders that drop open inline, and a widget block if you want one. Everything
else is a gesture — swipe up for the drawer, left/right to launch, down for
notifications or web search, double-tap to lock. It was the first app in the XX
suite, and every one after it inherited the look.

## What you get ⚙️

- Search-first drawer. Enter launches the top hit, `!query` goes to DuckDuckGo
- Reorderable widgets (clock, date, weather, battery) and renaming that sticks
  across home, drawer, search and folders alike
- Seven theme presets plus a custom background color, light/dark/system,
  adjustable text size, bundled JetBrains Mono / Nerd / Space Mono or your own TTF
- Hidden apps, per-app notification muting, work profiles, pinned shortcuts,
  full JSON backup and restore

Layout, folders, renames and settings live in one private `SharedPreferences`
file. The only outbound request in the codebase is the Open-Meteo weather lookup
— coarse location, no key, no account. There is no analytics to disable.

## Theme sync: this app is the sender 🌀

Every other XX app is a receiver; the launcher decides. Pick a preset in
**Settings → Theme** and it fans out `xx.launcher.THEME_CHANGED` to each family
package with the preset name and the resolved background ARGB — the ARGB is the
only way a receiver can honor `Custom`. Eight names in the contract: AMOLED
Night, Graphite, Forest Night, Ocean Drift, Burgundy, Paper, Mist, Custom.
The broadcast is signature-restricted
(`com.piercingxx.xxlauncher.permission.THEME_SYNC`); family apps must
`uses-permission` that name under the same signing key. Verified end to end
on a Pixel 6 under GrapheneOS: pick a preset here, the siblings repaint.

## Building 🧪

Gradle 8.11.1, AGP 8.9.1, Kotlin 2.1.20. `minSdk` 24, `compileSdk`/`targetSdk`
35, JVM target 17 (JDK 17 or newer to build).

```sh
./gradlew assembleDebug          # APK lands in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # JVM unit tests, no device needed
./gradlew installDebug
```

Then set XX-Launcher as your default launcher when prompted.

**The instrumented tests uninstall the app when they finish**, wiping folders
and settings. Never point them at a phone you actually use without
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.

[MANUAL.md](MANUAL.md) is the developer reference: file-by-file, data flow,
persistence model, the full theme-sync contract, and how to add a family app.

## Version & license

0.7 (`versionCode` 70). Proprietary — see [LICENSE](LICENSE). Bundled fonts are
under their own terms.
