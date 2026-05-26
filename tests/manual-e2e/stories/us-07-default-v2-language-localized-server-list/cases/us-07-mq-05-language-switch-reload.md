---
id: US-07-MQ-05
title: Switch language and reload DEFAULT_V2 - verify labels change without stale cache leakage
area: Android
surface: android
---

## Preconditions
- Connected ADB mobile target: Mi 9 SE (adb id e26d5c2f).
- App package installed: com.yahorzabotsin.openvpnclientgate.
- Network available.
- Device run-as access available for app shared prefs and cache.
- App has been run at least once with DEFAULT_V2 to populate cache.

## Steps
1. Write `user_settings.xml` with `server_source=DEFAULT_V2` and `language=ENGLISH`:
   ```bash
   adb shell run-as com.yahorzabotsin.openvpnclientgate tee /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/user_settings.xml << 'EOF'
   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
   <map>
     <string name="language">ENGLISH</string>
     <string name="server_source">DEFAULT_V2</string>
   </map>
   EOF
   ```
2. Clear v2 caches and logcat:
   ```bash
   adb shell run-as com.yahorzabotsin.openvpnclientgate rm -f /data/data/com.yahorzabotsin.openvpnclientgate/cache/v2_*.json
   adb shell run-as com.yahorzabotsin.openvpnclientgate rm -f /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/servers_v2_cache.xml
   adb logcat -c
   adb shell am force-stop com.yahorzabotsin.openvpnclientgate
   ```
3. Launch app with ENGLISH language:
   ```bash
   adb shell am start -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
   ```
4. Wait ~20 seconds for startup sync and app ready.
5. Capture screenshot with English labels:
   ```bash
   adb exec-out screencap -p > manual-qa/<run-id>/us07-mq05-english-initial-screen.png
   adb shell uiautomator dump /sdcard/us07-mq05-english-ui.xml
   adb pull /sdcard/us07-mq05-english-ui.xml manual-qa/<run-id>/us07-mq05-english-ui.xml
   ```
6. Now change language to RUSSIAN and restart the app:
   ```bash
   adb shell run-as com.yahorzabotsin.openvpnclientgate tee /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/user_settings.xml << 'EOF'
   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
   <map>
     <string name="language">RUSSIAN</string>
     <string name="server_source">DEFAULT_V2</string>
   </map>
   EOF
   ```
7. **Important:** Do NOT clear cache this time to ensure cache refresh/partition works correctly:
   ```bash
   adb logcat -c
   adb shell am force-stop com.yahorzabotsin.openvpnclientgate
   adb shell am start -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
   ```
8. Wait ~20 seconds for startup sync.
9. Capture screenshot with Russian labels:
   ```bash
   adb exec-out screencap -p > manual-qa/<run-id>/us07-mq05-russian-after-switch-screen.png
   adb shell uiautomator dump /sdcard/us07-mq05-russian-ui.xml
   adb pull /sdcard/us07-mq05-russian-ui.xml manual-qa/<run-id>/us07-mq05-russian-ui.xml
   ```
10. Capture filtered logcat from both runs:
    ```bash
    adb logcat -d | grep -E "ServersV2Repository|getCountries|locale|cache" > manual-qa/<run-id>/us07-mq05-logcat-after-switch.txt
    ```

## Assertions
1. **Language change is reflected in UI:**
   - English initial screenshot should show English country names.
   - Russian screenshot after language switch should show Russian country names (Cyrillic or appropriate language).
   - Labels should be different between the two screenshots, not the same (indicating language change took effect).
2. **No stale cache leakage:**
   - No mixed-language labels should appear in a single screenshot (e.g., some English, some Russian).
   - Logcat should show v2 fetch or cache hit with appropriate locale partition.
3. **Sorting/grouping remains stable:**
   - Both screenshots should show countries in the same order (alphabetical or by count).

## Evidence Required
- manual-qa/<run-id>/us07-mq05-english-initial-screen.png
- manual-qa/<run-id>/us07-mq05-english-ui.xml
- manual-qa/<run-id>/us07-mq05-russian-after-switch-screen.png
- manual-qa/<run-id>/us07-mq05-russian-ui.xml
- manual-qa/<run-id>/us07-mq05-logcat-after-switch.txt
- manual-qa/<run-id>/us07-mq05-english-user-settings.xml (optional)
- manual-qa/<run-id>/us07-mq05-russian-user-settings.xml (optional)

## Cleanup
- Keep app installed.
- Remove /sdcard/us07-mq05-*.xml files from device.
