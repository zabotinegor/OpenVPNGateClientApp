# MQ-SUB05-001: SSE client starts with 1 candidate URL (PRIMARY only)

**AC:** AC-1 (ordered URL list), AC-2 (URL derivation from build properties)

## Steps

1. Install debug APK
2. Launch app
3. Capture logcat: `adb logcat -d | grep SseServerEventsClient`

## Expected

- Log line: `SSE client starting; 1 candidate url(s)`
  (FALLBACK_SERVERS_URL is the VPN Gate CSV URL, not an SSE-capable backend, so only PRIMARY_SERVERS_URL
  is included in the default URL list. Multi-URL rotation is available via injected providers in tests.)
- Log line: `SSE connecting (attempt=0) url=<primaryUrl>`

## Result: PASS

Log at 10:41:00: `SSE client starting; 1 candidate url(s)`  
Log at 10:41:00: `SSE connecting (attempt=0) url=https://openvpngateclient.azurewebsites.net/api/v1/servers/events`
