# MQ-SUB02-004 — Regression: VPN connect and server list still work

## Status: PASS

## Device
Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)

## Steps executed
1. Bring app to foreground: `adb shell am start-activity -W -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`
2. Verified SSE client restarts: `SSE client starting` → `SSE connecting (attempt=0)` → `SSE connection opened (HTTP 200)`
3. Verified server list populates: `ConnectionControlsView: Server set: Республика Литва, ip=87.247.127.209`
4. Tapped VPN connect button (bounds [53,2076][1027,2183])
5. Captured logcat for `LEVEL_CONNECTED`
6. Disconnected VPN

## Observed logcat (key lines)
```
13:46:48.521  I  SseServerEventsClient: SSE client starting
13:46:48.540  D  SseServerEventsClient: SSE connecting (attempt=0)
13:46:51.862  I  SseServerEventsClient: SSE connection opened (HTTP 200)
13:46:54.620  D  SseServerEventsClient: SSE event received: type='servers-changed' id='null'
13:46:54.624  I  SseServerEventsClient: servers-changed event received; triggering server re-fetch

13:49:09.638  D  ServersV2Repository: getServersForCountry[LT]: serverCount=2 locale=ru
13:49:09.702  D  ServersV2Repository: fetchAllPages[LT]: fetched 2 servers (raw=2)
13:49:09.736  D  ConnectionControlsView: Server set: Республика Литва, ip=87.247.127.209

13:54:29.726  I  OpenVpnService: Service created
13:54:29.740  I  OpenVpnService: Session attempt 1 (serversInCountry=2, server=2/2, ip=87.247.127.209): Республика Литва
13:54:35.793  D  OpenVpnService: Engine state (AIDL): level=LEVEL_CONNECTED state=CONNECTED
13:54:35.795  I  OpenVpnService: Engine level=LEVEL_CONNECTED detail=CONNECTED source=AIDL
```

## Assertions
- Server list displays (no crash, no empty list): PASS — `fetchAllPages[LT]: fetched 2 servers`
- VPN connects with `LEVEL_CONNECTED` in logcat: PASS
- `SSE client starting` on foreground return: PASS
- Zero `FATAL EXCEPTION`: PASS

## Result: PASS
