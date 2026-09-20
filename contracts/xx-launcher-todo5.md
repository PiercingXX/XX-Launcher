# xx-launcher-todo5

Operator mill contract materialized from dispatch. Do not invent a different exam.

## Goal

SMOKE — the app fails its emulator smoke run. Locked STACK=android. Land the named leftover files — a leftover [x] without those files is still open. Do not tick leftover from T1–T7 exam placeholders (first-frame stitch, CameraX JPEG-only bind). Do not collapse into T1 src/package.json. Do not reuse the parent mill contract. Do not mill cleaned/audiobookshelf-app-cleanroom. Further leftover: L-E1 — Package id frozen for the store seed. Keep broadcasting.

### T1 — SMOKE — the app fails its emulator smoke run

- verify: python3 /home/piercingxx/.skippy/app/scripts/android_smoke.py . 2>&1 | tail -1 | grep -q 'SMOKE PASS'

### T2 — L-E1 — Package id frozen for the store seed. Keep broadcasting

- files: app/src/main/java/com/piercingxx/xxlauncher/LE1.kt, app/src/test/java/com/piercingxx/xxlauncher/LE1Test.kt
- note: verify was a test this box writes, which closes the box without wiring anything into the app (xx-camera, xx-apps, 2026-09-14). Appended an app-level acceptance the mill cannot satisfy by writing a test: production code outside LE1 must reference it.
- verify: ./gradlew :app:testDebugUnitTest --offline --tests LE1Test && grep -rn 'LE1' app/src/main --exclude=LE1.kt

## Final gate

- verify: ./gradlew testDebugUnitTest --offline
