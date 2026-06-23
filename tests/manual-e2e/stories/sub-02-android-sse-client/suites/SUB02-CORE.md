# SUB02-CORE — Android SSE Client Core Suite

## Overall: PASS

## Run 2 (PR #105 pre-merge retest)
- Run date: 2026-06-23
- Device: Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)
- APK: mobile-debug.apk (built from feature/MP-20260621-server-push-sse HEAD, 131 tasks BUILD SUCCESSFUL)
- Unit tests: SseServerEventsClientTest 18/18 PASS (`:core:testDebugUnitTest`)

## Results

| Case | Title | Result |
|------|-------|--------|
| MQ-SUB02-001 | App launches cleanly with SSE client wired | PASS |
| MQ-SUB02-002 | SSE client attempts connection on foreground | PASS |
| MQ-SUB02-003 | SSE client stops gracefully on background | PASS |
| MQ-SUB02-004 | Regression — VPN connect and server list still work | PASS |

**4/4 PASS — 0 FAIL — 0 BLOCKED**

## Key evidence (Run 2)

### CoreApp init + SSE observer registration
```
17:27:52.402  I  OpenVPNGateApp:CoreApp(11261): Periodic server refresh scheduling ensured
17:27:52.448  I  OpenVPNGateApp:CoreApp(11261): SSE lifecycle observer registered
```

### MQ-SUB02-001 and MQ-SUB02-002 (foreground + connection)
```
17:27:52.655  I  OpenVPNGateApp:SseServerEventsClient(11261): SSE client starting; url=https://openvpngateclient.azurewebsites.net/api/v1/servers/events
17:27:52.657  D  OpenVPNGateApp:SseServerEventsClient(11261): SSE connecting (attempt=0)
17:27:55.600  I  OpenVPNGateApp:SseServerEventsClient(11261): SSE connection opened (HTTP 200)
17:27:55.603  D  OpenVPNGateApp:SseServerEventsClient(11261): SSE event received: type='servers-changed' id='null'
17:27:55.605  I  OpenVPNGateApp:SseServerEventsClient(11261): servers-changed event received; triggering server re-fetch
17:28:05.933  D  OpenVPNGateApp:SseServerEventsClient(11261): SSE event received: type='servers-changed' id='null'
17:28:05.940  I  OpenVPNGateApp:SseServerEventsClient(11261): servers-changed event received; triggering server re-fetch
17:28:08.083  D  OpenVPNGateApp:SseServerEventsClient(11261): SSE event received: type='servers-changed' id='null'
17:28:08.089  I  OpenVPNGateApp:SseServerEventsClient(11261): servers-changed event received; triggering server re-fetch
```

### MQ-SUB02-003 (background stop — HOME key)
```
17:29:15.131  I  OpenVPNGateApp:SseServerEventsClient(11261): SSE client stopping
17:29:15.142  D  OpenVPNGateApp:SseServerEventsClient(11261): SSE connection failure (HTTP 200): Socket closed
```

### MQ-SUB02-003b (foreground return — SSE reconnects)
```
17:29:37.297  I  OpenVPNGateApp:SseServerEventsClient(11261): SSE client starting; url=https://openvpngateclient.azurewebsites.net/api/v1/servers/events
17:29:37.299  D  OpenVPNGateApp:SseServerEventsClient(11261): SSE connecting (attempt=0)
17:29:51.508  I  OpenVPNGateApp:SseServerEventsClient(11261): SSE connection opened (HTTP 200)
17:29:51.511  D  OpenVPNGateApp:SseServerEventsClient(11261): SSE event received: type='servers-changed' id='null'
17:29:51.513  I  OpenVPNGateApp:SseServerEventsClient(11261): servers-changed event received; triggering server re-fetch
```

### MQ-SUB02-004 (WorkManager periodic refresh — unaffected)
```
JobScheduler: JOB #u0a803/3 com.yahorzabotsin.openvpnclientgate/androidx.work.impl.background.systemjob.SystemJobService
  Minimum latency: +13m16s760ms
  Backoff: policy=1 initial=+30m0s0ms
  Last run: START -11m12s ago
```

### No fatal exceptions
```
FATAL EXCEPTION count: 0
KoinException: 0
NoBeanDefFoundException: 0
```

## Notes

- During splash→MainActivity transition a brief `SSE client stopping` fires due to
  `ProcessLifecycleOwner` detecting the intermediate window-stop. This is expected:
  the client restarts immediately when MainActivity resumes (`SSE client starting` fires again).
  The backoff counter is reset (attempt=0) on every new `start()` call.
- `JobCancellationException` on background is expected in-flight cancel; not a crash.
- WorkManager `SystemJobService` confirmed active via `dumpsys jobscheduler` — periodic
  refresh runs every 20 min independently of SSE client.

---

## Run 1 (initial QA run)

- Run date: 2026-06-23
- Device: Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)
- APK: mobile-debug.apk (built from feature/MP-20260621-server-push-sse HEAD)
- Build: assembleDebugApp BUILD SUCCESSFUL (131 tasks)

| Case | Title | Result |
|------|-------|--------|
| MQ-SUB02-001 | App launches cleanly with SSE client wired | PASS |
| MQ-SUB02-002 | SSE client attempts connection on foreground | PASS |
| MQ-SUB02-003 | SSE client stops gracefully on background | PASS |
| MQ-SUB02-004 | Regression — VPN connect and server list still work | PASS |

**4/4 PASS — 0 FAIL — 0 BLOCKED**

### Run 1 key evidence
- `SSE client starting` logged on every foreground entry
- `SSE connection opened (HTTP 200)` — backend endpoint live and reachable
- Multiple `servers-changed` events received and each triggered `syncCountries(forceRefresh=true)`
- `SSE client stopping` logged immediately on HOME key (ProcessLifecycleOwner onStop)
- `LEVEL_CONNECTED` on VPN connect to Республика Литва (ip=87.247.127.209)
- Server list loaded: `fetchAllPages[LT]: fetched 2 servers`
- Zero `FATAL EXCEPTION`, `KoinException`, `NoBeanDefFoundException` throughout
