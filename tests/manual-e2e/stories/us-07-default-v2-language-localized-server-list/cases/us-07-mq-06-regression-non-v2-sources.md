---
id: US-07-MQ-06
title: Regression spot-check LEGACY, VPNGATE, CUSTOM - verify no locale parameter, unchanged behavior
area: Android
surface: android
---

## Preconditions
- Connected ADB mobile target: Mi 9 SE (adb id e26d5c2f).
- App package installed: com.yahorzabotsin.openvpnclientgate.
- Network available.
- Device run-as access available for app shared prefs and cache.

## Steps
For each source in `LEGACY`, `VPNGATE`, `CUSTOM`:
1. Write `user_settings.xml` with the target source:
   ```bash
   adb shell run-as com.yahorzabotsin.openvpnclientgate tee /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/user_settings.xml << 'EOF'
   <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
   <map>
     <string name="language">ENGLISH</string>
     <string name="server_source"><SOURCE></string>
   </map>
   EOF
   ```
   where `<SOURCE>` is one of LEGACY, VPNGATE, or CUSTOM.

2. Clear legacy caches (not v2):
   ```bash
   adb shell run-as com.yahorzabotsin.openvpnclientgate rm -f /data/data/com.yahorzabotsin.openvpnclientgate/cache/servers_*.csv
   adb shell run-as com.yahorzabotsin.openvpnclientgate rm -f /data/data/com.yahorzabotsin.openvpnclientgate/shared_prefs/server_cache.xml
   ```
3. Clear logcat and launch:
   ```bash
   adb logcat -c
   adb shell am force-stop com.yahorzabotsin.openvpnclientgate
   adb shell am start -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
   ```
4. Wait ~20 seconds for sync.
5. Capture screenshot:
   ```bash
   adb exec-out screencap -p > manual-qa/<run-id>/us07-mq06-<source>-screen.png
   ```
6. Capture filtered logcat for source-specific markers (not v2 API calls):
   ```bash
   adb logcat -d | grep -E "ServerRepository|<SOURCE>" > manual-qa/<run-id>/us07-mq06-<source>-logcat.txt
   ```

## Assertions
1. **No locale parameter in requests:**
   - Logcat for LEGACY/VPNGATE/CUSTOM should NOT contain references to `locale=` or language query parameters.
   - Logcat should show source-specific repository fetch markers (e.g., "ServerRepository: Cache miss/stale. Fetching servers. Source=LEGACY").
2. **Behavior unchanged:**
   - Source-specific fetch succeeds.
   - Screenshot shows server list displayed correctly.
   - No errors or crashes related to language/locale handling for non-v2 sources.

## Evidence Required
- manual-qa/<run-id>/us07-mq06-LEGACY-screen.png
- manual-qa/<run-id>/us07-mq06-LEGACY-logcat.txt
- manual-qa/<run-id>/us07-mq06-VPNGATE-screen.png
- manual-qa/<run-id>/us07-mq06-VPNGATE-logcat.txt
- manual-qa/<run-id>/us07-mq06-CUSTOM-screen.png
- manual-qa/<run-id>/us07-mq06-CUSTOM-logcat.txt (if available/applicable)

## Cleanup
- Keep app installed.
- App data may be retained for later regression testing.
