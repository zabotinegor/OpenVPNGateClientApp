---
id: TV-DRAWER-FALSE-CLICK-CORE
title: Android TV drawer false-click prevention acceptance suite
surface: android-tv
includes:
  - TV-DRAWER-FALSE-CLICK-001
---

## Objective
Verify that opening/closing the TV drawer never allows accidental connection control actions from the main screen.

## Environment (Validated)
- Real Android TV device over ADB TCP (192.168.1.95:5555, model MIBOX4, Android 9).
- Fresh debug install recommended before acceptance run.

## Scope
- Drawer opening transition.
- Drawer opened state.
- Drawer closing transition.
- Main action control interactivity restore after drawer close.
- Existing drawer navigation behavior remains functional while background controls are blocked.

## Out of Scope
- VPN tunnel quality/performance.
- Notification actions.
- Non-TV launcher behavior.

## Execution Order
1. TV-DRAWER-FALSE-CLICK-001

## Recommended Execution Command (Windows)
```powershell
Set-Location .\src
$env:ANDROID_SERIAL = "192.168.1.95:5555"
.\gradlew :tv:connectedDebugAndroidTest
```

## Exit Criteria
- All included cases pass.
- No false connect/stop/pause action while drawer is opening/open/closing.
- Drawer navigation still works normally while the main screen remains guarded.
