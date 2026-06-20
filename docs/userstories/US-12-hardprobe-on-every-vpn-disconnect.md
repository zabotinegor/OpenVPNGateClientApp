# US-12: Hardprobe on Every VPN Disconnect

## User Story

As the VPN service, when any VPN disconnect completes, I want to send a hardprobe to the server that was active, so that the backend receives timely health feedback regardless of who initiated the disconnect.

## Background

A `ProbeRequestQueue` (WorkManager-backed, `KEEP` deduplication policy) already exists and is wired into `OpenVpnService` (`probeQueue`) and `ServerAutoSwitcher` (`probeRequestQueue`). Probes are already sent on:

- Auto-switch timed or immediate path — in `ServerAutoSwitcher.requestSwitchNow()`
- Engine-triggered VPN_STATUS path — in `OpenVpnService.updateState()`
- Watchdog degraded-connection recovery — in `OpenVpnService.handleConnectedProbeResult()`

Two gaps remain:

1. **User-initiated disconnect** — when the user explicitly stops the VPN, no probe is sent for the server that was active.
2. **DEFAULT_V2 hydration early-return** — in `ServerAutoSwitcher.requestSwitchNow()`, when `next == null && total == 0` and DEFAULT_V2 hydration is triggered, the function returns before the probe-enqueue block, skipping the probe for the failing server.

## Acceptance Criteria

### AC-1 — User-initiated disconnect probe

**Given** the user taps Disconnect,  
**When** the DISCONNECTED state is confirmed (i.e., `finishStopFlowConfirmed` completes in `OpenVpnService`),  
**Then** `probeQueue.enqueue(serverId)` is called for the server that was active.  
If `serverId == 0` (server identity unknown), no probe is sent.

### AC-2 — DEFAULT_V2 hydration gap probe

**Given** an auto-switch fires but the server list is empty and DEFAULT_V2 on-demand hydration is about to be triggered (`requestSwitchNow` with `next == null && total == 0 && DEFAULT_V2 && callback != null`),  
**When** the function would return early for hydration,  
**Then** a hardprobe is enqueued for the failing server before that return, consistent with the probe-enqueue that runs on all other exit paths of `requestSwitchNow`.

### AC-3 — No regression

All existing probe call sites (watchdog recovery, VPN_STATUS auto-switch, `requestSwitchNow` non-hydration paths) remain unchanged and continue to function correctly.

## Out of Scope

- PAUSE/resume flows
- Changes to the probe HTTP API contract or backend
- Changes to WorkManager deduplication policy
- New probe call sites beyond AC-1 and AC-2

## Risks

- In AC-1, `SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted` is called after the engine has already been unbound — the method is synchronous and reads SharedPreferences, so this is safe but must be confirmed.
- WorkManager `KEEP` deduplication means rapid user stop + auto-switch does not double-fire for the same server; this is the desired behaviour.

## Implementation Notes

### AC-1 — `OpenVpnService.finishStopFlowConfirmed`

Location: `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`

In `finishStopFlowConfirmed`, after `ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)`, enqueue a probe:

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

### AC-2 — `ServerAutoSwitcher.requestSwitchNow`

Location: `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/ServerAutoSwitcher.kt`

Before `return` inside the DEFAULT_V2 hydration block, add the same guard that runs on all other paths:

```kotlin
if (failingServerId != 0) {
    try { probeRequestQueue?.enqueue(failingServerId) } catch (e: Exception) {
        AppLog.w(TAG, "DEFAULT_V2: failed to enqueue hardprobe for serverId=$failingServerId", e)
    }
}
```

## Test Scenarios

1. `OpenVpnService` / `finishStopFlowConfirmed` — enqueues probe when server ID is non-zero.
2. `OpenVpnService` / `finishStopFlowConfirmed` — no probe when server ID is zero or unavailable.
3. `ServerAutoSwitcher.requestSwitchNow` DEFAULT_V2 hydration path — probe enqueued before early return.
4. Existing `ServerAutoSwitcherTest` and `VpnInactivityHardprobeTriggerTest` continue to pass without modification.

## Definition of Done

- AC-1, AC-2, AC-3 all pass.
- Unit tests green (all existing tests pass; new tests cover AC-1 and AC-2 gaps).
- Code review clean with no blocking findings.
- No new Lint errors introduced.
