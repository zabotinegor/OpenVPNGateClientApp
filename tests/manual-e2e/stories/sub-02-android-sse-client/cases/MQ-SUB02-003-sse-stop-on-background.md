# MQ-SUB02-003 — SSE client stops gracefully on background

## Status: PASS

## Device
Samsung Galaxy A71 SM-A715F Android 13 (<your-device-serial>)

## Steps executed
1. App in foreground, SSE actively receiving events
2. `adb logcat -c`
3. `adb shell input keyevent KEYCODE_HOME`
4. Wait 3 s
5. `adb logcat -d | grep SseServerEventsClient`

## Observed logcat (key lines)
```
13:45:31.060  D  OpenVPNGateApp:SseServerEventsClient: SSE event received: type='servers-changed' id='null'
13:45:31.064  I  OpenVPNGateApp:SseServerEventsClient: servers-changed event received; triggering server re-fetch
13:45:31.392  I  OpenVPNGateApp:SseServerEventsClient: SSE client stopping
13:45:31.396  W  OpenVPNGateApp:SseServerEventsClient: Server re-fetch triggered by SSE event failed
13:45:31.396  W  OpenVPNGateApp:SseServerEventsClient: kotlinx.coroutines.JobCancellationException: Job was cancelled
13:45:31.398  D  OpenVPNGateApp:SseServerEventsClient: SSE connection failure (HTTP 200): Socket closed
```

## Notes
- `SSE client stopping` fires immediately on HOME key (ProcessLifecycleOwner.onStop)
- `JobCancellationException` in-flight is expected — events arrived at the same instant as stop; coroutines cancelled gracefully
- No FATAL EXCEPTION logged

## Assertions
- `SSE client stopping` in logcat: PASS
- No crash: PASS

## Result: PASS
