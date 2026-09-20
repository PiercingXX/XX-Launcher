# XX-Launcher — Remaining work

**2026-09-04.** Review follow-up @ `7486b09` is implemented. Remaining
work is **device smoke + release packaging**, not a new feature surface.

Package: `com.piercingxx.xxlauncher`  
Text-only AMOLED home. Theme **sender** for the family
(`xx.launcher.THEME_CHANGED` + `THEME_SYNC`).

```
Status: home, drawer, folders, widgets, backup/restore, gestures, theme
broadcast to 14 family packages. Release unsigned, unminified.
Instrumented tests uninstall the app — do not run them on the daily driver.
```

---

## Estate (locked 2026-09-20)

No fabric login here — the launcher is the home shell and theme **sender**,
not a door client. xx-apps catalogs this package and may uninstall it when
the Skippy user is disabled. Suite backup (`SuiteBackupProvider` → xx-apps →
skippy-tel) is the phone snapshot of launcher layout/prefs. No per-app
`:845x` in the UX.

## Locked now (2026-09-04)

| ID | Decision |
|---|---|
| L1 | Device smoke **is** the remaining product work. Known limitations L-1–L-8 in MANUAL.md stay limitations unless reopened here. |
| L2 | Theme send does **not** require the same signing key (Weather / Vitals / Nope-Mode debug keys differ). Receivers gate on the permission name. |

---

## Device smoke

- [ ] Set as default HOME on caiman. Cold boot lands here, not Pixel Launcher.
- [ ] Eight home slots + one folder: launch, rename, remove. Failed launch does not clear the slot.
- [ ] Drawer search + `!query`. Hidden apps stay hidden across reboot.
- [ ] Swipe L/R (camera / dialer), swipe down, swipe up drawer, double-tap lock.
- [ ] Clock / date / weather / battery widgets. Clock widget opens **xx-clock**, not a chooser.
- [ ] Change theme → family apps restyle (calculator, weather, clock, files, email, keyboard IME chrome).
- [ ] Backup JSON → wipe launcher data → restore is a **replace**, including mutes.
- [ ] Recents / lock toasts: missing accessibility vs unsupported SDK are distinct.

**Accept:** dated notes. Then this is daily-driver home.

---

## Release

- [ ] Signing config for a non-debug APK (keystore gitignored).
- [x] Decide minify: default **off** (MANUAL L-8) unless a size problem appears.
- [x] CI: `./gradlew testDebugUnitTest` on push (`.github/workflows/ci.yml`). Do **not** run the instrumented suite on a provisioned phone (it uninstalls).

---

## Family list hygiene

- [x] `ThemeBroadcaster.FAMILY_PACKAGES` includes contacts, files, email,
  keyboard, and `com.piercingxx.xxkeyboard.debug`. Add only real packages.
- [ ] After xx-email theme align: verify email actually restyles **on device**.

---

## Do not start unless reopened

- Shortcut-in-folder launches the shortcut (L-1)
- Backup format v2
- Notification-drawer reflection rewrite (L-6)

---

## Stop conditions

- Analytics → reject.
- Requiring same signing key on send → reject (breaks mixed debug keys).
- Running instrumented tests on the daily driver → reject.
- Inventing a new home metaphor → reject.

- [ ] SMOKE — emulator/device still open. Code fix landed: MAIN/LAUNCHER on
  `MainActivity` so the app is startable before it is set as HOME (HOME
  remains the product filter). Prior fail was "no MAIN/LAUNCHER activity".
  - files: app/src/main/AndroidManifest.xml
  - verify: python3 /home/piercingxx/.skippy/app/scripts/android_smoke.py . 2>&1 | tail -1 | grep -q 'SMOKE PASS'

---

## WAVE-1 — xx-apps catalog (operator 2026-09-17)

Package `com.piercingxx.xxlauncher`. Default-on. Theme **sender** for the
family. No fabric login. xx-apps may uninstall this APK when the Skippy
user is disabled.

- [x] L-E1 — Package id frozen for the store seed. Keep broadcasting
      `THEME_CHANGED` to the seeded catalog packages.

## BACKUP wave (operator lock 2026-09-20)

Contract: `xx-apps/docs/SUITE-BACKUP-PROVIDER.md`; server
`skippy-tel-network/docs/SUITE-BACKUP.md`. No release of this app ships
without its provider once the `suite-backup` library is on the estate
Maven.

- [x] BK-1 — Ship `SuiteBackupProvider` at `${applicationId}.suite.backup` guarded by `com.piercingxx.suite.permission.BACKUP` plus the in-code signature check. Snapshot contains its existing JSON backup as `export/layout.json`, `prefs/`, the folder DB. Restore applies atomically then exits the process.
  - files: app/src/main/AndroidManifest.xml, app/src/main/java/**/backup/SuiteBackupProvider.kt
  - verify: unit test round-trips snapshot → restore on an in-memory store; xx-apps Back up now lists this app with a size
