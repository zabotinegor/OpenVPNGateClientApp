---
id: US-02-MQ-2
title: Auto-switch circular rotation for DEFAULT_V2 without manual country re-entry
area: Android
surface: android
---

## Preconditions
- App installed with fresh DEFAULT_V2 store (Australia selected, 3 servers in store)
- ADB access to device
- Network can be disabled via `adb shell svc wifi disable; adb shell svc data disable`

## Steps
1. Confirm main screen shows Australia with connect button active
2. Clear logcat: `adb logcat -c`
3. Tap connect button to start VPN connection attempt
4. Disable both wifi and data: `adb shell svc wifi disable; adb shell svc data disable`
5. Observe automatic switch behaviour in logcat (no user action)
6. Wait for engine to report `LEVEL_NONETWORK` and trigger first switch
7. Observe second switch on second NONETWORK event
8. Observe full-cycle-complete log after all servers exhausted

## Assertions
- First NONETWORK triggers `Immediate switch at level=LEVEL_NONETWORK: Australia -> (serversInCountry=3, server=2/3, ...)`
- Second NONETWORK triggers switch to server 3/3
- Third NONETWORK triggers `completed full server cycle for Australia (serversInCountry=3)` — engine stops
- At no point does the app open or require the country list screen
- Country stays Australia throughout; switches are driven from pre-populated `SelectedCountryStore`

## Evidence Required
- Logcat excerpt showing the three `Immediate switch` log lines and `completed full server cycle`
- Screenshot of main screen before connect showing Australia, 203.45.204.129
- Screenshot confirming DISCONNECTED state after full cycle

## Cleanup
- Re-enable network: `adb shell svc wifi enable; adb shell svc data enable`
