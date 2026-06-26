# Android ADB — Manual QA Runbook for OpenVPN Gate Client

## Device Setup

- ADB serial: `R58N849XQEY` (MIUI/Xiaomi device in secondary space)
- Package: `com.yahorzabotsin.openvpnclientgate`
- Launch activity: `.mobile.SplashActivity`

## Known Workarounds

### Package listing fails with SecurityException (user 150)
`adb shell pm list packages` fails with `Shell does not have permission to access user 150`.
Use instead:
```
adb -s R58N849XQEY shell dumpsys package com.yahorzabotsin.openvpnclientgate | grep -i "package\|version"
```

### Activity resolution
Use `cmd package resolve-activity` to find the launchable activity:
```
adb -s R58N849XQEY shell cmd package resolve-activity --brief com.yahorzabotsin.openvpnclientgate
```
Result: `.mobile.SplashActivity`

### Splash stalls (screen locked)
If `SplashActivity` doesn't transition to `MainActivity`, the device screen may be locked.
Fix:
```
adb -s R58N849XQEY shell input keyevent 224   # wake screen
```

### uiautomator dump — no PCRE grep on device
`grep -P` fails on the device shell. Use `grep -E` for extended regex.
```
adb -s R58N849XQEY shell "uiautomator dump /sdcard/ui.xml && cat /sdcard/ui.xml" | grep -E "pattern"
```

### `--tests` flag not supported on aggregate Gradle task
`./gradlew.bat testDebugUnitTestApp --tests "*.SomeTest"` fails.
Run the full suite: `./gradlew.bat testDebugUnitTestApp`

### VPN force-stop
```
adb -s R58N849XQEY shell am force-stop com.yahorzabotsin.openvpnclientgate
```

### App data wipe (fresh-install simulation without reinstall)
```
adb -s R58N849XQEY shell pm clear com.yahorzabotsin.openvpnclientgate
```

## Useful Log Filters

### Server selection + counter
```
adb -s R58N849XQEY logcat -d 2>&1 | grep "OpenVPNGateApp" | grep -E "(chosenIndex|ensureIndex|Session attempt|ConnectionControlsView|pendingUser|Server sel)"
```

### Full connect-flow trace
```
adb -s R58N849XQEY logcat -d 2>&1 | grep "OpenVPNGateApp" | grep -E "(CountryServersInteractor|MainViewModel|MainConnectionInteractor|SelectedCountryStore|OpenVpnService)" | grep -E "(chosenIndex|ensureIndex|Session attempt|Server sel|getLastSuccessful|saveLastStart|prepareStart)"
```

## Known Environmental Behaviour

### configData strings are dynamic (API returns different content per fetch)
The OpenVPN Gate server API returns config strings (`configData`) that change with each fetch
(likely include session nonces or timestamps). As a result:
- ViewModel state may hold a stale `configData` from the time of server selection
- Subsequent SSE syncs refresh the store with new `configData` strings
- `ensureIndexForConfig` config-match fails → falls back to IP-only → resets index to 0 (first server)
- This manifests as `matched by ip index=1/N` in logs even after user selects server N/N

This is a fundamental constraint for all Belarus/multi-server IP tests.

### Belarus has 3 servers, all sharing IP 213.184.224.127
All config-match tests must use Belarus because it is the only known country with multiple servers
sharing the same IP, which exercises the IP-vs-config disambiguation logic.

### SSE fires `servers-changed` on connection open and repeatedly during session
The SSE client fires `servers-changed` events causing the store to be refreshed every few seconds.
During any delay between server selection and Connect tap, the store will be updated multiple times.
