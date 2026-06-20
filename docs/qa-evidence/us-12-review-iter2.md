# Code Review — US-12: Hardprobe on Every VPN Disconnect
**Branch:** `feature/us-12-hardprobe-on-disconnect`
**Base:** `main`
**Commit:** `6d281b3580ba91ad3d6de2186023a27f7da6c712`
**Reviewer:** Claude Code
**Date:** 2026-06-20
**Iteration:** 2 (second and final pass)

---

## Scope

| File | Role |
|------|------|
| `src/core/…/vpn/OpenVpnService.kt` | AC-1: probe enqueue in `finishStopFlowConfirmed` |
| `src/core/…/vpn/ServerAutoSwitcher.kt` | AC-2: probe enqueue in DEFAULT_V2 hydration path |
| `src/core/…/vpn/OpenVpnServiceDisconnectProbeTest.kt` | New unit tests (AC-1) |
| `src/core/…/vpn/ServerAutoSwitcherTest.kt` | Updated unit tests (AC-2 + existing) |
| `src/docs/server-sync-flow.md` | Documentation of new trigger points |
| `docs/runbooks/how-to.md` | Developer knowledge runbook entry |

---

## Pass 1 — AC-1 and AC-2 Implementation Correctness

### AC-1: Probe placement and ordering in `finishStopFlowConfirmed`

**Result: PASS**

Diff at `OpenVpnService.kt` lines 345–357 confirms:
```kotlin
ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)   // fires first
val serverId = try {
    SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)
} catch (e: Exception) {
    AppLog.w(TAG, "Failed to resolve serverId for disconnect probe", e)
    0
}
if (serverId != 0) {
    try { probeQueue?.enqueue(serverId) } catch (e: Exception) {
        AppLog.w(TAG, "Failed to enqueue hardprobe on user disconnect", e)
    }
}
```

- Probe fires **after** `DISCONNECTED` state is set — correct per AC-1.
- Exception from `getCurrentServerIdIfMatchingLastStarted` silently falls back to `0`, suppressing the probe — correct.
- `serverId == 0` guard prevents spurious probe — correct.
- Error handling follows the identical `try { … } catch (e: Exception) { AppLog.w(…) }` pattern used at lines 1090 and 1464.

### AC-1: `@Volatile` field and `onDestroy` nulling

**Result: PASS**

- `@Volatile private var probeQueue: ProbeRequestQueue? = null` at line 193 — declared `@Volatile` and nullable, consistent with the existing `probeRequestQueue` field in `ServerAutoSwitcher.kt` (line 37).
- `onDestroy` at line 1002 sets `probeQueue = null` and `ServerAutoSwitcher.probeRequestQueue = null` in the same block — both references cleaned up.

### AC-1: Thread safety of `getCurrentServerIdIfMatchingLastStarted`

**Result: PASS**

`finishStopFlowConfirmed` is invoked from `handleEngineLevelForStop`, which is called from:
- `updateState(…)` — annotated `@MainThread`
- `syncEngineState(…)` — dispatched through `statusHandler` (main looper)

Both paths remain on the main thread. `getCurrentServerIdIfMatchingLastStarted` reads SharedPreferences — synchronous but in-memory after first load. This is the same call site pattern used at lines 1086–1090 (VPN_STATUS probe) and line 1462 (watchdog probe). No new blocking I/O concern.

### AC-2: Probe placement in DEFAULT_V2 hydration path

**Result: PASS**

Diff at `ServerAutoSwitcher.kt` lines 263–270 confirms:
```kotlin
if (callback != null) {
    AppLog.i(TAG, "DEFAULT_V2: store empty at switch time, requesting on-demand hydration …")
    if (failingServerId != 0) {                           // new guard
        try { probeRequestQueue?.enqueue(failingServerId) } catch (e: Exception) { … }
    }
    v2HydrationPending = true                             // existing
    callback(appContext) { … }
    return
```

- Probe fires **before** `v2HydrationPending = true` and **before** `return` — correct.
- `failingServerId` is captured at lines 245–247 **before** `nextServerCircular` executes — correct.
- `LEVEL_NONETWORK` sets `failingServerId = 0` via the ternary at line 245 — probe suppressed correctly.
- `v2HydrationPending` guard at line 234–237 prevents re-entry while hydration is in progress — unaffected by the new probe call.

### AC-2: Line 243 comment character check

**Result: PASS — no backslash comment**

Line 242–244 in `ServerAutoSwitcher.kt`:
```kotlin
        // Capture the failing server id before nextServerCircular advances the index.
        // Guard: only probe if the currently selected server config matches the last-started config,
```
Comment correctly starts with `//`, not `\`. No malformed comment.

### AC-3: Existing probe call sites unchanged

**Result: PASS**

Five total probe call sites verified in production code:

| Location | Site | Status |
|----------|------|--------|
| `OpenVpnService.kt:1090` | VPN_STATUS auto-switch probe | Unchanged |
| `OpenVpnService.kt:1464` | Watchdog recovery probe | Unchanged |
| `OpenVpnService.kt:355` | AC-1 user-disconnect probe | **New (correct)** |
| `ServerAutoSwitcher.kt:267` | AC-2 DEFAULT_V2 hydration probe | **New (correct)** |
| `ServerAutoSwitcher.kt:313` | Auto-switch timeout/immediate probe | Unchanged |

All three pre-existing sites (lines 1090, 1464, 313) are intact. No regression.

---

## Pass 2 — Tests, Isolation, and Coverage

### Test file: `OpenVpnServiceDisconnectProbeTest`

**Result: PASS**

Three tests covering AC-1:

| Test | What it verifies | Result |
|------|-----------------|--------|
| `finishStopFlowConfirmed_enqueuesToProbeQueueWithCorrectServerId` | Probe enqueued with `serverId=42` when server at index 0 matches last-started | PASS |
| `finishStopFlowConfirmed_doesNotEnqueueProbeWhenServerIdIsZero` | No probe when all servers have `id=0` | PASS |
| `finishStopFlowConfirmed_doesNotEnqueueProbeWhenConfigMismatch` | No probe when last-started config does not match current server config | PASS |

Test setup correctness:
- `ReflectionHelpers.setField(service, "probeQueue", fakeQueue)` and `ReflectionHelpers.setField(service, "userInitiatedStop", true)` correctly inject state without modifying production visibility.
- `ReflectionHelpers.callInstanceMethod` with correct `ClassParameter` types invokes the private method.
- Config-mismatch test accurately explains the `ensureIndexForConfig` side-effect in the comment, making the intent clear.
- `@After tearDown` resets `ConnectionStateManager` to `DISCONNECTED` — no state leak.

**Minor gap (non-blocking):** No test for `probeQueue == null` (DI wiring failure). The null-safe `probeQueue?.enqueue(serverId)` already handles this, and the gap is consistent with existing null-queue test coverage for the other probe call sites.

### Test file: `ServerAutoSwitcherTest`

**Result: PASS**

Key test for AC-2: `defaultV2HydrationGap_hydrationPathEnteredAndNoProbeForEmptyStore`
- Configures `ServerSource.DEFAULT_V2`, empty server list, fake `ProbeRequestQueue`.
- Hydration callback records invocation without calling `onDone` (prevents side-effects in this test).
- Asserts hydration callback was invoked — confirms the path is entered.
- Asserts no probe enqueued — confirms `failingServerId == 0` guard works when store is empty.
- Cleans up `ServerSource` to `LEGACY` in test body.

**Coverage gap (acceptable, non-blocking):** The case where `failingServerId != 0` during DEFAULT_V2 hydration is logically impossible: `total == 0` (required to enter the hydration path) means `currentServer()` returns `null`, which means `getCurrentServerIdIfMatchingLastStarted` returns `0`. The positive probe enqueue path in the hydration block is thus structurally unreachable in production for this exact condition combination. The gap is a logical impossibility, not a missed test.

### Test isolation: `tearDown` calls `ServerAutoSwitcher.resetForTest()`

**Result: PASS**

`ServerAutoSwitcherTest.tearDown()` at line 65 calls `ServerAutoSwitcher.resetForTest()` which invokes `cancel(resetCycle = true)`. This resets `cycleStartIndex` to `null`, clearing the shared singleton state between tests.

### Test verification results (fresh run)

**Command:** `.\gradlew.bat :core:testDebugUnitTest --tests "com.yahorzabotsin.openvpnclientgate.vpn.OpenVpnServiceDisconnectProbeTest" --tests "com.yahorzabotsin.openvpnclientgate.vpn.ServerAutoSwitcherTest" --rerun-tasks`

**Result: 12/12 PASS, BUILD SUCCESSFUL**

```
OpenVpnServiceDisconnectProbeTest > finishStopFlowConfirmed_enqueuesToProbeQueueWithCorrectServerId PASSED
OpenVpnServiceDisconnectProbeTest > finishStopFlowConfirmed_doesNotEnqueueProbeWhenServerIdIsZero PASSED
OpenVpnServiceDisconnectProbeTest > finishStopFlowConfirmed_doesNotEnqueueProbeWhenConfigMismatch PASSED
ServerAutoSwitcherTest > defaultV2HydrationGap_hydrationPathEnteredAndNoProbeForEmptyStore PASSED
ServerAutoSwitcherTest > [all 8 existing tests] PASSED
Total: 12 | Passed: 12 | Failed: 0 | Skipped: 0
```

Note: `fullCycleRestoresStartIndex` also passed in this run (was flaky in prior evidence). The flaky `ServerRepositoryTest` test is not in scope and was not run.

---

## Risk Register

| ID | Risk | Impact | Confidence | Mitigation |
|----|------|--------|-----------|------------|
| R1 | Null `probeQueue` on DI failure (startup Koin exception) | Low | High | `probeQueue?.enqueue(serverId)` null-safe; wired in `runCatching` block in `onCreate` |
| R2 | `getCurrentServerIdIfMatchingLastStarted` exception path | Low | High | Outer try/catch falls back to `id=0`, probe suppressed |
| R3 | `LEVEL_NONETWORK` fires user-stop probe | None | High | `finishStopFlowConfirmed` flow only fires on explicit user stop; `LEVEL_NONETWORK` case not guarded at call site but `getCurrentServerIdIfMatchingLastStarted` will correctly return the actual server ID (not suppress). This is by design: a user-initiated stop after a NONETWORK event means the user actively disconnected — a probe is appropriate and correct |
| R4 | Double-probe for same server on rapid user-stop + auto-switch | None | High | WorkManager `KEEP` dedup prevents double-fire per PRD |
| R5 | Shared singleton state leakage between tests | Low | High | `resetForTest()` in `tearDown` resets all mutable state |

**Note on R3:** Unlike the auto-switch path (where `LEVEL_NONETWORK` forces `failingServerId=0` to suppress a probe because the device lost network), the user-disconnect path does NOT need this suppression. If the user presses Disconnect after a NONETWORK event, they are making an intentional choice — a probe for the server that was active is valid backend feedback. The story AC-1 and the implementation correctly make no NONETWORK exception in the user-stop path.

---

## Documentation Checks

### `src/docs/server-sync-flow.md`
- Sections 3 (User-initiated disconnect) and 4 (DEFAULT_V2 hydration early-return) added under "Hardprobe Trigger Points" — accurate and consistent with the code.
- All 5 probe trigger points are now documented.

### `docs/runbooks/how-to.md`
- New section "Hardprobe enqueue during VPN lifecycle" added with table of all 5 call sites, semantics of `serverId=0`, WorkManager KEEP dedup note, and references.
- Accurate and consistent with the implementation.

### Security / privacy
- No hardcoded URLs, credentials, or production constants in any changed file.
- No `android.util.Log` calls — all logging via `AppLog`.

---

## Checklist Summary

| Check | Result | Severity |
|-------|--------|----------|
| AC-1: probe fires after DISCONNECTED state | PASS | — |
| AC-1: `serverId == 0` guard suppresses probe | PASS | — |
| AC-1: exception from `getCurrentServerIdIfMatchingLastStarted` falls back to 0 | PASS | — |
| AC-2: probe before `v2HydrationPending = true` and `return` | PASS | — |
| AC-2: `failingServerId` captured before `nextServerCircular` | PASS | — |
| AC-2: `LEVEL_NONETWORK` sets `failingServerId = 0` | PASS | — |
| AC-3: watchdog recovery probe unchanged | PASS | — |
| AC-3: VPN_STATUS probe unchanged | PASS | — |
| AC-3: auto-switch timeout probe unchanged | PASS | — |
| Line 242: comment starts with `//` (not `\`) | PASS | — |
| `probeQueue` is `@Volatile` | PASS | — |
| `probeQueue` nulled in `onDestroy` | PASS | — |
| `ServerAutoSwitcher.probeRequestQueue` nulled in `onDestroy` | PASS | — |
| `ServerAutoSwitcherTest.tearDown` calls `resetForTest()` | PASS | — |
| AC-1 probe-enqueued test | PASS | — |
| AC-1 no-probe-when-id-zero test | PASS | — |
| AC-2 hydration-gap test | PASS | — |
| Missing null-queue test for `probeQueue == null` | MINOR GAP | Non-blocking |
| Positive-probe AC-2 path untestable (logically impossible state) | ACCEPTABLE | Non-blocking |
| No hardcoded URLs or credentials | PASS | — |
| Logging via AppLog only | PASS | — |
| 12/12 targeted tests pass (fresh --rerun-tasks run) | PASS | — |

**BLOCKING FINDINGS: 0**
**NON-BLOCKING GAPS: 2** (null-queue test coverage; logically-impossible AC-2 positive enqueue)

---

## Final Verdict

**GATE_RESULT: PASS**
**BLOCKING_COUNT: 0**
**EVIDENCE:** docs/qa-evidence/us-12-review-iter2.md
**COMMIT:** 6d281b3580ba91ad3d6de2186023a27f7da6c712
**SUMMARY:** Two passes completed. All AC-1, AC-2, AC-3 checks pass. 12/12 targeted unit tests pass on fresh rerun. No blocking findings. Two non-blocking test coverage gaps noted (null-queue scenario, logically-impossible AC-2 positive probe path) — both consistent with existing test patterns in the codebase.
