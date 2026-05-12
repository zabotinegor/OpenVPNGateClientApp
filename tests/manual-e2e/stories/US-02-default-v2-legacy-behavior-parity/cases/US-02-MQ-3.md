---
id: US-02-MQ-3
title: Foreground sync keeps DEFAULT_V2 selected-country store populated without user action
area: Android
surface: android
---

## Preconditions
- App in DISCONNECTED state with Australia selected (from MQ-2 cleanup)
- Network re-enabled

## Steps
1. Clear logcat: `adb logcat -c`
2. Send app to background: `adb shell input keyevent 3` (HOME)
3. Wait 6 seconds
4. Relaunch app: `adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
5. Wait 2 seconds for foreground sync to complete
6. Check UI: country and connect button state

## Assertions
- `server_selection_container` still shows Australia with IP 203.45.204.129
- `start_connection_button` is enabled and clickable
- Logcat shows `ServersV2SyncCoordinator: syncSelectedCountryServers: synced country=Australia servers=3`
- User did not need to open the country list manually

## Evidence Required
- Screenshot of main screen after foreground showing country + connect button
- Logcat excerpt showing `syncSelectedCountryServers: synced country=Australia servers=3`

## Cleanup
- None
