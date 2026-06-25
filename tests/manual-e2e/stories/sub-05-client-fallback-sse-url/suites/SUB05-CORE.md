# SUB05-CORE: Client Fallback SSE URL — Core Suite

**Story:** SUB-05-client-fallback-sse-url  
**Device:** Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)  
**Build:** bc7275a (feature/sub-05-client-fallback-sse-url)  
**Date:** 2026-06-25

## Test cases

| ID | Description | Result |
|----|-------------|--------|
| MQ-SUB05-001 | SSE client starts with 2 candidate URLs | PASS |
| MQ-SUB05-002 | Primary URL attempted first on foreground | PASS |
| MQ-SUB05-003 | WorkManager periodic refresh remains active | PASS |
| MQ-SUB05-004 | Zero FATAL exceptions after cold launch | PASS |

## Evidence

- Logcat: `SSE client starting; 2 candidate url(s)` at 10:41:00 (PID 32220)
- Logcat: `SSE connecting (attempt=0) url=https://openvpngateclient.azurewebsites.net/api/v1/servers/events`
- dumpsys jobscheduler: `com.yahorzabotsin.openvpnclientgate/SystemJobService` RUNNABLE
- Zero FATAL exceptions in logcat

## Notes

- URL fallback switching (AC-1, AC-4) and return-to-primary (AC-3) are covered by unit tests (5/5 PASS).
  Real-device simulation of primary URL outage is not practical (would require blocking the primary host).
  Unit tests with MockWebServer provide deterministic coverage for these scenarios.
- AC-5 (unit test for fallback) fully verified in test suite: `primary URL failure switches to fallback after threshold` PASS.

## Overall: PASS
