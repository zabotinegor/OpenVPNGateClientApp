# Quality Gate — SUB-05 SSE Fallback URL

**Branch:** `feature/sub-05-client-fallback-sse-url`  
**Commit:** `4868174` (latest after bot-review iterations)  
**Date:** 2026-06-25  
**Iterations:** 2 (initial) + multiple bot-review fix passes

---

## Test Results

Command: `.\gradlew.bat testDebugUnitTestApp --rerun-tasks` (from `src/`)

| Metric | Value |
|--------|-------|
| Total tests | 637+ |
| Passed | All |
| Failed | 0 |
| Errors | 0 |
| Build | SUCCESS |

**Result: ALL PASS**

---

## AC Coverage Matrix

| AC | Description | Covered by | Status |
|----|-------------|------------|--------|
| AC-1 | Client accepts ordered URL list; switches after threshold failures | `primary URL failure switches to fallback after threshold` + `reconnectAttempt is not reset on URL rotation so backoff accumulates during outage` | PASS |
| AC-2 | URL derived from build property (PRIMARY only; FALLBACK_SERVERS_URL is CSV, not SSE-capable) | `defaultSseUrls()` uses only `ApiConstants.PRIMARY_SERVERS_URL` via `PrimaryDomainRoutes.sseServersEventsUrl()`; FALLBACK excluded per design | PASS |
| AC-3 | Returns to primary on next cycle (round-robin) | `currentUrlIndex % urls.size` wraps to 0 after full cycle; verified via code inspection | PASS |
| AC-4 | All URLs fail → continue backoff, reach MAX_BACKOFF_MS | `reconnectAttempt` is intentionally NOT reset on URL rotation; backoff accumulates across switches; `reconnectAttempt is not reset on URL rotation so backoff accumulates during outage` | PASS |
| AC-5 | Unit test for fallback URL switching | `primary URL failure switches to fallback after threshold` + `successful open resets failure count from non-zero to zero` | PASS |
| AC-6 | WorkManager periodic refresh untouched | `ServerRefreshWorker` not in diff; confirmed not modified | PASS |

All 6 ACs covered (AC-2 scope refined: only PRIMARY_SERVERS_URL produces a valid SSE URL in the default config).

---

## Key Design Decisions (post bot-review)

**`FALLBACK_SERVERS_URL` excluded from `defaultSseUrls()`:** This build property holds the VPN Gate CSV URL (e.g. `https://www.vpngate.net/api/iphone/`). Passing it through `PrimaryDomainRoutes.sseServersEventsUrl()` would produce a garbage path (`/api/iphone/api/v1/servers/events`). Only `PRIMARY_SERVERS_URL` is SSE-capable. The multi-URL rotation mechanism is exercised via unit tests with injected URLs and is available for future use.

**`reconnectAttempt` NOT reset on URL rotation:** Resetting on every URL switch prevented exponential backoff from accumulating to `MAX_BACKOFF_MS` (5 min) during a complete outage. The reset was removed in commit `c378d9b`; a regression test guards this invariant.

**`start()` / `stop()` fully synchronized:** Both methods use `synchronized(this)` to prevent the race where `stop()` cancels a scope newly created by a concurrent `start()`.

**`onFailure` double guard:** `connectionJob?.isActive` check prevents stale callbacks from cancelled coroutines; `synchronized(this) + running.get()` closes the TOCTOU race with `stop()`.

---

## Edge Case Analysis (updated)

**1. Single URL in list (production default)**
- `currentUrlIndex % 1 == 0` always stays at 0.
- After threshold failures the index "rotates" to 0 (no-op), `failuresOnCurrentUrl` resets to 0, `reconnectAttempt` keeps growing.
- Effective behavior: continues retrying the primary URL with exponential backoff up to 5 min. Correct — WorkManager periodic refresh is the safety net.

**2. `urlFailureThreshold` < 1**
- Blocked by `init { require(urlFailureThreshold >= 1) }`. Setting 0 throws `IllegalArgumentException` at construction time.

**3. Empty `sseUrls`**
- `defaultSseUrls()` has `ifEmpty { listOf("…local…") }` guard.
- `start()` adds `require(sseUrls.isNotEmpty())` to surface misconfiguration early.

**4. Concurrency in `onFailure`**
- OkHttp delivers callbacks serially per EventSource instance.
- `synchronized(this)` + `running.get()` guard closes any race with `stop()`.
- `connectionJob?.isActive` guard prevents stale callbacks from cancelled coroutines.

---

## Verdict

**GATE: PASS**  
**BLOCKING_COUNT: 0**

All 6 ACs covered. All tests pass. Multiple correctness improvements applied through bot-review iterations (backoff preservation, stop-cancel guard, synchronized start/stop, coroutineContext job guards). No regressions.
