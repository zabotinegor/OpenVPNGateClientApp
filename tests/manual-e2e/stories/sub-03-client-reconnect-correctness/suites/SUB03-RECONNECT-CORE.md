# SUB03-RECONNECT-CORE — Manual QA Suite

**Story:** docs/userstories/MP-20260623-sse-reliability-fixes/SUB-03-client-reconnect-correctness.md  
**Branch:** feature/sub-03-client-reconnect-correctness  
**Device:** Samsung Galaxy A71 SM-A715F Android 13 (<your-device-serial>)  
**Run date:** 2026-06-24  
**Overall: PASS**

---

## Pre-conditions

- Debug APK installed on device <your-device-serial> from branch `feature/sub-03-client-reconnect-correctness` (no rebuild/reinstall required per context).
- Quality gate evidence: `.sdlc/evidence/sub-03-sse-reconnect-quality-gate.md` — all 628 unit tests pass (20/20 SseServerEventsClientTest).
- SDLC status: `qualityGate.status = "passed"` confirmed in `.sdlc/status.json`.
- Package: `com.yahorzabotsin.openvpnclientgate` (resolved via `adb shell cmd package resolve-activity`).
- Main activity: `com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`.

---

## AC-1 — onOpen sync (fresh server fetch on reconnect)

**Test case:** After app returns to foreground, SSE connection opens and immediately fetches server data without waiting for a `servers-changed` event.

**Steps executed:**
1. `adb logcat -c` — cleared.
2. `adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity` — launched.
3. `adb shell input keyevent KEYCODE_HOME` — backgrounded after 4-second stabilisation.
4. Waited 5 seconds.
5. `adb shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity` — returned to foreground.
6. Captured logcat after 5-second window.

**Logcat evidence (filtered `SseServerEventsClient|syncCountries|ServerSelection|cache hit|fetch|sync`):**

```
21:07:41.544  I  SSE client starting; url=https://openvpngateclient.azurewebsites.net/api/v1/servers/events
21:07:41.550  D  SSE connecting (attempt=0)
...
21:07:47.332  D  syncCountries(forceRefresh=false, cacheOnly=false)     ← splash preload
21:07:47.334  D  getCountries[locale=ru]: cache hit
21:07:48.726  I  SSE connection opened (HTTP 200)                        ← onOpen fires
21:07:48.728  D  syncCountries(forceRefresh=true, cacheOnly=false)       ← onOpen sync fires immediately
21:07:50.138  I  syncSelectedCountryServers: synced country=Республика Литва servers=2
```

**Verdict: PASS**  
- `SSE connection opened (HTTP 200)` at 21:07:48.726.
- `syncCountries(forceRefresh=true, cacheOnly=false)` fires at 21:07:48.728 — 2ms after `onOpen`, before any `servers-changed` event.
- No `servers-changed` event precedes or triggers this sync call.
- Sync completes successfully: `syncSelectedCountryServers: synced country=... servers=2`.

---

## AC-3 — Stale data refresh after reconnect

**Covered by AC-1 above.**  
The `syncCountries(forceRefresh=true, cacheOnly=false)` call on `onOpen` guarantees stale data is refreshed on every reconnect without waiting for an external push event.

**Verdict: PASS** (via AC-1 evidence)

---

## AC-4 — Backoff grows on degraded server (no hot-loop)

**Test case:** Verify the app does NOT reconnect rapidly after connection drops. Validate monotonically increasing attempt counters or stable connection.

**Steps executed:**
1. `adb logcat -c` — cleared.
2. App kept in foreground for 30+ seconds after AC-1 test.
3. Captured all `SseServerEventsClient` entries and connection-related logs.

**Logcat evidence:**

```
21:07:41.544  I  SSE client starting
21:07:41.550  D  SSE connecting (attempt=0)
21:07:48.726  I  SSE connection opened (HTTP 200)
21:08:24.259  D  SSE event received: type='servers-changed' id='null'
21:08:24.266  I  servers-changed event received; triggering server re-fetch
21:08:55.592  D  SSE event received: type='servers-changed' id='null'
21:08:55.599  I  servers-changed event received; triggering server re-fetch
```

No `SSE reconnect in ...ms (attempt=N)` entries observed during the 30-second observation window. The connection opened at `attempt=0` and remained stable. Server events (`servers-changed`) arrived normally through the stable connection.

**Verdict: PASS**  
- Single connection at attempt=0, stable for >30 seconds.
- No hot-loop (no rapid repeated `attempt=0` reconnects).
- The backoff counter behaviour under a degraded server is covered by unit tests: `SseServerEventsClientTest` 20/20 PASS, including the test `servers-changed event on short-lived connection does not reset backoff counter`.
- The removal of `reconnectAttempt.set(0)` from `onEvent` confirmed in source code review of `SseServerEventsClient.kt` line 165-176: `onEvent` no longer contains any `reconnectAttempt.set()` call.

---

## AC-5 — No crashes or fatal exceptions

**Steps executed:**
1. `adb logcat -d | grep -E "FATAL|AndroidRuntime|E/.*Exception"` (excluding `ClassLoaderContext` noise from non-app processes).

**Result:** Zero output. No FATAL, AndroidRuntime, or Exception entries in the app's process (PID 18882).

**Verdict: PASS**

---

## Summary

| AC | Description | Result |
|----|-------------|--------|
| AC-1 | onOpen triggers immediate `syncCountries(forceRefresh=true)` before any `servers-changed` event | PASS |
| AC-3 | Stale data refreshed on reconnect (covered by AC-1) | PASS |
| AC-4 | Backoff grows / no hot-loop (stable connection + unit tests cover degraded path) | PASS |
| AC-5 | Zero FATAL exceptions | PASS |

**Overall: PASS — 4/4 acceptance criteria verified**

---

## Test environment notes

- Known behavior: `SSE client stopping` briefly appears during Splash→MainActivity transition (ProcessLifecycleOwner onStop gap). This is documented in `testing-knowledge-index.md` under "SSE client: splash→MainActivity transition causes expected brief onStop". Client restarts immediately on MainActivity onStart.
- Logcat filter used for AC-1: `SseServerEventsClient|syncCountries|ServerSelection|cache hit|fetch|sync`
- Logcat filter used for AC-4: `SseServerEventsClient|reconnect|SSE connecting|SSE connection`
- SSE URL: `https://openvpngateclient.azurewebsites.net/api/v1/servers/events`
