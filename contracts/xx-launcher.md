# xx-launcher

Operator mill contract materialized from dispatch. Do not invent a different exam.

## Goal

Fix xx-launcher per root todo.md 'xx-launcher' and 'Family theme-sync (do this first)' sections (HEAD 1b6da08, package com.piercingxx.xxlauncher, version 0.7). In-repo todo items 1-10 are implemented; the remaining work is the family fan-out and docs. (1) P0: Add to ThemeBroadcaster.FAMILY_PACKAGES AND ThemeBroadcasterTest.family package list covers every receiver app exactly once (exact list + distinct): com.piercingxx.xxcontacts (only after contacts' permission string and BACKGROUND extra are fixed), com.piercingxx.xxfiles, dev.xxemail (after email grows a receiver), com.piercingxx.xxkeyboard (after keyboard grows a receiver; if debug builds keep applicationIdSuffix '.debug', fan out both ids or drop the suffix). Current FAMILY_PACKAGES (10): txxt, nopemode, calendar, xxclock, xxnote, xxdialer, vitals, xxdrive, xxcalculator, com.xx.weather. (2) P0: Broadcast on every theme change AND on launcher start (publish() already exists — keep it). Accept: changing a preset in Settings restyles every listed package without a reboot. (3) P1: Update MANUAL/README — the send path is sendBroadcast(intent) with per-package setPackage; receivers gate senders via android:permission; drop the 'family must share a signing key to receive' wording where it contradicts mixed-key Weather/Nope-Mode/Vitals. (4) P1: Appearance system → MODE_NIGHT_FOLLOW_SYSTEM can disagree with siblings that never read the OS clock — family rule is ground-from-preset, not follow-system; either drop follow-system or document that Paper+system-dark is a local launcher choice only. (5) P2: allowBackup=true on the launcher vs several siblings false — decide. Do NOT change the launcher to sendBroadcast(intent, PERMISSION_THEME_SYNC) — that would require every receiver to hold the permission and Weather/Nope-Mode/Vitals (different debug keys) would stop receiving; launcher todo.md item 8 and MANUAL claiming that send path are stale, do not restore them. Device-QA (ROLE_HOME, theme strip publish, clock widget opens xxclock, delete home slot) is NOT part of this contract — code-side work only.

### T1 — Implement the scoped goal

- files: app/src/main/AndroidManifest.xml
- verify: ./gradlew :app:testDebugUnitTest --offline

## Final gate

- verify: ./gradlew testDebugUnitTest --offline
