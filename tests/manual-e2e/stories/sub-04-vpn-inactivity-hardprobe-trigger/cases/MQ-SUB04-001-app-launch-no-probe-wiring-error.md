# MQ-SUB04-001 — App launches, no Koin / ProbeRequestQueue errors in logcat

## Preconditions
- APK built from `feature/sub-04-vpn-inactivity-hardprobe-trigger` HEAD installed on device
- ADB connected to Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)
- `mobile-debug.apk` already installed (verified in this session)

## Steps
1. Launch app:
   ```
   adb -s R58N849XQEY shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
   ```
2. Wait ~6 seconds for SplashActivity → MainActivity transition
3. Capture app PID:
   ```
   adb -s R58N849XQEY shell pidof com.yahorzabotsin.openvpnclientgate
   ```
4. Check for Koin and probe-wiring errors:
   ```
   adb -s R58N849XQEY logcat -d | findstr /i "NoBeanDefFound\|KoinException\|ProbeRequestQueue\|HardProbeApiClient\|Failed to wire"
   ```
5. Also run the broader fatal-exception check:
   ```
   adb -s R58N849XQEY logcat -d --pid=<PID> | findstr /i "FATAL\|AndroidRuntime\|Exception\|crash"
   ```

## Expected
- App starts without crash; MainActivity visible with country selection and connect button
- Zero `NoBeanDefFoundException`, `KoinException`, or `Failed to wire ProbeRequestQueue` lines
- Zero `FATAL EXCEPTION` or `AndroidRuntime` lines in app-scoped logcat

## Result: PASS
- Verified in this session (2026-06-16)
- Logcat was clean: no Koin errors, no NoBeanDefFoundException, no ProbeRequestQueue wiring
  failures, no FATAL EXCEPTION lines
- App reached MainActivity successfully

## Run date
2026-06-16
