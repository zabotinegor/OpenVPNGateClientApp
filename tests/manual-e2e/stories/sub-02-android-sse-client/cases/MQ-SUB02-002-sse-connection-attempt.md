# MQ-SUB02-002 — SSE client attempts connection on foreground

## Status: PASS

## Device
Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)

## Steps executed
1. App in foreground (from MQ-SUB02-001)
2. `adb logcat -d -t 500 | grep SseServerEventsClient`

## Observed logcat (key lines)
```
13:44:00.550  I  OpenVPNGateApp:SseServerEventsClient: SSE client starting; url=https://...
13:44:00.552  D  OpenVPNGateApp:SseServerEventsClient: SSE connecting (attempt=0)
13:44:04.134  I  OpenVPNGateApp:SseServerEventsClient: SSE connection opened (HTTP 200)
13:44:04.137  D  OpenVPNGateApp:SseServerEventsClient: SSE event received: type='servers-changed' id='null'
13:44:04.140  I  OpenVPNGateApp:SseServerEventsClient: servers-changed event received; triggering server re-fetch
13:44:08.322  D  OpenVPNGateApp:SseServerEventsClient: SSE event received: type='servers-changed' id='null'
13:44:08.325  I  OpenVPNGateApp:SseServerEventsClient: servers-changed event received; triggering server re-fetch
```

## Notes
- Backend IS available at `openvpngateclientgate.azurewebsites.net`
- HTTP 200 connection opened; multiple `servers-changed` events received
- `ServerSelectionSyncCoordinator.sync()` triggered on each event (`syncCountries(forceRefresh=true)` confirmed in ServersV2SyncCoordinator logs)

## Assertions
- SSE connection attempt present: PASS
- `SSE connection opened (HTTP 200)` present: PASS (backend available)
- `servers-changed` event triggers re-fetch: PASS
- No FATAL exception: PASS

## Result: PASS
