# SMOKE-01 — Cold launch, splash → main transition

**Result: PASS**

## Steps

```
adb install -r -g mobile-debug.apk
adb shell input keyevent 224 && input keyevent 82
adb shell am start -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity
```

## Evidence

- `am start` result: `Status: ok  LaunchState: COLD  TotalTime: 1178`
- After unlock: `topResumedActivity = .mobile.MainActivity (task 153)`
- Logcat: `SplashActivityCore: Starting server preload. vpn_connected=false, cache_only=false`
- No `FATAL EXCEPTION` in logcat

## Notes

Second cold launch (after force-stop) with device locked produced `LaunchState: UNKNOWN (0)` and `exit MainActivity` log — caused by missing screen unlock before `am start`. Unlock + recheck confirmed MainActivity as topResumedActivity.
