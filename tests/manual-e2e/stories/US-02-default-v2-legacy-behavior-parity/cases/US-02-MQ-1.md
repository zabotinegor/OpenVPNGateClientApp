---
id: US-02-MQ-1
title: Fresh install — DEFAULT_V2 initial selection without manual country entry
area: Android
surface: android
---

## Preconditions
- Clean install of the app (no stored preferences)
- `ServerSource.DEFAULT_V2` active (default on current branch)
- ADB access to device
- Network connectivity available

## Steps
1. Uninstall and reinstall the debug APK fresh
2. Launch the app via SplashActivity
3. Observe main screen without any interaction

## Assertions
- Selected country is shown (non-empty) in `server_selection_container` without user opening country list
- Connect button (`start_connection_button`) is enabled and clickable
- `SelectedCountryStore` contains a non-empty `selected_country`, `selected_country_servers` (≥1 entry), and a valid current index
- Logcat shows `ServersV2SyncCoordinator` calls on splash load

## Evidence Required
- Screenshot of main screen showing country + connect button
- Screenshot of country list (verifying servers are present)
- Logcat excerpt showing `ServersV2SyncCoordinator: syncSelectedCountryServers`
- `vpn_selection_prefs.xml` contents showing `selected_country` and `selected_country_servers` populated

## Cleanup
- None — app remains installed for subsequent MQ cases
