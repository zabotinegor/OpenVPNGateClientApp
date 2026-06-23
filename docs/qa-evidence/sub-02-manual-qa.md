# SUB-02 Manual QA Evidence

**Story:** SUB-02 — Android SSE Client — Re-poll on Server Change Event
**Branch:** feature/MP-20260621-server-push-sse
**Date:** 2026-06-23
**Device:** Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)
**Run:** 1 (first run — all cases PASS)

## Gate Result

GATE: PASS
STEP: manualQa
ITERATION: 1
BLOCKING_COUNT: 0

## Test summary

| Case | Title | Result |
|------|-------|--------|
| MQ-SUB02-001 | App launches cleanly with SSE client wired | PASS |
| MQ-SUB02-002 | SSE client attempts connection on foreground | PASS |
| MQ-SUB02-003 | SSE client stops gracefully on background | PASS |
| MQ-SUB02-004 | Regression — VPN connect and server list still work | PASS |

**4/4 PASS — 0 FAIL — 0 BLOCKED**

## Key logcat evidence

### MQ-SUB02-001 and MQ-SUB02-002 (foreground + connection)
```
13:44:00.550  I  SseServerEventsClient: SSE client starting; url=https://openvpngateclientgate.azurewebsites.net/api/v1/servers/events
13:44:00.552  D  SseServerEventsClient: SSE connecting (attempt=0)
13:44:04.134  I  SseServerEventsClient: SSE connection opened (HTTP 200)
13:44:04.137  D  SseServerEventsClient: SSE event received: type='servers-changed' id='null'
13:44:04.140  I  SseServerEventsClient: servers-changed event received; triggering server re-fetch
13:44:08.322  D  SseServerEventsClient: SSE event received: type='servers-changed' id='null'
13:44:08.325  I  SseServerEventsClient: servers-changed event received; triggering server re-fetch
```

### MQ-SUB02-003 (background stop)
```
13:45:31.392  I  SseServerEventsClient: SSE client stopping
13:45:31.396  W  SseServerEventsClient: kotlinx.coroutines.JobCancellationException: Job was cancelled
13:45:31.398  D  SseServerEventsClient: SSE connection failure (HTTP 200): Socket closed
```

### MQ-SUB02-004 (VPN regression + foreground restart)
```
13:46:48.521  I  SseServerEventsClient: SSE client starting
13:46:51.862  I  SseServerEventsClient: SSE connection opened (HTTP 200)
13:49:09.736  D  ConnectionControlsView: Server set: Республика Литва, ip=87.247.127.209
13:54:35.793  D  OpenVpnService: Engine state (AIDL): level=LEVEL_CONNECTED state=CONNECTED
13:54:35.795  I  OpenVpnService: Engine level=LEVEL_CONNECTED detail=CONNECTED source=AIDL
```

## Build
- `assembleDebugApp` BUILD SUCCESSFUL (131 tasks, 41 s)
- APK installed: `adb install -r mobile-debug.apk` Success

## Defects
None.

## Acceptance criteria verification

| AC | Description | Verified |
|----|-------------|---------|
| Start on foreground | `SSE client starting` on ProcessLifecycleOwner.onStart | Yes |
| Connect to endpoint | HTTP 200 opened on `GET /api/v1/servers/events` | Yes |
| servers-changed → sync | `syncCountries(forceRefresh=true)` triggered on each event | Yes |
| Stop on background | `SSE client stopping` on ProcessLifecycleOwner.onStop | Yes |
| Backoff | `SSE reconnect in Xms` logged on connection failures (verified in unit tests + splash transition stop/restart) | Yes |
| No UI errors | Zero FATAL EXCEPTION throughout session | Yes |
| VPN regression | LEVEL_CONNECTED achieved; server list loaded 2 servers | Yes |

## Suite link
tests/manual-e2e/stories/sub-02-android-sse-client/suites/SUB02-CORE.md
