# US-12 Docs Evidence

**Story:** US-12 — Hardprobe on Every VPN Disconnect  
**Date:** 2026-06-20  
**Iteration:** 1

## Files Audited

### `src/docs/server-sync-flow.md` — Updated

**Prior state:** The "Hardprobe Trigger Points (SUB-04)" section listed two trigger paths
(autoswitch timeout and watchdog recovery). It did not mention the three additional paths that
now exist in the codebase:
- User-initiated disconnect (`finishStopFlowConfirmed`)
- DEFAULT_V2 hydration early-return probe (`requestSwitchNow`)
- VPN_STATUS engine auto-switch (`updateState`)

**Change:** Replaced the two-item list with a five-item enumerated list covering all current
probe call sites (SUB-04 paths retained; three additional paths documented with their stories).
Added clarification on the `serverId == 0` suppression, WorkManager `KEEP` deduplication, and
the distinction between `LEVEL_NONETWORK` (device loss, no probe) and server failure levels.

Section heading renamed from "Hardprobe Trigger Points (SUB-04)" to "Hardprobe Trigger Points"
to reflect that the section now covers multiple stories.

### `docs/runbooks/how-to.md` — Updated

**Prior state:** No entry existed for the hardprobe lifecycle pattern.

**Change:** Added a new section "Hardprobe enqueue during VPN lifecycle — when it fires and when
it is suppressed" covering:
- A table of all five enqueue call sites with code locations
- Explanation of why `serverId == 0` is a no-op (legacy CSV servers, LEVEL_NONETWORK, selection
  mismatch)
- Note on WorkManager `KEEP` deduplication behavior
- Cross-references to the relevant source files and user stories

### `docs/runbooks/solutions.md` — No change required

No entry is needed; US-12 introduces no new failure mode or known issue requiring a solutions
runbook entry. The existing entries (WorkManager unit test pattern, AlarmManager mock issue,
OkHttp MockWebServer catalog) are unaffected.

### `docs/runbooks/android-qa.md` — No change required

Reviewed for any hardprobe QA steps; none are present that need updating for US-12. The section
cross-references `android-qa-adb-cookbook.md` (in `src/docs/`) which is unaffected.

## Code Verification

### `OpenVpnService.finishStopFlowConfirmed` (US-12 AC-1)

Confirmed at lines 336–377 of `OpenVpnService.kt`:

```kotlin
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

Matches the implementation spec in US-12 exactly.

### `ServerAutoSwitcher.requestSwitchNow` DEFAULT_V2 hydration path (US-12 AC-2)

Confirmed at lines 264–269 of `ServerAutoSwitcher.kt`:

```kotlin
if (failingServerId != 0) {
    try { probeRequestQueue?.enqueue(failingServerId) } catch (e: Exception) {
        AppLog.w(TAG, "DEFAULT_V2: failed to enqueue hardprobe for serverId=$failingServerId", e)
    }
}
```

Probe is enqueued before `v2HydrationPending = true` and the early `return`, consistent with
the US-12 AC-2 spec.

### Pre-existing probe paths (US-12 AC-3 — no regression)

- `handleConnectedProbeResult` (watchdog): line 1459-1461, unchanged.
- `updateState` (VPN_STATUS auto-switch): line 1090, unchanged.
- `requestSwitchNow` main probe block (non-hydration): lines 311-313, unchanged.

## Gaps Found

None. All AC-1 through AC-3 changes are correctly in place. The `id == 0` guard is applied
consistently at every call site.

## Documentation Files Changed

| File | Action |
|---|---|
| `src/docs/server-sync-flow.md` | Updated Hardprobe Trigger Points section |
| `docs/runbooks/how-to.md` | Added hardprobe lifecycle how-to entry |
