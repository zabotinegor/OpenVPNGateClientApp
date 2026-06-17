# Quality Gate — SUB-04: VPN inactivity → hardprobe trigger integration

**Date:** 2026-06-16  
**Branch:** `feature/sub-04-vpn-inactivity-hardprobe-trigger`  
**Diff scope:** `origin/dev..HEAD`  
**Iterations completed:** 2  
**Prerequisite:** `steps.review.status = passed` — confirmed in `.sdlc/status.json`

---

## Changed files

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/ServerAutoSwitcher.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/vpn/VpnInactivityHardprobeTriggerTest.kt` (new)

---

## Iteration 1 — Build + Unit Tests

### Check 1: Build (`assembleDebugApp`)

```
BUILD SUCCESSFUL in 1m 5s
```

Status: PASS. No compile errors, no new warnings introduced in scope files.

### Check 2: Unit tests (`testDebugUnitTestApp`)

```
BUILD SUCCESSFUL in 45s
VpnInactivityHardprobeTriggerTest:  6 tests,  0 failures,  0 errors,  0 skipped
ServerAutoSwitcherTest:             8 tests,  0 failures,  0 errors,  0 skipped
ServerAutoSwitcherV2HydrationTest:  7 tests,  0 failures,  0 errors,  0 skipped
Core module total:                594 tests,  0 failures
TV module total:                   17 tests,  0 failures
Mobile module total:                2 tests,  0 failures
Grand total:                      613 tests,  0 failures,  0 errors
```

Status: PASS. All 613 tests green.

---

## Iteration 2 — Static Analysis + AC Coverage + Edge Cases

### Check 3: Test coverage adequacy (AC-6 scenarios)

The story requires 6 tests covering 5 scenarios. Verified mapping:

| Test | AC scenario | Result |
|---|---|---|
| `autoswitchTimeout_enqueuesToProbeQueueWithCorrectServerId` | AC-1 timer path | PASS |
| `authFailedImmediateSwitch_enqueuesToProbeQueueWithCorrectServerId` | AC-1 immediate/auth-failed path | PASS |
| `watchdogRecovery_enqueuesToProbeQueueWithCurrentServerId` | AC-2 watchdog recovery | PASS |
| `userStop_doesNotEnqueueProbe` | AC-3 user-stop no-probe | PASS |
| `zeroIdServer_doesNotEnqueueProbe` | AC-5 zero-id guard | PASS |
| `levelNoNetworkFromAidl_doesNotEnqueueProbe` | AC-4 NONETWORK no-probe | PASS |

AC-6 fully satisfied: 6 tests, 5 scenarios, all assertions confirmed green.

### Check 4: Edge case handling

**`currentServer()` returns null → `failingServerId=0` → no probe:**

Both `ServerAutoSwitcher.requestSwitchNow()` (line 242-244) and `OpenVpnService.handleConnectedProbeResult()` (line 1436) use:
```kotlin
runCatching { SelectedCountryStore.currentServer(ctx)?.id ?: 0 }.getOrElse { 0 }
```
Null-safe: double protection via `?.id ?: 0` and `.getOrElse { 0 }`. No probe fires.

**Tested directly:** `zeroIdServer_doesNotEnqueueProbe` exercises the `id=0` guard path (PASS).

**Koin resolution failure in `onCreate()` → `probeQueue=null` → no probe:**

`OpenVpnService.onCreate()` wraps the Koin resolution in `runCatching {...}.onFailure { e -> AppLog.w(...) }` (lines 450-456). If `ProbeRequestQueue` is not in the Koin graph, both `probeQueue` and `ServerAutoSwitcher.probeRequestQueue` remain `null`. All call sites use null-safe `?.enqueue(...)`. Service does not crash; feature degrades to no-op.

**Not directly unit-tested** (Koin DI failure path is an infrastructure concern, covered by the existing `CoreDiTest` which validates the binding is present). Acceptable — the null-safe pattern is structurally sufficient.

### Check 5: Performance

`WorkManagerProbeRequestQueue.enqueue()` submits a WorkManager `OneTimeWorkRequest`. WorkManager enqueue is documented as main-thread safe (it offloads scheduling asynchronously). No blocking I/O on the main thread. Timer callbacks and `handleConnectedProbeResult` are both dispatched on `Handler(Looper.getMainLooper())`. Performance impact: negligible.

### Check 6: Security

- No credentials introduced.
- No hardcoded production URLs.
- `serverId` is an `Int` from the local server model — not sensitive data.
- Logging uses `AppLog.w(TAG, ...)` with exception parameter — follows `src/docs/logging-policy.md`.
- No `android.util.Log` calls introduced.

Status: PASS.

### Check 7: Regression — `ServerAutoSwitcherTest` + `ServerAutoSwitcherV2HydrationTest`

```
ServerAutoSwitcherTest:            8/8  PASS
ServerAutoSwitcherV2HydrationTest: 7/7  PASS
```

Existing cancel, timer, v2 hydration, and chained-switch paths untouched. No regression.

---

## Risk register

| ID | Risk | Severity | Disposition |
|---|---|---|---|
| R1 | Koin unavailable in onCreate — probeQueue=null | Low | Handled — runCatching + null-safe calls, graceful no-op |
| R2 | v2 hydration empty-store path silently skips probe | Low | Accepted — store empty means no known serverId; not a probe target |
| R3 | watchdogProbe mock is dead code in watchdog test | Trivial | Observation only — assertion still valid, test still exercises probe enqueue |
| R4 | AC-8 manual QA (probe reaches server) | Medium | Deferred to manual QA step per story (server-side verification) |

---

## Coverage by layer

| Layer | Status |
|---|---|
| Unit | 6 new tests — all 5 AC-6 scenarios covered; PASS |
| Component/Integration | Covered by existing ServerAutoSwitcherTest (8) + WatchdogTest (18); PASS |
| UI | Not applicable — no UI changes |
| E2E | Deferred to manual QA (AC-8: end-to-end probe reaching server) |

---

## Conclusion

All gate checks pass across 2 iterations.

- `assembleDebugApp`: BUILD SUCCESSFUL
- `testDebugUnitTestApp`: 613/613 PASS (0 failures, 0 errors)
- AC-6 scenarios: 6/6 tests, 5/5 scenarios verified
- Edge cases: null-server and Koin-failure paths are safe
- Security: clean
- Performance: no main-thread blocking I/O
- Regression: ServerAutoSwitcherTest 8/8, V2HydrationTest 7/7 PASS

**GATE_RESULT: PASS**
