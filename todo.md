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
- [ ] Change theme → family apps that are aligned restyle (calculator, weather, clock, files once F2 lands, email once aligned).
- [ ] Backup JSON → wipe launcher data → restore is a **replace**, including mutes.
- [ ] Recents / lock toasts: missing accessibility vs unsupported SDK are distinct.

**Accept:** dated notes. Then this is daily-driver home.

---

## Release

- [ ] Signing config for a non-debug APK (keystore gitignored).
- [ ] Decide minify: default **off** (MANUAL L-8) unless a size problem appears.
- [ ] CI: `./gradlew testDebugUnitTest` on push. Do **not** run the instrumented suite on a provisioned phone (it uninstalls).

---

## Family list hygiene

- [ ] `ThemeBroadcaster.FAMILY_PACKAGES` stays in sync as apps ship.
  Today includes keyboard, email, files — add only real packages.
- [ ] After xx-email theme align: verify email actually restyles.

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
