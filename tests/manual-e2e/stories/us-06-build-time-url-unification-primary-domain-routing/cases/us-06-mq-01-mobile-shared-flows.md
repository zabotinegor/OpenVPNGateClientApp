---
id: US-06-MQ-01
title: Mobile shared startup and metadata routing flow
area: Android
surface: android
---

## Preconditions
- Connected ADB mobile target.
- App package installed: com.yahorzabotsin.openvpnclientgate.
- Network available.

## Steps
1. Clear app state and relaunch splash activity.
2. Capture main screen screenshot after startup.
3. If update dialog appears, process it first (`ОТМЕНА` or `ЧТО НОВОГО` then back) and capture evidence.
4. Attempt to open AboutActivity for update/metadata checks.
5. Run connected mobile smoke instrumentation for MainActivitySmokeTest.
6. Collect androidTest artifacts and focused logcat.

## Assertions
- Startup must launch successfully on mobile.
- About/metadata actions should be reachable only through app flow (non-exported activity is expected for direct shell start).
- Update-dialog interruption is handled deterministically and does not block return to `MainActivity`.
- Instrumented smoke should complete to assert drawer/settings/about navigation.
- Log evidence should include repository-level events for server sync and update/release routing.

## Evidence Required
- manual-qa/2026-05-13-us06-build-time-url-unification/mobile-splash-main.png
- manual-qa/2026-05-13-us06-build-time-url-unification/mobile-about.png
- manual-qa/2026-05-13-us06-build-time-url-unification/mobile-logcat-key.txt
- src/mobile/build/outputs/androidTest-results/connected/debug/TEST-Mi 9 SE - 11-_mobile-.xml
- src/mobile/build/outputs/androidTest-results/connected/debug/Mi 9 SE - 11/testlog/test-results.log

## Cleanup
- Leave package installed.
- Stop hanging instrumentation session if it does not finish normally.
