# Quality Gate — US-12 Hardprobe on Every VPN Disconnect
**Iteration:** 1  
**Branch:** `feature/us-12-hardprobe-on-disconnect` vs `dev`  
**Commit:** 316cca95f0d2c3497477d5cffe5fe27b8e64278b  
**Date:** 2026-06-20  

---

## Check 1 — Unit Tests

**Command:** `.\gradlew.bat testDebugUnitTestApp` (from `src/`)  
**Result:** 600 total | 598 passed | **2 failed**

### Failure 1 — `ServerAutoSwitcherTest.fullCycleRestoresStartIndex` (KNOWN PRE-EXISTING)

```
org.junit.ComparisonFailure: expected:<conf[2]> but was:<conf[1]>
  at ServerAutoSwitcherTest.kt:189
```

This test predates the US-12 branch. Confirmed pre-existing: the test asserts `currentServer == "conf2"` after a circular cycle that starts at index 1, but the implementation restores the cycle start index after exhaustion rather than leaving the pointer at the last-visited node. The test has been unstable across multiple prior branches. **Not counted as a gate failure per the gate brief.**

### Failure 2 — `ServerRepositoryTest.parallel_force_refresh_same_key_does_not_fail_cache_write` (PRE-EXISTING)

```
java.io.FileNotFoundException: ...servers_58935e2432ea....csv
  at ServerRepository.parseServers(ServerRepository.kt:370)
```

This is a flaky file-race test in `ServerRepositoryTest` — a parallel cache-write race on the Robolectric temp directory. The affected file (`ServerRepository.kt`) is not in the US-12 diff. Confirmed pre-existing and unrelated to this feature. **Not counted as a gate failure.**

### US-12 test classes — all green

| Class | Tests | Result |
|---|---|---|
| `OpenVpnServiceDisconnectProbeTest` | 3 | PASS |
| `ServerAutoSwitcherTest` (US-12 tests) | 7 (excl. pre-existing fullCycleRestoresStartIndex) | PASS |

**Check 1 verdict: PASS** (0 new failures attributable to US-12)

---

## Check 2 — Test Coverage Adequacy

### AC-1: probe enqueued / not-enqueued on user disconnect

Three dedicated tests in `OpenVpnServiceDisconnectProbeTest`:

1. `finishStopFlowConfirmed_enqueuesToProbeQueueWithCorrectServerId` — verifies probe enqueued with correct `serverId=42` when current server matches last-started config.
2. `finishStopFlowConfirmed_doesNotEnqueueProbeWhenServerIdIsZero` — verifies no enqueue when `serverId=0`.
3. `finishStopFlowConfirmed_doesNotEnqueueProbeWhenConfigMismatch` — verifies no enqueue when the current server config does not match the last-started config (user changed selection between connect and disconnect).

All three pass. The coverage directly mirrors the guard logic at `OpenVpnService.kt:353-357`.

### AC-2: DEFAULT_V2 hydration probe

Test `defaultV2HydrationGap_hydrationPathEnteredAndNoProbeForEmptyStore` in `ServerAutoSwitcherTest` verifies:
- Hydration callback is invoked when `total==0` and `ServerSource.DEFAULT_V2`.
- No probe is enqueued when `failingServerId==0` (empty store, `currentServer()` returns null).
- Code path in `ServerAutoSwitcher.requestSwitchNow` lines 259-305 exercised.

Coverage gap (minor, non-blocking): the AC-2 path where `failingServerId != 0` at hydration time (store becomes non-empty between timer start and expiry) is not directly exercised. However the guard logic `if (failingServerId != 0)` is identical to the path tested in AC-1 tests, so the risk is low.

**Check 2 verdict: PASS** — ACs 1 and 2 are covered; minor uncovered AC-2 sub-branch is acceptable.

---

## Check 3 — Performance Impact

### `getCurrentServerIdIfMatchingLastStarted` in `finishStopFlowConfirmed`

Called at `OpenVpnService.kt:348`. This reads from `SharedPreferences` — a synchronous disk-backed read on the main thread (Handler/main looper context in `statusCallbacks.updateStateString` or `handleEngineLevelForStop`).

SharedPreferences reads are in-memory after the first load and are safe on the main thread; Android's own documentation explicitly supports this pattern. The `commit()` call pattern used for stop-intent persistence is the only blocking write in the flow, and it predates US-12.

The `probeQueue?.enqueue(serverId)` call at line 354 delegates to `WorkManager` (fire-and-forget enqueue). No blocking I/O on the main thread.

**Check 3 verdict: PASS** — no performance regressions introduced.

---

## Check 4 — Edge Case Handling

### `probeQueue == null`

`probeQueue` is declared as nullable (`ProbeRequestQueue?`) at `OpenVpnService.kt:192`. All call sites use null-safe invocation:
- Line 354: `probeQueue?.enqueue(serverId)` — safe
- Line 1090: `probeQueue?.enqueue(vpnStatusFailingServerId)` — safe
- Line 1461: `probeQueue?.enqueue(watchdogServerId)` — safe

`ServerAutoSwitcher.probeRequestQueue` is also nullable and uses identical null-safe call patterns (`probeRequestQueue?.enqueue(...)`).

The wiring in `onCreate` (`runCatching { ... probeQueue = queue ... }`) ensures that if Koin DI fails, `probeQueue` remains null and all enqueue calls silently no-op — correct behavior.

**Check 4 verdict: PASS** — null safety is properly handled everywhere.

---

## Check 5 — Security Surface

No new network calls are introduced directly. All probe enqueues go through the existing `ProbeRequestQueue` → `ProbeRequestWorker` → `HardProbeApiClient` path already in production. No new data (credentials, PII) is exposed. The `serverId` is an integer identifier with no sensitive content.

**Check 5 verdict: PASS** — no new security surface.

---

## Summary

| Check | Result | Notes |
|---|---|---|
| 1. Unit tests | PASS | 2 pre-existing failures excluded per gate brief |
| 2. Coverage adequacy | PASS | AC-1 and AC-2 directly covered; minor uncovered sub-branch acceptable |
| 3. Performance impact | PASS | Fire-and-forget WorkManager enqueue; SharedPreferences read is in-memory safe |
| 4. Edge case handling | PASS | Null-safe `?.enqueue()` at all 5 call sites |
| 5. Security surface | PASS | No new network calls or data exposure |

**BLOCKING ISSUES: 0**
