---
id: US-06-MQ-02
title: TV parity for startup, source switch, refresh, and metadata actions
area: Android
surface: android-tv
---

## Preconditions
- Leanback-capable Android TV target connected over ADB.
- TV APK from validated commit.

## Steps
1. Verify Leanback capability via `pm list features`.
2. Install and launch TV app.
3. Execute startup/source-switch/refresh checks.
4. Verify What's New/Get Update behavior parity.

## Assertions
- TV startup and shared source flows match mobile behavior.
- Metadata actions use primary host and are source-independent.

## Evidence Required
- Device capability output with leanback feature.
- TV launch output and screenshots.
- Filtered logcat for shared flow and metadata repositories.

## Cleanup
- Uninstall test TV app from non-primary target if installed.
