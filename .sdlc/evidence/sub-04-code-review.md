# Code Review — SUB-04: VPN inactivity → hardprobe trigger integration

**Date:** 2026-06-16  
**Branch:** `feature/sub-04-vpn-inactivity-hardprobe-trigger`  
**Diff scope:** `origin/dev..HEAD`  
**Reviewer:** Claude Code (Sonnet 4.6)  
**Passes completed:** 2

---

## Diff summary

3 files changed:
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/ServerAutoSwitcher.kt` — +23 lines
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt` — +18 lines
- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/vpn/VpnInactivityHardprobeTriggerTest.kt` — new, 265 lines

---

## Pass 1 — AC coverage, logic correctness, and structure

### AC-1: Autoswitch probe (PASS)

`requestSwitchNow()` captures `failingServerId` BEFORE `nextServerCircular()` advances the index (line 242-244 in `ServerAutoSwitcher.kt`). The guard:

```kotlin
val failingServerId = if (level != ConnectionStatus.LEVEL_NONETWORK) {
    runCatching { SelectedCountryStore.currentServer(appContext)?.id ?: 0 }.getOrElse { 0 }
} else 0
```

Probe fires after `next` resolution but before the switch (line 304-306). Both timer-triggered and `LEVEL_AUTH_FAILED` immediate paths flow through `requestSwitchNow` and therefore enqueue the probe.

### AC-2: Watchdog probe (PASS)

In `OpenVpnService.handleConnectedProbeResult()`, after `recoveryTarget` is resolved (non-null), the probe is enqueued via `probeQueue`:

```kotlin
val watchdogServerId = runCatching { SelectedCountryStore.currentServer(applicationContext)?.id ?: 0 }.getOrElse { 0 }
if (watchdogServerId != 0) {
    try { probeQueue?.enqueue(watchdogServerId) } catch (e: Exception) { ... }
}
```

This fires before `watchdogRecoveryStarter` is called. The failing server is still current at this point (recovery hasn't switched yet). Ordering is correct.

### AC-3: User-stop no-probe (PASS — structurally enforced)

User-initiated stops go through `startUserStopTeardown()`. The probe-fire path only exists inside `requestSwitchNow()`. `requestSwitchNow` is called only from:
1. The timer runnable (canceled during user stop)
2. The `shouldSwitchImmediately` block (only if `timerActive || isConnecting`)

User stop cancels the timer before the callback fires. `LEVEL_NOTCONNECTED` calls `cancel()` which never invokes `requestSwitchNow`. AC-3 is structurally, not just behaviorally, enforced.

### AC-4: LEVEL_NONETWORK no-probe (PASS)

The `failingServerId` is set to `0` when `level == LEVEL_NONETWORK` (explicit `else 0` branch). The guard `if (failingServerId != 0)` blocks the enqueue. Additionally, LEVEL_NONETWORK arrives as `shouldSwitchImmediately` → `requestSwitchNow` with `level=LEVEL_NONETWORK`. The code correctly handles this at the capture site.

### AC-5: Zero-id guard (PASS)

Both in `ServerAutoSwitcher.requestSwitchNow` and `OpenVpnService.handleConnectedProbeResult`, the pattern is:

```kotlin
val id = runCatching { ... ?.id ?: 0 }.getOrElse { 0 }
if (id != 0) { probeQueue?.enqueue(id) }
```

`Server.id: Int = 0` is the default for unknown servers. The double-safe `runCatching + getOrElse(0)` ensures no probe fires if `currentServer()` throws or returns null.

### AC-6: Six unit tests (PASS)

Test class `VpnInactivityHardprobeTriggerTest` contains exactly 6 tests:

| Test name | AC covered |
|---|---|
| `autoswitchTimeout_enqueuesToProbeQueueWithCorrectServerId` | AC-1 (timer path) |
| `authFailedImmediateSwitch_enqueuesToProbeQueueWithCorrectServerId` | AC-1 (immediate path) |
| `watchdogRecovery_enqueuesToProbeQueueWithCurrentServerId` | AC-2 |
| `userStop_doesNotEnqueueProbe` | AC-3 |
| `zeroIdServer_doesNotEnqueueProbe` | AC-5 |
| `levelNoNetworkFromAidl_doesNotEnqueueProbe` | AC-4 |

All 5 scenarios covered; AC-1 has two tests (timer + auth-failed), matching the story's "6 tests covering all 5 scenarios."

**Test run result:**
```
VpnInactivityHardprobeTriggerTest: 6/6 PASSED
Full suite: BUILD SUCCESSFUL (testDebugUnitTestApp)
```

---

## Pass 2 — Thread safety, memory, edge cases, regression

### Thread safety (PASS)

`probeRequestQueue` is annotated `@Volatile` in `ServerAutoSwitcher`. All calls to `probeRequestQueue?.enqueue()` happen on the main thread (inside the `requestSwitchNow` function which is called from `Handler(Looper.getMainLooper())` timers and from `onEngineLevel` which is also dispatched on main). `WorkManagerProbeRequestQueue.enqueue()` is WorkManager-backed and is documented safe to call on main thread. No background thread accesses the queue.

In `OpenVpnService`, the `probeQueue` field is a private non-volatile instance field. Access is on main thread only: `handleConnectedProbeResult` is posted via `statusHandler.post {...}` (which uses a Handler backed by the main looper), or called directly from `evaluateConnectedHealth` which is also on main. No cross-thread access to `probeQueue`.

### Memory leak (PASS — sufficient)

`onDestroy` nulls both references:
```kotlin
ServerAutoSwitcher.probeRequestQueue = null  // clears singleton ref
probeQueue = null                             // clears instance ref
```

`WorkManagerProbeRequestQueue` holds a `WorkManager` reference (via `get<WorkManager>()`), which is an application-scoped singleton and doesn't carry Service references. No circular reference or leak risk. The pattern is identical to `v2HydrationCallback` which has been in production without issues.

### Edge case: null from currentServer() (PASS)

Both probe capture sites use:
```kotlin
runCatching { SelectedCountryStore.currentServer(appContext)?.id ?: 0 }.getOrElse { 0 }
```
Safe null-handling with exception fallback. No probe fires on null/exception.

### Edge case: Koin resolution failure in onCreate() (PASS)

The wiring block is wrapped in `runCatching {...}.onFailure { e -> AppLog.w(...) }`. If `ProbeRequestQueue` is not yet in the Koin context, `probeQueue` and `ServerAutoSwitcher.probeRequestQueue` remain null. Null-safe calls (`?.enqueue(...)`) ensure no crash. The feature gracefully degrades to no-op probe behavior.

### Regression check: existing ServerAutoSwitcher paths (PASS)

- `cancel()` path: unchanged, does not touch `probeRequestQueue`
- `start()` / timer path: unchanged, probe fires only inside `requestSwitchNow`
- `v2HydrationCallback` path: unchanged; probe is NOT fired when `v2HydrationPending=true` returns early (lines 234-237 return before `failingServerId` is computed). This is correct — if the store is empty we have no valid failing server id anyway
- `v2HydrationPending` early-return (line 234-237): returns BEFORE `failingServerId` is computed; no probe fires. Acceptable: empty store implies unknown server.
- `beginChainedSwitch()` path: unchanged, no probe involvement
- `scheduleStopRetryTimeout()` path: unchanged

No existing logic altered in the cancel, timer, or v2 hydration flows.

### v2 hydration path probe gap (ACCEPTED — not a defect)

When `next == null && total == 0 && serverSource == DEFAULT_V2`: the function fires a hydration callback and `return`s at line 297 WITHOUT enqueuing a probe. `failingServerId` was computed above but is abandoned. This is acceptable: if the server store is empty, there is no known `serverId` to probe — the server was never successfully used. This is not a regression; it's a non-issue edge case.

### DI wiring (PASS)

`CoreDi.kt` already has `single<ProbeRequestQueue> { WorkManagerProbeRequestQueue(get<WorkManager>()) }` from SUB-02. `OpenVpnService.onCreate` resolves it via `GlobalContext.get().get<ProbeRequestQueue>()` — same resolver used for other singletons. Consistent with established pattern.

### Test helper: watchdogProbe mock is dead in watchdog test (OBSERVATION, not a defect)

The watchdog test sets `watchdogProbe` to return `false`, but `evaluateConnectedHealth` takes the `probeTargets.isEmpty()` path in test (no BUILD_CONFIG primary URL), calling `handleConnectedProbeResult` directly without invoking `watchdogProbe`. The mock is harmless dead code. The test's probe-enqueue assertion is still exercised correctly. This is a cosmetic observation only.

### Logging (PASS)

All new log calls use `AppLog.w(TAG, ...)` with a caught exception parameter. Follows `src/docs/logging-policy.md`. No `android.util.Log` usage introduced.

---

## Build validation

```
./gradlew :core:testDebugUnitTest
VpnInactivityHardprobeTriggerTest: 6/6 PASSED
BUILD SUCCESSFUL

./gradlew testDebugUnitTestApp
BUILD SUCCESSFUL (all modules, UP-TO-DATE / cache)
```

`assembleDebugApp` not re-run (no source changes since last successful build; UP-TO-DATE). The last commit `f8c4e74` is the sole change on this branch. Tests confirm compile + runtime correctness.

---

## Risk register

| ID | Risk | Severity | Status |
|---|---|---|---|
| R1 | v2 hydration empty-store path skips probe | Low | Accepted — store empty means no known serverId |
| R2 | watchdogProbe mock dead in watchdog test | Trivial | Observation only — test assertion still valid |
| R3 | Koin unavailable in onCreate | Low | Handled — runCatching + null-safe call |

---

## Action plan

No blocking findings. No recommended fixes required. All ACs satisfied, all tests pass, no regressions detected.

---

## Conclusion

**GATE_RESULT: PASS (no blocking findings)**

All 5 ACs and 6 unit tests verified. Thread safety correct. Memory lifecycle adequate. No regression in existing autoswitch, cancel, timer, or v2 hydration paths.
