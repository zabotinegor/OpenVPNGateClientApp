# MQ-SUB03-002: App launches to main screen without crash

## Case
Verify the app starts without crashing after install and transitions from SplashActivity to MainActivity.

## Steps
1. Wake device: `adb shell input keyevent 224 && adb shell input keyevent 82`
2. Launch: `adb -s R58N849XQEY shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
3. Wait 10 seconds
4. Check process alive: `adb -s R58N849XQEY shell pidof com.yahorzabotsin.openvpnclientgate`

## Expected
- am start returns no error
- `pidof` returns a non-empty PID after 10 seconds
- Logcat shows `MainActivityCore: onCreate called` and `ScreenFlow: enter MainActivity`

## Result: PASS
- am start: `Starting: Intent { cmp=com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity }` — no error
- PID: 18259 (non-empty, app alive)
- Logcat confirms at 17:42:11: `MainActivityCore: onCreate called` and `ScreenFlow: enter MainActivity`
- Executed: 2026-06-16 17:42 UTC+3
