---
id: US-02-MQ-4
title: Forced server list refresh keeps DEFAULT_V2 store aligned and connect button active
area: Android
surface: android
---

## Preconditions
- App on main screen with Australia selected, DISCONNECTED state
- Network connected

## Steps
1. Clear logcat: `adb logcat -c`
2. Tap `server_selection_container` button to open server list screen
3. Tap "Обновить" (Refresh) button (resource-id `refresh_button`, bounds ~[593,436][984,568])
4. Wait 3 seconds for network fetch to complete
5. Navigate back to main screen: `adb shell input keyevent 4`
6. Wait 2 seconds for foreground sync on return

## Assertions
- Server list refresh triggers `ServerListViewModel: Loading servers. force_refresh=true`
- `ServersV2Repository: getCountries: fetching from network` confirming live fetch
- On return to main, `syncSelectedCountryServers: synced country=Australia servers=3` fires
- Main screen shows Australia, ip=203.45.204.129, server 1/3
- `start_connection_button` is enabled and clickable
- User did not need to manually re-select country

## Evidence Required
- Screenshot of server list with Обновить button visible
- Screenshot of server list after refresh (server counts may change from network data)
- Screenshot of main screen after returning showing Australia + connect button
- Logcat excerpt showing `force_refresh=true` → `fetching from network` → `syncSelectedCountryServers: synced`

## Cleanup
- None
