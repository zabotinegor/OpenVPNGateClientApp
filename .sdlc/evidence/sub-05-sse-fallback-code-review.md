# Code Review — SUB-05 SSE Fallback URL

**Branch:** `feature/sub-05-client-fallback-sse-url`  
**Diff scope:** `mp/sse-reliability-fixes..feature/sub-05-client-fallback-sse-url`  
**Commit reviewed:** `bc7275a`  
**Files reviewed:**
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClient.kt`
- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClientTest.kt`

---

## Pass 1 Findings

### AC Coverage

**AC-1 — Ordered URL list, switch on N consecutive failures**  
Satisfied. `sseUrls: List<String>` is ordered; `failuresOnCurrentUrl.incrementAndGet()` in `onFailure` gates the switch on `failures >= urlFailureThreshold`. Cycling uses `(currentUrlIndex.get() + 1) % urls.size`.

**AC-2 — Fallback URL from `FALLBACK_SERVERS_URL` via `sseServersEventsUrl()`**  
Satisfied. `defaultSseUrls()` calls `PrimaryDomainRoutes.sseServersEventsUrl(ApiConstants.PRIMARY_SERVERS_URL)` then `PrimaryDomainRoutes.sseServersEventsUrl(ApiConstants.FALLBACK_SERVERS_URL)`, which is exactly the same helper used by the REST client. `listOfNotNull` cleanly drops nulls.

**AC-3 — Returns to primary on next `start()` (stop() resets index)**  
Satisfied. `stop()` calls `currentUrlIndex.set(0)` and `failuresOnCurrentUrl.set(0)`. On the next `start()` the client begins at index 0 (primary). Note: there is no "priority-first reset" *mid-run*; URLs cycle round-robin indefinitely until `stop()`. The story says "round-robin or priority-first — implementation choice" so round-robin is acceptable.

**AC-4 — All URLs failing → continues retrying with existing exponential backoff**  
Satisfied. When the threshold is reached, `reconnectAttempt.set(0)` gives a fresh backoff sequence for the newly selected URL, but the outer `while (running.get())` loop continues. There is no break or permanent failure path.

**AC-5 — Unit test simulates primary URL failure and verifies fallback is tried**  
Satisfied by `primary URL failure switches to fallback after threshold` (line 792). Uses two `MockWebServer` instances; primary returns 503, fallback returns 200 with SSE stream, sync latch confirmed.

**AC-6 — WorkManager periodic refresh is untouched**  
Confirmed. `ServerRefreshWorker.kt` is not in the diff. `SseServerEventsClient.kt` still references the class only in its KDoc comment. The feature branch only touches the two declared files.

---

### Correctness Issues

**P1-C1: Non-atomic check-then-act on URL cycling (onFailure, OkHttp thread)**  
In `onFailure`:
```kotlin
val failures = failuresOnCurrentUrl.incrementAndGet()
if (failures >= urlFailureThreshold) {
    val urls = sseUrls
    val nextIndex = (currentUrlIndex.get() + 1) % urls.size
    currentUrlIndex.set(nextIndex)        // <-- set
    failuresOnCurrentUrl.set(0)
    reconnectAttempt.set(0)
    ...
}
```
`currentUrlIndex.get()` and `currentUrlIndex.set(nextIndex)` are not a single atomic compare-and-set. In the degenerate case where two OkHttp dispatcher threads simultaneously deliver `onFailure` for the same event source (OkHttp guarantees only one active `onFailure` per `EventSource`, so in practice this is safe), this would be a double-advance. However, `connectOnce` creates a single `Job` (`connectionDone`) and each `EventSource` instance is one-shot — OkHttp will not call `onFailure` twice on the same listener. **In practice the race cannot happen**, but the code lacks a comment explaining why and a careless future refactor could introduce the bug. **NON-BLOCKING — add a clarifying comment.**

**P1-C2: `urlFailureThreshold = 0` edge case**  
If `urlFailureThreshold` is constructed with `0` (possible via the test constructor), `failures >= 0` is always true on the very first `incrementAndGet()` (returns 1, which is >= 0). This means the client switches URL after every single failure, which may or may not be the desired behavior in tests but could be surprising. There is no guard in the production path because the default constant `URL_FAILURE_THRESHOLD = 3` is used. **NON-BLOCKING — the default is safe; consider a `require(urlFailureThreshold >= 1)` guard.**

**P1-C3: Single-URL list — correct behavior but potentially confusing log**  
When `sseUrls.size == 1`, `(index + 1) % 1 == 0`, so `currentUrlIndex` cycles back to 0, effectively staying on the same URL. `failuresOnCurrentUrl` resets to 0 and `reconnectAttempt` resets to 0 on every threshold — giving faster retries than the normal backoff would. The log says "switching to ${urls[nextIndex]}" but the URL hasn't changed. This is not a logic error, but the log message is misleading in the single-URL case. **NON-BLOCKING — log improvement.**

**P1-C4: `reconnectAttempt.set(0)` in `onFailure` interacts with `runReconnectLoop` timing**  
When the threshold is met, `reconnectAttempt` is reset to 0 inside the OkHttp callback. Meanwhile, the reconnect loop has already called `getAndIncrement()` at the top of the current iteration *before* `connectOnce` returned. So the sequence is:
1. Loop: `attempt = reconnectAttempt.getAndIncrement()` → attempt=N, counter becomes N+1
2. `connectOnce(url)` starts
3. `onFailure` fires → threshold hit → `reconnectAttempt.set(0)` resets the counter to 0
4. `connectOnce` returns
5. Loop: `attempt = reconnectAttempt.getAndIncrement()` → attempt=0, counter becomes 1
6. `attempt > 0` is false → **no backoff delay on the switch**

This is intentional (the KDoc says the switch gets a "fresh backoff sequence"), and the test `primary URL failure switches to fallback after threshold` validates that behavior. **No issue — confirmed by design and test coverage.**

**P1-C5: `connectOnce` builds a new `OkHttpClient` on every call**  
```kotlin
val sseOkHttpClient = okHttpClient.newBuilder()
    .readTimeout(0, TimeUnit.SECONDS)
    .build()
```
`OkHttpClient.newBuilder().build()` shares the underlying thread pool and connection pool with the parent; it does not create a full new client. This is the correct OkHttp pattern for SSE (no read timeout). **Not an issue.**

**P1-C6: `defaultSseUrls()` emergency fallback URL**  
```kotlin
.ifEmpty { listOf("https://openvpnclientgate.local/api/v1/servers/events") }
```
The fallback is a `.local` mDNS address that will never resolve in production. This path is only reachable if both `PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` produce null from `sseServersEventsUrl()`, which in turn requires malformed build properties. The build already fails at configuration time if these properties are absent (per CLAUDE.md). The `.local` address is non-routable and not a hardcoded production URL. **NON-BLOCKING — it's a defensive stub, not a security issue. A clarifying comment ("build guard makes this unreachable in practice") would help.**

---

### Logging

All logging uses `AppLog` (Timber wrapper). No `android.util.Log` calls found. URLs are logged at `AppLog.w` level in the switch message: `"switching to ${urls[nextIndex]}"`. URLs are derived from build properties (not secrets), consistent with the logging policy. Logging is appropriate.

---

### DI Compatibility

`CoreDi.kt` line 166: `single { SseServerEventsClient(get(), get()) }`. The new `sseUrlsProvider` parameter is the third parameter with a default value (`{ defaultSseUrls() }`), so the existing two-argument Koin call remains valid. **No DI change required.**

---

### Test Quality

**P1-T1: `primary URL failure switches to fallback after threshold` test (AC-5)**  
Uses `urlFailureThreshold = 1` for speed. Primary serves a 503; OkHttp SSE will call `onFailure` (not `onClosed`) for non-2xx responses. This is correct OkHttp SSE behavior. The test correctly asserts both the sync latch and `fallbackServer.takeRequest()`. **Sound.**

**P1-T2: `successful open on fallback resets failure count to zero`**  
Uses `fakeCoordinator` (unused) and a separate `fakeCoordinatorWithLatch` — the first coordinator object is instantiated but never assigned to the client. This is a dead variable (`fakeCoordinator` at line 866) that creates minor confusion. **NON-BLOCKING — cosmetic dead variable.**

**P1-T3: Test count**  
The test file contains 18 named `@Test` methods (counted from the file). The brief says 637 tests — this appears to be the total across the test suite, not just this file. All 5 new tests for SUB-05 are present: fallback switch, failure reset, stop reset, and two constant checks (`URL_FAILURE_THRESHOLD` and `urlFailureThreshold default`).

**P1-T4: `stop resets URL index and failure count to zero` (line 153)**  
Directly validates AC-3. Manually sets `currentUrlIndex` and `failuresOnCurrentUrl`, calls `stop()`, and asserts both are 0. Clean and correct.

**P1-T5: `Thread.sleep` usage in tests**  
Several tests use `Thread.sleep` (300 ms, 500 ms, 600 ms). These are integration tests with real network I/O and coroutines; countdown latches are used where possible. The sleeps are justified for "let async side effects settle" cases. No flakiness concerns beyond normal CI network latency — acceptable for Robolectric.

---

## Pass 2 Classification

| # | Finding | Severity | Blocking? |
|---|---------|----------|-----------|
| C1 | `onFailure` URL advance is two separate atomic ops without explanation. Safe in practice because OkHttp guarantees single-threaded callback delivery per EventSource, but no comment. | LOW | No |
| C2 | `urlFailureThreshold = 0` causes switch-on-every-failure with no guard. Default is safe; edge case reachable only via test constructor. | LOW | No |
| C3 | Single-URL list: log says "switching to X" when X is unchanged. | COSMETIC | No |
| C6 | `defaultSseUrls()` `.local` emergency fallback has no explanatory comment. | COSMETIC | No |
| T2 | Dead variable `fakeCoordinator` in `successful open on fallback resets failure count` test. | COSMETIC | No |

All AC requirements are fully met. No blocking findings.

---

## Verdict

```
GATE: PASS
BLOCKING_COUNT: 0
```
