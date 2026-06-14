# MQ-SUB01-001 — App launches without crash

## Preconditions
- APK built from `feature/sub-01-serverv2-id-and-ping` HEAD installed on device
- ADB connected to Samsung Galaxy A71 SM_A715F Android 13 (R58N849XQEY)

## Steps
1. `adb install -r mobile-debug.apk`
2. `adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
3. Wait ~6 seconds for SplashActivity → MainActivity transition
4. `adb logcat -d --pid=<app_pid>` and check for FATAL/AndroidRuntime/JsonSyntaxException

## Expected
- App starts without crash
- MainActivity visible with country selection and connect button
- No FATAL or AndroidRuntime in app-scoped logcat

## Result: PASS
- APK install: Success
- App PID: 20642
- SplashActivity launched and transitioned to MainActivity
- Logcat: zero FATAL/AndroidRuntime/JsonSyntaxException entries
- Screenshot shows MainActivity in disconnected state with "Республика Литва" (Lithuania) server selected

## Evidence
- logcat: `ViewRootImpl@....[MainActivity]` — relayout confirmed
- logcat: `OpenVPNGateApp:CoreApp: Skipping OpenVpnService auto-start in Application` — clean start
- Screenshot: `qa-sub01-ui.png` (Status: Отключено, Server: 2/2, City: Каунас (+03:00 UTC))

## Run date
2026-06-14
