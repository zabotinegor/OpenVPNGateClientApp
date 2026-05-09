---
id: US-03-MQ-1
title: Clean startup on DEFAULT_V2 in debug and release/minified
area: Android
surface: android
---

## Preconditions
- ADB target connected
- Network connectivity available
- Debug and release artifacts built from current commit
- App data can be cleared before launch

## Steps
1. Build debug artifacts (`assembleDebugApp`).
2. Build release-like/minified artifacts (`assembleReleaseApp`).
3. Clear app data, install debug APK, launch app from splash activity.
4. Verify main screen readiness and absence of fatal crash.
5. Attempt release APK install and launch on same device.

## Assertions
- Debug launch reaches main flow without startup crash.
- Logs show `ServersV2Repository`/`ServersV2SyncCoordinator` country sync activity.
- No abstract-type instantiation crash appears in logcat.
- Release path behaves identically when installable; otherwise blocker is captured with explicit install error.

## Evidence Required
- Build output snippets for debug and release tasks
- Launch output (`am start -W`) for debug and release (if installable)
- Screenshot of app state after debug launch
- Logcat filtered by `ServersV2Repository|ServersV2SyncCoordinator|DefaultMainSelectionInteractor`
- Release install failure output if blocked

## Cleanup
- Keep app installed for MQ-2 and MQ-3 unless release install attempt requires uninstall/reinstall sequence
