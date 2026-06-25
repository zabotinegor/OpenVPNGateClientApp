# MQ-SUB05-001: SSE client starts with 2 candidate URLs

**AC:** AC-1 (ordered URL list), AC-2 (FALLBACK_SERVERS_URL parity)

## Steps

1. Install debug APK
2. Launch app
3. Capture logcat: `adb logcat -d | grep SseServerEventsClient`

## Expected

- Log line: `SSE client starting; 2 candidate url(s)`
- Log line: `SSE connecting (attempt=0) url=<primaryUrl>`

## Result: PASS

Log at 10:41:00: `SSE client starting; 2 candidate url(s)`  
Log at 10:41:00: `SSE connecting (attempt=0) url=https://openvpngateclient.azurewebsites.net/api/v1/servers/events`
