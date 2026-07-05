# SUB04-CORE Manual QA Suite

**Story:** SUB-04 Client-Side SSE Event Debounce  
**Device:** Samsung Galaxy A71 SM-A715F Android 13 (<your-device-serial>)  
**APK:** mobile-debug.apk, branch feature/sub-04-client-event-debounce, commit c287234  
**Run date:** 2026-06-25

## Overall: PASS

## MQ-SUB04-001 — SSE connects and onOpen triggers immediate sync

| Step | Expected | Actual | Result |
|------|----------|--------|--------|
| Launch app | SplashActivity starts, server preload runs | SplashActivityCore: Starting server preload | PASS |
| SSE connects | `SSE connection opened (HTTP 200)` | 01:37:51.161 SSE connection opened (HTTP 200) | PASS |
| onOpen sync | `syncCountries(forceRefresh=true)` immediately | 01:37:51.171 syncCountries(forceRefresh=true, cacheOnly=false) — 10ms after onOpen | PASS |

## MQ-SUB04-002 — Foreground/background lifecycle

| Step | Expected | Actual | Result |
|------|----------|--------|--------|
| Press HOME | SSE client stops | 01:37:24.909 SSE client stopping | PASS |
| SSE connection closed | Socket closed on background | 01:37:24.914 SSE connection failure (HTTP 200): Socket closed | PASS |
| Return to foreground | SSE client restarts | 01:37:27.670 SSE client starting; SSE connecting (attempt=0) | PASS |
| Reconnect | SSE connection opens again | 01:37:51.161 SSE connection opened (HTTP 200) | PASS |

## MQ-SUB04-003 — servers-changed event triggers debounced sync

| Step | Expected | Actual | Result |
|------|----------|--------|--------|
| servers-changed event #1 | Event logged + tryEmit | 01:37:52.382 SSE event received type=servers-changed; 01:37:52.387 triggering server re-fetch | PASS |
| Debounce fires ~500ms later | syncCountries(forceRefresh=true) once | 01:37:52.904 syncCountries(forceRefresh=true) — 517ms after event | PASS |
| servers-changed event #2 | Event logged + debounce fires | 01:38:04.876 event; 01:38:05.398 syncCountries — 517ms later | PASS |
| No multiple parallel syncs | Exactly 1 sync per event (non-burst) | Only 1 forceRefresh=true sync per event observed | PASS |

## MQ-SUB04-004 — Zero FATAL exceptions

| Check | Expected | Actual | Result |
|-------|----------|--------|--------|
| adb logcat FATAL EXCEPTION | None | No output | PASS |
| App stability | No crashes | App ran continuously for >10 minutes | PASS |

## Logcat evidence (key lines)

```
01:37:51.161 I SseServerEventsClient: SSE connection opened (HTTP 200)
01:37:51.171 D syncCountries(forceRefresh=true, cacheOnly=false)
01:37:52.382 D SseServerEventsClient: SSE event received: type='servers-changed' id='null'
01:37:52.387 I SseServerEventsClient: servers-changed event received; triggering server re-fetch
01:37:52.904 D syncCountries(forceRefresh=true, cacheOnly=false)
01:38:04.876 D SseServerEventsClient: SSE event received: type='servers-changed' id='null'
01:38:04.881 I SseServerEventsClient: servers-changed event received; triggering server re-fetch
01:38:05.398 D syncCountries(forceRefresh=true, cacheOnly=false)
```
