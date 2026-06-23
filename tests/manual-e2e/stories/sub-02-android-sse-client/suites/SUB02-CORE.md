# SUB02-CORE — Android SSE Client Core Suite

## Overall: PASS

## Run date: 2026-06-23
## Device: Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)
## APK: mobile-debug.apk (built from feature/MP-20260621-server-push-sse HEAD)
## Build: assembleDebugApp BUILD SUCCESSFUL (131 tasks)

## Results

| Case | Title | Result |
|------|-------|--------|
| MQ-SUB02-001 | App launches cleanly with SSE client wired | PASS |
| MQ-SUB02-002 | SSE client attempts connection on foreground | PASS |
| MQ-SUB02-003 | SSE client stops gracefully on background | PASS |
| MQ-SUB02-004 | Regression — VPN connect and server list still work | PASS |

**4/4 PASS — 0 FAIL — 0 BLOCKED**

## Key evidence

- `SSE client starting` logged on every foreground entry
- `SSE connection opened (HTTP 200)` — backend endpoint live and reachable
- Multiple `servers-changed` events received and each triggered `syncCountries(forceRefresh=true)`
- `SSE client stopping` logged immediately on HOME key (ProcessLifecycleOwner onStop)
- `LEVEL_CONNECTED` on VPN connect to Республика Литва (ip=87.247.127.209)
- Server list loaded: `fetchAllPages[LT]: fetched 2 servers`
- Zero `FATAL EXCEPTION`, `KoinException`, `NoBeanDefFoundException` throughout

## Notes

- During splash→MainActivity transition, a brief `SSE client stopping` fires due to
  `ProcessLifecycleOwner` detecting the intermediate window-stop. This is expected:
  the client restarts immediately when MainActivity resumes (`SSE client starting` fires again).
  The backoff is reset on reconnect (attempt=0 on next start).
- `JobCancellationException` on background is expected in-flight cancel; not a crash.
