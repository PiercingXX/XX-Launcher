# xx-launcher-todo3

Operator mill contract materialized from dispatch. Do not invent a different exam.

## Goal

Continue the millable product todo.md until the app is complete. Do not collapse into T1 src/package.json. Do not reuse the parent mill contract. Next boxes: Signing config for a non-debug APK (keystore gitignored).; After xx-email theme align: verify email actually restyles **on device**..

### T1 — Signing config for a non-debug APK (keystore gitignored).

- files: src/stores/signing-config-for-a-non-debug-apk-keyst.ts, src/tests/app/signing-config-for-a-non-debug-apk-keyst.test.ts
- verify: cd src && corepack npm@10 test tests/app/signing-config-for-a-non-debug-apk-keyst.test.ts

### T2 — After xx-email theme align: verify email actually restyles on device.

- files: src/pages/after-xx-email-theme-align-verify-email-.vue, src/tests/app/after-xx-email-theme-align-verify-email-.test.ts
- verify: cd src && corepack npm@10 test tests/app/after-xx-email-theme-align-verify-email-.test.ts

## Final gate

- verify: ./gradlew testDebugUnitTest --offline
