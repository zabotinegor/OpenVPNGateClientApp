---
id: US-07-MQ-03
title: DEFAULT_V2 + POLISH - verify locale=pl in v2 requests and Polish labels in UI
area: Android
surface: android
---

## Preconditions
- Connected ADB mobile target: Mi 9 SE (adb id e26d5c2f).
- App package installed: com.yahorzabotsin.openvpnclientgate.
- Network available.
- Device run-as access available for app shared prefs and cache.

## Steps
1. Write `user_settings.xml` with `server_source=DEFAULT_V2` and `language=POLISH`:
   ```bash
   adb shell run-as com.yahorzabotsin.openvpnclientgate tee /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/user_settings.xml << 'EOF'
   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
   <map>
     <string name="language">POLISH</string>
     <string name="server_source">DEFAULT_V2</string>
   </map>
   EOF
   ```
2. Clear v2 caches:
   ```bash
   adb shell run-as com.yahorzabotsin.openvpnclientgate rm -f /data/data/com.yahorzabotsin.openvpnclientgate/cache/v2_*.json
   adb shell run-as com.yahorzabotsin.openvpnclientgate rm -f /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/servers_v2_cache.xml
   ```
3. Clear logcat and force-stop app:
   ```bash
   adb logcat -c
   adb shell am force-stop com.yahorzabotsin.openvpnclientgate
   ```
4. Launch app from exported splash:
   ```bash
   adb shell am start -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
   ```
5. Wait ~20 seconds for startup sync to complete.
6. Capture screenshot and UI XML:
   ```bash
   adb exec-out screencap -p > manual-qa/<run-id>/us07-mq03-polish-screen.png
   adb shell uiautomator dump /sdcard/us07-mq03-polish-ui.xml
   adb pull /sdcard/us07-mq03-polish-ui.xml manual-qa/<run-id>/
   ```
7. Capture filtered logcat for v2 request markers:
   ```bash
   adb logcat -d | grep -E "ServersV2Repository|getCountries|getServers|locale" > manual-qa/<run-id>/us07-mq03-polish-logcat.txt
   ```

## Assertions
1. **API request includes locale=pl:**
   - Logcat contains marker indicating v2 API call.
   - Logcat or request inspection confirms query includes `locale=pl`.
2. **UI labels are in Polish (if backend supports):**
   - Screenshot shows country/server list with Polish-language labels.
   - If backend returns English fallback for pl, document observed behavior.
3. **Cache is locale-aware:**
   - Verify no mixing of previous language and Polish labels in the list.

## Evidence Required
- manual-qa/<run-id>/us07-mq03-polish-screen.png
- manual-qa/<run-id>/us07-mq03-polish-ui.xml
- manual-qa/<run-id>/us07-mq03-polish-logcat.txt
- manual-qa/<run-id>/us07-mq03-polish-user-settings.xml (optional, for reference)

## Cleanup
- Keep app installed.
- Remove /sdcard/us07-mq03-polish-ui.xml from device.
