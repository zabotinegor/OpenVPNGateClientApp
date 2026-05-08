---
id: US-03-MQ-2
title: Offline startup with existing countries cache
area: Android
surface: android
---

## Preconditions
- App already launched online at least once on DEFAULT_V2 to populate countries cache
- ADB target connected

## Steps
1. Disable wifi and mobile data.
2. Force-stop app.
3. Launch app from splash activity.
4. Capture screenshot and filtered logcat.

## Assertions
- App remains usable without fatal crash.
- Countries/selection remain available from cache path.
- Logs contain cache usage (`cache hit` or stale cache fallback) rather than fatal parsing startup crash.

## Evidence Required
- Launch output (`am start -W`)
- Main screen screenshot under offline condition
- Filtered logcat lines demonstrating cache path and no fatal exception

## Cleanup
- Keep offline mode for MQ-3 start, or restore network if sequence is split
