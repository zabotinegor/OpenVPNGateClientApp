---
id: US-03-MQ-4
title: TV launcher sanity parity on Leanback target
area: Android
surface: android-tv
---

## Preconditions
- Leanback-capable ADB target connected (`android.software.leanback=true`)
- TV APK built from same commit under validation

## Steps
1. Verify target exposes TV/Leanback feature.
2. Install TV APK and launch TV splash activity.
3. Observe startup and country readiness behavior.
4. Capture screenshot/video and filtered logcat.

## Assertions
- TV launcher starts without deserialization-related startup crash.
- Country sync path behaves consistently with mobile expectations.
- Logs include `ServersV2Repository`/`ServersV2SyncCoordinator` entries and no abstract-type instantiation crash.

## Evidence Required
- Device capability output (`pm list features`/`getprop`)
- TV install/launch output
- TV screenshot and filtered logcat

## Cleanup
- Uninstall TV test APK from non-primary test target if required

## Notes
- If no Leanback target is connected, mark MQ-4 as BLOCKED with explicit capability evidence.
