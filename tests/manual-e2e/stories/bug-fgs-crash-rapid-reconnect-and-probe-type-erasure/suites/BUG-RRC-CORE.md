# BUG-RRC-CORE — FGS Rapid-Reconnect Crash & ProbeApi Type Erasure — Core Suite

## Story
`docs/userstories/BUG-fgs-crash-rapid-reconnect-and-probe-type-erasure.md`

## Branch
`fix/fgs-crash-rapid-reconnect`

## Run date
2026-06-25

## Device
Samsung Galaxy A71 SM-A715F, Android 13, ADB serial R58N849XQEY

## Build
Debug APK, commit 9dd3004 (HEAD on fix/fgs-crash-rapid-reconnect)

## Unit tests
638/638 PASS (testDebugUnitTestApp, 9m 53s, BUILD SUCCESSFUL)

## Cases

| ID | Title | Result |
|----|-------|--------|
| MQ-BUG-RRC-001 | Rapid reconnect: no crash on 3rd connect attempt | PASS |
| MQ-BUG-RRC-002 | Normal VPN connect (regression) | PASS |
| MQ-BUG-RRC-003 | Disconnect and reconnect stability (regression) | PASS |
| MQ-BUG-RRC-004 | Probe enqueued and succeeds on VPN disconnect | PASS |

## Overall: PASS

## Key evidence summary
- `LEVEL_NOTCONNECTED` delivered via AIDL at 21:44:10.705; `syncEngineState()` did NOT call
  `exitControllerForeground()` — fix working; service destroyed cleanly at 21:44:11.723 with no crash
- Zero `RemoteServiceException` across entire session
- VPN connected normally: `LEVEL_CONNECTED` at 22:04:00.925, watchdog `trafficDelta=1992`
- Disconnect clean: `CONNECTED → DISCONNECTING → DISCONNECTED → Service destroyed` at 22:05:25
- Reconnect succeeded: `ACTION_START` at 22:06:52.230, `LEVEL_CONNECTED` at 22:06:55.891 (3.661 s)
- Probe enqueued immediately on disconnect at 22:05:25.292, `status=202` at 22:05:26.623
- `WorkerWrapper: Worker result SUCCESS` — R8 type-erasure fix confirmed (no `IllegalArgumentException`)
