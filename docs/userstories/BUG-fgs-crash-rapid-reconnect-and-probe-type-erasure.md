---
title: "BUG-FIX: App crashes on 3rd rapid VPN connect attempt and probe fails with type error"
description: |
  Two bugs reported 2026-06-25 (build 100).
  1. App crashes with RemoteServiceException after 2 rapid connect/disconnect cycles
     followed immediately by a 3rd connect attempt.
  2. ProbeRequestWorker fails with IllegalArgumentException (Response generic type erased by R8).

## Context
- Crash date: 2026-06-25
- Affected build: 100
- Crash type: `android.app.RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground()`
- Affected service: `com.yahorzabotsin.openvpnclientgate/.vpn.OpenVpnService`
- Source files:
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
  - `src/core/consumer-rules.pro`
- Evidence: logcat_20260625_155649.txt (local logcat capture, not committed)
- Crash PID: 28271 (recovery PID 6411 — succeeded)
- Related: Prior fix in commit f7aee57 addressed a similar FGS crash (build 95 → 96); this is a distinct surviving race.

## Reproduction Steps (Bug 1 — FGS crash)
1. Connect to VPN (1st attempt)
2. Disconnect within ~2 s
3. Immediately reconnect (2nd attempt)
4. Disconnect again within ~2 s
5. Browse server list → return to main screen
   (`MainActivityCore.onStart()` → `VpnManager.syncStatus()` → `startService(ACTION_SYNC_STATUS)`)
6. Tap Connect within ~1 s of returning to main screen
7. **App crashes** with `RemoteServiceException`

## Expected Behavior
VPN connects normally on the 3rd attempt without crash.

## Actual Behavior
App crashes. PID 28271 terminates. Auto-restart (PID 6411) succeeds.

---

## Bug 1 Root Cause — FGS Timer Race

`syncEngineState()` at line 1711 called `exitControllerForeground()` (→ `stopForeground()`) for
**any** engine level that is not `LEVEL_START` or `UNKNOWN_LEVEL`, including `LEVEL_NOTCONNECTED`
and `LEVEL_NONETWORK`. Both of those levels mean the engine is **completely idle** (not running).

During service startup via `startService(ACTION_SYNC_STATUS)`:

1. `onCreate()` → `enterControllerForeground()` → `startForeground()` ← FGS safety net established
2. AIDL delivers `LEVEL_NOTCONNECTED` snapshot → `syncEngineState()` →
   `exitControllerForeground()` ← **safety net removed** (bug — should not happen for idle levels)
3. `onOneShotInitialStateSynced()` → `stopAfterOneShotSyncRunnable` posted (+1000 ms)
4. At +1 s: `stopSelf()` called
5. User taps Connect → `startForegroundService(ACTION_START)` while service is in destruction
   window — an earlier `startForegroundService()` call's 5-second AMS timer was not cleared in
   time (timer origin: ~15:55:37.008, fires at exactly 15:55:42.008 = +5000 ms)
6. `RemoteServiceException` crash

**Why the prior fix (f7aee57) did not fully solve it:**
That fix added `enterControllerForeground()` in `onCreate()` but did NOT prevent
`syncEngineState(LEVEL_NOTCONNECTED)` from immediately calling `exitControllerForeground()`
milliseconds later — cancelling the `startForeground()` safety net and re-opening the race.

## Bug 1 Fix

In `syncEngineState()`, extend the guard to exclude idle engine levels:

```kotlin
// BEFORE
if (controllerForegroundActive && level != ConnectionStatus.LEVEL_START && level != ConnectionStatus.UNKNOWN_LEVEL) {
    exitControllerForeground()
}

// AFTER
if (controllerForegroundActive
    && level != ConnectionStatus.LEVEL_START
    && level != ConnectionStatus.UNKNOWN_LEVEL
    && level != ConnectionStatus.LEVEL_NOTCONNECTED
    && level != ConnectionStatus.LEVEL_NONETWORK) {
    exitControllerForeground()
}
```

`ACTION_STOP` (line 678) and the `ACTION_SYNC_STATUS` handler (conditional on
`state == DISCONNECTED`) both call `exitControllerForeground()` explicitly — those paths are
unaffected by this change.

---

## Bug 2 Root Cause — ProbeApi Response<Unit> Type Erasure

`ProbeApi.probe()` is declared `suspend fun probe(...): Response<Unit>`. R8 strips the
`<Unit>` generic type parameter from the compiled interface's JVM Signature attribute despite
`-keepattributes Signature` being present in `consumer-rules.pro`.

The existing rule `-keep,allowobfuscation interface * { @retrofit2.http.* <methods>; }` gives
R8 latitude to optimise method representations under `allowobfuscation`, which in some R8
versions removes the Signature attribute for short suspend methods. Retrofit 2.9.0 inspects
the return type generics at method-inspection time and throws:

```
java.lang.IllegalArgumentException: Response must include generic type (e.g., Response<String>)
    for method c.a
```

(`c.a` is the R8-obfuscated name for `ProbeApi.probe()` in build 100.)

## Bug 2 Fix

Add a fully-qualified keep rule for `ProbeApi` in `consumer-rules.pro` that prevents R8 from
touching any member of the interface:

```proguard
-keep interface com.yahorzabotsin.openvpnclientgate.core.servers.probe.ProbeApi { *; }
```

---

## Regression Risk Areas
1. **VPN connect flow** — `syncEngineState(LEVEL_NOTCONNECTED)` no longer calls
   `exitControllerForeground()`. Foreground notification stays active until `ACTION_STOP`
   or `ACTION_SYNC_STATUS` removes it. No user-visible change (notification was transient).
2. **VPN pause (LEVEL_NONETWORK)** — previously `exitControllerForeground()` was called here
   too. With fix, controller stays foreground while VPN is paused waiting for network — correct,
   because the engine is paused not stopped.
3. **ServerAutoSwitcher reconnect** — passes through `ACTION_START`; no change.
4. **`onDestroy()` safety net** — still calls `exitControllerForeground()`; no change.
5. **`ACTION_STOP`** — calls `exitControllerForeground()` at line 678 unconditionally; no change.
6. **ProbeApi R8 keep rule** — additive rule, no impact on other interfaces or code paths.

## Acceptance Criteria
- [ ] 3rd rapid-connect crash no longer occurs (3× connect/disconnect → connect → no crash)
- [ ] No `RemoteServiceException` in logcat for `OpenVpnService`
- [ ] `ProbeRequestWorker` no longer throws `IllegalArgumentException` for type erasure
- [ ] No logcat `W/ProbeRequestWorker` for "Response must include generic type"
- [ ] No regression on normal connect / disconnect / reconnect
- [ ] No regression on `ServerAutoSwitcher` auto-switch reconnect
- [ ] No regression on VPN pause (LEVEL_NONETWORK) → foreground notification remains visible

## Implementation Handoff
- Branch: `fix/fgs-crash-rapid-reconnect`
- Story path: `docs/userstories/BUG-fgs-crash-rapid-reconnect-and-probe-type-erasure.md`
- Files changed:
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
    - `syncEngineState()` line ~1711: extend guard to exclude LEVEL_NOTCONNECTED, LEVEL_NONETWORK
  - `src/core/consumer-rules.pro`
    - Added `-keep interface ...ProbeApi { *; }` at end of file
