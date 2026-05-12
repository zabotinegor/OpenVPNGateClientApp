---
id: US-03-MQ-3
title: Offline startup with no countries cache
area: Android
surface: android
---

## Preconditions
- ADB target connected
- Network disabled

## Steps
1. Clear app data to remove cached countries.
2. Keep wifi and mobile data disabled.
3. Launch app from splash activity.
4. Capture screenshot and filtered logcat.

## Assertions
- No repetitive fatal startup crash loop.
- Failure path is graceful and observable through controlled error logs.
- Logs contain actionable context (`network failed and no cache available` or equivalent).

## Evidence Required
- Launch output (`am start -W`)
- Main screen/failure-state screenshot
- Filtered logcat showing controlled exception path and absence of fatal loop signal

## Cleanup
- Re-enable network after case completion
