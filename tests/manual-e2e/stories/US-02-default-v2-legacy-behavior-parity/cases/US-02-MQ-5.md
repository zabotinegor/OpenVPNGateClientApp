---
id: US-02-MQ-5
title: TV surface — DEFAULT_V2 parity initial selection and auto-switch
area: Android
surface: android
---

## Preconditions
- TV ADB target connected (`ro.build.characteristics` must contain `tv`)
- TV debug APK built from same commit as mobile
- ADB access to TV device

## Steps
1. Install TV APK: `adb install -r src/build/outputs/apk/tv/debug/tv-debug.apk`
2. Launch TV app
3. Observe initial screen — country must be pre-selected without user action
4. Disable network (wifi), observe auto-switch in TV UI or logcat
5. Re-enable network

## Assertions
- TV app on DEFAULT_V2 shows selected country with connect option without manual country entry
- Auto-switch cycles through servers within selected country without reopening country screen
- `syncSelectedCountryServers` called in logcat on foreground sync for TV surface

## Evidence Required
- Screenshot / video of TV main screen showing selected country
- Logcat of `syncSelectedCountryServers` for TV process

## Cleanup
- Uninstall TV test APK if installed on non-primary TV device

## Notes
- This case cannot be executed without a TV ADB target (`ro.build.characteristics=tv`).
- The Mi 9 SE mobile device used in the MQ-1—MQ-4 run reports `nosdcard` characteristics and cannot substitute for a TV.
