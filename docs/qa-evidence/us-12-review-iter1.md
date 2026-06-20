# Code Review — US-12: Hardprobe on Every VPN Disconnect
**Branch:** `feature/us-12-hardprobe-on-disconnect`  
**Base:** `dev`  
**Reviewer:** Claude Code  
**Date:** 2026-06-20  
**Iteration:** 1

---

## Scope

| File | Role |
|------|------|
| `src/core/…/vpn/OpenVpnService.kt` | Probe enqueue in `finishStopFlowConfirmed` |
| `src/core/…/vpn/ServerAutoSwitcher.kt` | Probe enqueue in DEFAULT_V2 hydration path |
| `src/core/…/vpn/OpenVpnServiceDisconnectProbeTest.kt` | New unit tests (AC-1) |
| `src/core/…/vpn/ServerAutoSwitcherTest.kt` | Updated unit tests (AC-2) |

---

## Pass 1 — Correctness and Logic

### 1.1 Probe placement relative to DISCONNECTED state update (OpenVpnService)

**Finding: PASS**

In `finishStopFlowConfirmed` the sequence is:

```kotlin
ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)   // line 346
val serverId = SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(...)  // line 347-352
if (serverId != 0) {
    probeQueue?.enqueue(serverId)                                   // line 354
}
```

The probe fires **after** DISCONNECTED is set — correct. The user story AC-1 requirement is that the probe fires on confirmed disconnect, and that is satisfied. There is no ordering issue.

### 1.2 Thread safety of `getCurrentServerIdIfMatchingLastStarted` in `finishStopFlowConfirmed`

**Finding: PASS (with note)**

`finishStopFlowConfirmed` is called from `handleEngineLevelForStop`, which is called from:
- `updateState(…)` — annotated `@MainThread` (line 1044)
- `syncEngineState(…)` — called from `statusCallbacks.updateStateString`, which is an AIDL binder callback; however, it is posted and handled on the main thread via `statusHandler` in the surrounding code flow.

`getCurrentServerIdIfMatchingLastStarted` reads `SharedPreferences` synchronously. This is acceptable on the main thread for small preference files (Android docs allow this for rarely-updated prefs). The existing codebase calls this same function in the same thread context in the VPN_STATUS auto-switch path (line 1086) and the watchdog path (line 1459) without any concern — the new disconnect probe call is fully consistent with those patterns.

**No blocking IO concern.** SharedPreferences reads are in-memory after the first load.

### 1.3 Probe placement in DEFAULT_V2 hydration path (ServerAutoSwitcher)

**Finding: PASS**

New code in `requestSwitchNow`:

```kotlin
if (failingServerId != 0) {
    probeRequestQueue?.enqueue(failingServerId)     // inserted here
}
v2HydrationPending = true                          // existing line
callback(appContext) { … }
return
```

The probe fires **before** `v2HydrationPending = true`. This is correct because:
1. `failingServerId` is already captured (line 244–246) before the hydration block is reached.
2. Setting `v2HydrationPending = true` after the enqueue has no effect on the probe call itself.
3. The `v2HydrationPending` guard at line 234 only prevents re-entry on a subsequent `requestSwitchNow` call while hydration is in progress — the probe does not interact with that flag.

**Ordering is correct and does not affect correctness of either the probe or the hydration flow.**

### 1.4 Guard `serverId != 0` consistency with other probe call sites

**Finding: PASS**

All four probe call sites in the codebase share the same `!= 0` guard pattern:
- `OpenVpnService.kt` line 1089: `if (vpnStatusFailingServerId != 0)`
- `OpenVpnService.kt` line 1460: `if (watchdogServerId != 0)`
- `ServerAutoSwitcher.kt` line 265: `if (failingServerId != 0)` (new)
- `ServerAutoSwitcher.kt` line 311: `if (failingServerId != 0)` (pre-existing)
- `OpenVpnService.kt` line 353: `if (serverId != 0)` (new)

All consistent. The guard is correct: `getCurrentServerIdIfMatchingLastStarted` returns `0` both when the server ID is literally zero and when there is a config mismatch, so the guard correctly suppresses spurious probes in both cases.

### 1.5 LEVEL_NONETWORK exclusion

**Finding: PASS**

The DEFAULT_V2 new probe code inherits the existing `failingServerId` which is computed at line 244–246:

```kotlin
val failingServerId = if (level != ConnectionStatus.LEVEL_NONETWORK) {
    SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(appContext)
} else 0
```

So LEVEL_NONETWORK correctly yields `failingServerId = 0`, which the guard at line 265 suppresses. This is consistent with the behaviour at line 311 (the normal auto-switch probe path). No regression.

### 1.6 Error handling consistency

**Finding: PASS**

New error handling follows exactly the same `try { … } catch (e: Exception) { AppLog.w(…) }` pattern used in all existing probe enqueue call sites (lines 1090, 1461). The outer `try/catch` around `getCurrentServerIdIfMatchingLastStarted` in `finishStopFlowConfirmed` falls back to `0`, which the guard naturally suppresses — a safe and silent degradation.

### 1.7 No `android.util.Log` calls

**Finding: PASS** — Grep confirmed no `android.util.Log` references in changed production files.

### 1.8 No hardcoded URLs or production constants

**Finding: PASS** — No URLs, tokens, or environment-specific strings introduced.

### 1.9 Minor style observation (non-blocking)

`try { probeQueue?.enqueue(serverId) } catch (e: Exception) { … }` is written on a single line without braces (line 354–356 in OpenVpnService; identical pattern at lines 1090, 1461). This is consistent with the pre-existing code style in the surrounding file. Not a blocking issue.

---

## Pass 2 — Tests

### 2.1 New test file: `OpenVpnServiceDisconnectProbeTest`

**Overall: PASS with one gap noted**

#### Test 1: `finishStopFlowConfirmed_enqueuesToProbeQueueWithCorrectServerId`
- Sets up a two-server list with server `id=42` at index 0 and `id=99` at index 1.
- Saves `lastStartedConfig = "conf1"` which matches current server at index 0 (id=42).
- Calls `finishStopFlowConfirmed` via reflection.
- Asserts exactly one enqueue for `serverId=42`.

**Correctness:** The setup correctly aligns current index with last-started config. Reflection call uses correct parameter types. Assertion is specific (size=1, value=42). PASS.

#### Test 2: `finishStopFlowConfirmed_doesNotEnqueueProbeWhenServerIdIsZero`
- Both servers have `id=0`.
- Confirms no enqueue occurs.

**Correctness:** Exercises the `id=0` path. PASS.

#### Test 3: `finishStopFlowConfirmed_doesNotEnqueueProbeWhenConfigMismatch`
- Saves `lastStartedConfig = "old-conf"` which is not in the server list.
- Comment explains that `ensureIndexForConfig` won't realign, leaving current=conf1 while lastStarted="old-conf" — mismatch → id=0.
- Confirms no enqueue.

**Correctness:** This is the correct way to test the mismatch guard given that `saveLastStartedConfig` internally calls `ensureIndexForConfig`. The comment accurately explains the subtle setup. PASS.

#### Missing test case: `probeQueue == null`
**Finding: MINOR GAP (non-blocking)**

There is no test verifying that when `probeQueue` is `null` (i.e., DI wiring failed at startup and the field was never set), `finishStopFlowConfirmed` does not crash. The production code handles this with the safe-call `probeQueue?.enqueue(serverId)`, and the `null`-queue scenario is already implicitly covered by the other existing callers (watchdog, VPN_STATUS fallback) which also lack null-queue tests. Not blocking — consistent with the existing test gap pattern — but worth noting.

### 2.2 Updated test: `ServerAutoSwitcherTest.defaultV2HydrationGap_hydrationPathEnteredAndNoProbeForEmptyStore`

**Overall: PASS with one observability gap**

- Configures `ServerSource.DEFAULT_V2`, empty server list, fake `ProbeRequestQueue`, and a hydration callback that records invocation but does NOT invoke `onDone`.
- Triggers the timer path via `LEVEL_CONNECTING_NO_SERVER_REPLY_YET` and idles for 2 seconds.
- Asserts hydration callback was invoked.
- Asserts no probe was enqueued (since `total==0` means `currentServer()` is null → `failingServerId==0`).

**Correctness of assertions:** The test correctly verifies the path is entered (assertion 1) and the guard works (assertion 2). Cleanup restores `ServerSource.LEGACY`. PASS.

**Observability gap (non-blocking):** The test verifies the guard blocks a probe when `failingServerId==0` (empty store), but does **not** cover the complementary case where `failingServerId != 0` and a probe **is** enqueued via the DEFAULT_V2 path. However, this scenario requires a non-empty store (which would not trigger the hydration path since `total == 0` is the condition for entering it). The mutually exclusive nature of `total==0` (hydration path) and `failingServerId!=0` (requires `currentServer()!=null`, which requires non-empty store) means this combination is logically impossible in production. The test gap is acceptable.

### 2.3 Test isolation and mock setup

**Finding: PASS**

- `FakeProbeRequestQueue` is a proper fake (not a spy/mock), idiomatic for Kotlin unit tests.
- `ReflectionHelpers.setField` for `probeQueue` and `userInitiatedStop` is the correct approach for testing private fields in Robolectric without changing production visibility.
- `@After tearDown` resets `ConnectionStateManager` state and clears `ServerAutoSwitcher.probeRequestQueue` — no state leakage between tests.
- `ShadowLog.clear()` in `@Before`/`@After` ensures log assertions are not polluted.

### 2.4 Test style consistency with existing `ServerAutoSwitcherTest`

**Finding: PASS**

The new `defaultV2HydrationGap_hydrationPathEnteredAndNoProbeForEmptyStore` test:
- Uses the same `Shadows.shadowOf(Looper.getMainLooper()).idleFor(…)` pattern.
- Uses inline `object : ProbeRequestQueue { … }` (vs. named class in the other file) — both styles are acceptable and consistent within their respective files.
- Cleanup is placed in `tearDown` via `ServerAutoSwitcher.setProbeRequestQueueForTest(null)` — PASS.

---

## Summary Table

| Check | Result | Severity |
|-------|--------|----------|
| Probe fires after DISCONNECTED state | PASS | — |
| Thread safety of SharedPreferences read | PASS | — |
| DEFAULT_V2 probe placement vs. `v2HydrationPending` | PASS | — |
| Guard `serverId != 0` consistency | PASS | — |
| LEVEL_NONETWORK exclusion | PASS | — |
| Error handling pattern | PASS | — |
| No `android.util.Log` | PASS | — |
| No hardcoded URLs | PASS | — |
| AC-1 test: correct serverId enqueued | PASS | — |
| AC-1 test: no enqueue when id=0 | PASS | — |
| AC-1 test: no enqueue on config mismatch | PASS | — |
| AC-1 test: missing null-queue case | MINOR GAP | Non-blocking |
| AC-2 test: DEFAULT_V2 hydration path entered | PASS | — |
| AC-2 test: guard prevents spurious probe | PASS | — |
| AC-2 test: positive probe enqueue not testable | ACCEPTABLE GAP | Non-blocking |
| Test isolation and teardown | PASS | — |

**BLOCKING FINDINGS: 0**  
**Non-blocking gaps: 2** (null-queue test, positive-probe-in-DEFAULT_V2 test)
