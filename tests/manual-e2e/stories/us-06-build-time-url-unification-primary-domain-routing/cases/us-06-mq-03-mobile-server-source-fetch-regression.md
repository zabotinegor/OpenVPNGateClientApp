---
id: US-06-MQ-03
title: Mobile regression of server fetch across DEFAULT_V2, LEGACY, and VPNGATE
area: Android
surface: android
---

## Preconditions
- Connected ADB mobile target: e26d5c2f.
- App package installed: com.yahorzabotsin.openvpnclientgate.
- Network available.
- Device run-as access available for app shared prefs.

## Steps
1. For each source in `DEFAULT_V2`, `LEGACY`, `VPNGATE`, write `shared_prefs/user_settings.xml` with `server_source=<SOURCE>` via:
   - `"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>..." | adb shell run-as com.yahorzabotsin.openvpnclientgate tee /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/user_settings.xml`
2. Before each source run, clear source caches and logcat:
   - remove `cache/v2_*.json`, `cache/servers_*.csv`, `shared_prefs/servers_v2_cache.xml`, `shared_prefs/server_cache.xml`
   - `adb logcat -c`
3. Relaunch app from exported splash:
   - `adb shell am force-stop com.yahorzabotsin.openvpnclientgate`
   - `adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
4. Wait for startup sync, capture screenshot and UI XML.
5. Capture filtered logcat for source-specific repository markers.
6. Save `user_settings.xml` snapshot per source.

## Assertions
- `DEFAULT_V2`: logcat contains `ServersV2Repository: getCountries: fetching from network`.
- `LEGACY`: logcat contains `ServerRepository: Cache miss/stale. Fetching servers. Source=LEGACY` and successful fetch marker.
- `VPNGATE`: logcat contains `ServerRepository: Cache miss/stale. Fetching servers. Source=VPNGATE` and successful fetch marker.
- For each source, persisted `user_settings.xml` contains corresponding `server_source` value used for the run.

## Evidence Required
- manual-qa/2026-05-13-us06-server-source-regression/DEFAULT_V2-logcat-filtered.txt
- manual-qa/2026-05-13-us06-server-source-regression/DEFAULT_V2-screen.png
- manual-qa/2026-05-13-us06-server-source-regression/DEFAULT_V2-ui.xml
- manual-qa/2026-05-13-us06-server-source-regression/DEFAULT_V2-user_settings.xml
- manual-qa/2026-05-13-us06-server-source-regression/LEGACY-logcat-filtered.txt
- manual-qa/2026-05-13-us06-server-source-regression/LEGACY-screen.png
- manual-qa/2026-05-13-us06-server-source-regression/LEGACY-ui.xml
- manual-qa/2026-05-13-us06-server-source-regression/LEGACY-user_settings.xml
- manual-qa/2026-05-13-us06-server-source-regression/VPNGATE-logcat-filtered.txt
- manual-qa/2026-05-13-us06-server-source-regression/VPNGATE-screen.png
- manual-qa/2026-05-13-us06-server-source-regression/VPNGATE-ui.xml
- manual-qa/2026-05-13-us06-server-source-regression/VPNGATE-user_settings.xml

## Cleanup
- Keep app installed.
- Remove temporary device-side UI dumps under `/sdcard/*.xml` after pull.
