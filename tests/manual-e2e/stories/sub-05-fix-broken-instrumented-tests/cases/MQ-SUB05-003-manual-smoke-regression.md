# MQ-SUB05-003 — Manual smoke: app launch and navigation regression

## Preconditions
- APK installed on both devices (R58N849XQEY and b6e8f6bd)
- ADB connected to both devices

## Steps (per device)
1. Force-stop and clear logcat:
   ```
   adb -s <device> shell am force-stop com.yahorzabotsin.openvpnclientgate
   adb -s <device> logcat -c
   ```
2. Launch app via SplashActivity:
   ```
   adb -s <device> shell am start -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
   ```
3. Wait ~6 seconds for splash → main transition
4. Verify MainActivity is resumed:
   ```
   adb -s <device> shell dumpsys activity activities | grep -i "com.yahorzabotsin.openvpnclientgate/.mobile.MainActivity"
   ```
5. Check for fatal exceptions:
   ```
   adb -s <device> logcat -d | grep -E "(FATAL EXCEPTION|AndroidRuntime|NoActivityResumedException)"
   ```
6. Capture screenshot evidence
7. Open navigation drawer and verify menu items visible
8. Navigate to Settings and back
9. Navigate to About and back

## Expected
- App launches without crash on both devices
- MainActivity visible and interactive
- Navigation drawer opens with all menu items
- Settings and About screens load without error
- Zero FATAL EXCEPTION or NoActivityResumedException in logcat

## Evidence Required
- Screenshots of main screen on each device
- Logcat output (filtered for exceptions)
- dumpsys activity output showing resumed MainActivity

## Cleanup
- None required
