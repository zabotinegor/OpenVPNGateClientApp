# MQ-SUB02-001 — App launches cleanly with SSE client wired

## Status: PASS

## Device
Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)

## Steps executed
1. `adb shell am force-stop com.yahorzabotsin.openvpnclientgate`
2. `adb logcat -c`
3. `adb shell am start-activity -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
4. Wait 5 s
5. `adb logcat -d 2>&1 | grep SseServerEventsClient`

## Observed logcat (key lines)
```
13:44:00.550  I  OpenVPNGateApp:SseServerEventsClient: SSE client starting; url=https://openvpngateclient.azurewebsites.net/api/v1/servers/events
13:44:00.552  D  OpenVPNGateApp:SseServerEventsClient: SSE connecting (attempt=0)
```

## Assertions
- App launched without crash: PASS
- `SSE client starting` log present: PASS
- No `FATAL EXCEPTION` / `KoinException` / `NoBeanDefFoundException`: PASS

## Result: PASS
