# XX-Launcher

> Your phone as a list of words. No icons, no wallpaper, no grid.

| Home | Folders | Drawer Search |
|------|---------|---------------|
| ![Home](docs/images/pixel-10-pro.png) | ![Folders](docs/images/folder-dropdown.png) | ![Drawer Search](docs/images/drawer-search.png) |

![Theme presets](docs/images/theme-presets.jpg)

Text-first Android home. Eight slots, folders that drop open inline, a widget
block if you want one. Swipe up for the drawer, left/right to launch, down for
notifications or web search, double-tap to lock. It was the first app in the
suite. Everything after it inherited the look.

- Search-first drawer. Enter launches the top hit. `!query` goes to DuckDuckGo.
- Widgets: clock, date, weather, battery. Renames stick everywhere.
- Theme presets plus a custom color. One family theme, set once.
- Hidden apps, per-app notification muting, work profiles, pinned shortcuts,
  JSON backup.

Settings live on the device. The only outbound request is Open-Meteo for the
weather widget — coarse location, no key, no account. No analytics.

```
package: com.piercingxx.xxlauncher    minSdk 24
```

## Build

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew installDebug
```

Then set it as the default launcher.

**Instrumented tests uninstall the app when they finish.** Do not run them on a
phone you actually use unless you pass
`-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`.

[MANUAL.md](MANUAL.md) is the developer reference.

## License

0.7 (`versionCode` 70). Proprietary — see [LICENSE](LICENSE). Bundled fonts
are under their own terms.
