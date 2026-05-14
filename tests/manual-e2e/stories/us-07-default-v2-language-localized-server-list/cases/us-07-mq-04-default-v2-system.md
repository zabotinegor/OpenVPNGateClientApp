---
id: US-07-MQ-04
title: DEFAULT_V2 + SYSTEM - verify system locale is used, fallback to en when blank
area: Android
surface: android
---

## Preconditions
- Connected ADB mobile target: Mi 9 SE (adb id e26d5c2f).
- App package installed: com.yahorzabotsin.openvpnclientgate.
- Network available.
- Device run-as access available for app shared prefs and cache.
- Device system locale is set to a supported language (e.g., Russian or Polish).

## Steps
1. Verify device system locale:
   ```bash
   adb shell getprop ro.product.locale
   ```
   Expected: non-empty locale string (e.g., "ru_RU", "pl_PL", "en_US", etc.).
2. Write `user_settings.xml` with `server_source=DEFAULT_V2` and `language=SYSTEM`:
   ```bash
   adb shell run-as com.yahorzabotsin.openvpnclientgate tee /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/user_settings.xml << 'EOF'
   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
   <map>
     <string name="language">SYSTEM</string>
     <string name="server_source">DEFAULT_V2</string>
   </map>
   EOF
   ```
3. Clear v2 caches:
   ```bash
   adb shell run-as com.yahorzabotsin.openvpnclientgate rm -f /data/data/com.yahorzabotsin.openvpnclientgate/cache/v2_*.json
   adb shell run-as com.yahorzabotsin.openvpnclientgate rm -f /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/servers_v2_cache.xml
   ```
4. Clear logcat and force-stop app:
   ```bash
   adb logcat -c
   adb shell am force-stop com.yahorzabotsin.openvpnclientgate
   ```
5. Launch app from exported splash:
   ```bash
   adb shell am start -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
   ```
6. Wait ~20 seconds for startup sync to complete.
7. Capture screenshot and UI XML:
   ```bash
   adb exec-out screencap -p > manual-qa/<run-id>/us07-mq04-system-screen.png
   adb shell uiautomator dump /sdcard/us07-mq04-system-ui.xml
   adb pull /sdcard/us07-mq04-system-ui.xml manual-qa/<run-id>/
   ```
8. Capture filtered logcat for v2 request markers:
   ```bash
   adb logcat -d | grep -E "ServersV2Repository|getCountries|getServers|locale" > manual-qa/<run-id>/us07-mq04-system-logcat.txt
   ```

## Assertions
1. **API request uses system locale language code:**
   - If system locale is supported (ru, pl, en), logcat should show corresponding locale in v2 request.
   - If system locale is blank or unsupported, implementation must fallback to `locale=en`.
2. **UI labels match system locale:**
   - Screenshot shows country/server list matching device system language if supported.
   - If unsupported or blank, labels should match English (fallback).

## Evidence Required
- manual-qa/<run-id>/us07-mq04-system-locale.txt (output of `getprop ro.product.locale`)
- manual-qa/<run-id>/us07-mq04-system-screen.png
- manual-qa/<run-id>/us07-mq04-system-ui.xml
- manual-qa/<run-id>/us07-mq04-system-logcat.txt
- manual-qa/<run-id>/us07-mq04-system-user-settings.xml (optional, for reference)

## Cleanup
- Keep app installed.
- Remove /sdcard/us07-mq04-system-ui.xml from device.
