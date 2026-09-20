# xx-launcher-todo2

Operator mill contract materialized from dispatch. Do not invent a different exam.

## Goal

Per root todo.md 'xx-launcher (other than theme fan-out)' + family theme-sync sender section: (1) P0 add to ThemeBroadcaster.FAMILY_PACKAGES AND ThemeBroadcasterTest family package list (exact list + distinct): com.piercingxx.xxcontacts (ONLY after contacts' permission string and BACKGROUND extra fixed), com.piercingxx.xxfiles, dev.xxemail (after email grows a receiver), com.piercingxx.xxkeyboard (after keyboard grows a receiver; if debug keeps applicationIdSuffix .debug, fan out both ids or drop suffix); (2) P0 broadcast on every theme change AND on launcher start (publish() already exists); (3) P1 update MANUAL/README: send path is sendBroadcast(intent) with per-package setPackage; receivers gate via android:permission; drop 'family must share signing key to receive' wording; (4) P1 appearance system → MODE_NIGHT_FOLLOW_SYSTEM can disagree with siblings — family rule is ground-from-preset; drop follow-system or document Paper+system-dark is local choice only; (5) P2 allowBackup=true vs siblings false — decide. Device QA (ROLE_HOME, theme strip publish) NOT part of this contract — code-side only.

### T1 — Implement the scoped goal

- files: app/src/main/java/com/piercingxx/xxlauncher/theme/ThemeBroadcaster.kt, app/src/test/java/com/piercingxx/xxlauncher/ThemeBroadcasterTest.kt, app/src/main/AndroidManifest.xml
- verify: ./gradlew :app:testDebugUnitTest --offline

## Final gate

- verify: ./gradlew testDebugUnitTest --offline
