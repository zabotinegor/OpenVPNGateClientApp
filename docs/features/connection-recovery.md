# Connection recovery: watchdog, auto-switch, and stop

Three mechanisms that keep a session healthy or end it cleanly. They are separate, they can interact,
and all three are bounded — none retries forever.

## Index

- [Connected-state watchdog](#connected-state-watchdog)
- [Auto-switch within country](#auto-switch-within-country)
- [Stop and teardown](#stop-and-teardown)
- [How the three interact](#how-the-three-interact)

---

## Connected-state watchdog

Once the engine reports `CONNECTED`, `OpenVpnService` polls for evidence that traffic is actually
flowing. A tunnel can be "up" and carrying nothing.

| Constant | Value | Meaning |
|---|---|---|
| `WATCHDOG_CONNECTED_WARMUP_MS` | 10 000 | grace period after `CONNECTED` before judging anything |
| `WATCHDOG_POLL_INTERVAL_MS` | 2 000 | poll cadence |
| `WATCHDOG_MIN_TRAFFIC_DELTA_BYTES` | 256 | below this between polls counts as no traffic |
| `WATCHDOG_FAILURE_THRESHOLD` | 3 | consecutive failed polls before acting |
| `WATCHDOG_RECOVERY_COOLDOWN_MS` | 15 000 | minimum gap between recovery attempts |
| `WATCHDOG_MAX_RECOVERY_ATTEMPTS` | 3 | recoveries **until traffic flows again**, then fail-safe disconnect — see below |
| `WATCHDOG_DEFAULT_OPENVPN_PORT` | 1194 | assumed port when probing |
| `WATCHDOG_FALLBACK_HTTPS_PORT` | 443 | second probe port, for networks that only permit 443 |

`handleConnectedProbeResult()` interprets each probe. The warmup exists because a freshly established
tunnel legitimately carries no traffic for a moment; without it every connect would trip the
threshold.

**The attempt cap survives the watchdog's own reconnects — and that is deliberate.**
`trafficPollRunnable` replaces `watchdogState` wholesale on **any** connection-state transition, and
a recovery attempt reconnects, so it triggers that very transition. Left alone, the watchdog would
reset its own budget every time it spent some of it.

`watchdogRecoveryInFlight` closes that loop. It is set when a recovery is dispatched, and while it
holds, `recoveryAttempts` is carried across the transition:

```kotlin
val carriedRecoveryAttempts =
    if (watchdogRecoveryInFlight) watchdogState.recoveryAttempts else 0
// ... resetHealthWatchdog(...) replaces watchdogState ...
watchdogState.recoveryAttempts = carriedRecoveryAttempts
```

**Only the count is carried.** Timing fields are not, so each reconnected tunnel gets a fresh warmup
grace period and a clear cooldown — the budget persists, the per-attempt patience does not.

**A successful probe is not a successful recovery.** `markWatchdogHealthy` has two callers meaning
different things: real traffic (`evaluateConnectedHealth`) and a TCP probe that merely proves the peer
answers (`handleConnectedProbeResult`). Both clear the failure streak; only the traffic-verified one
refills the budget, via the `trafficVerified` parameter. A server that answers probes while passing no
data is precisely what the bound exists to stop, so letting reachability reset it would have made the
bound decorative.

The flag is cleared in exactly three places:

| Cleared when | Why |
|---|---|
| `markWatchdogHealthy` with **verified traffic** | the chain succeeded, budget refills |
| `triggerWatchdogFailSafeDisconnect` | the chain is over; do not carry into whatever follows |
| a **user-initiated** start (`!isReconnect`) | the user's own connect is a fresh budget; auto-switch reconnects continue the chain |

So the effective contract is: **at most three watchdog recoveries until traffic actually flows**,
then `triggerWatchdogFailSafeDisconnect("attempt_limit_reached")`. A server that connects cleanly but
carries no traffic now terminates instead of being retried forever — the reconnect-storm case, which
matters most on a metered mobile connection.

**When nothing is actually dispatched, it fails safe at once.** The watchdog recovers only through
`ServerAutoSwitcher.beginChainedSwitch`, which **returns whether a switch was really begun**. It
returns `false` on three separate paths, and all of them used to look like success from outside:

| Path | What happens inside |
|---|---|
| `autoSwitchWithinCountry` is off | logs and returns without touching the tunnel |
| `VpnManager.stopVpn` rejects the stop | `cancel(resetCycle = true)`, clears the reconnect hint |
| requesting the stop throws | same cleanup, via the catch |

`watchdogRecoveryStarter` passes that value straight through, and a `false` triggers
`recovery_unavailable` immediately — the same treatment a missing recovery target already gets.
Otherwise the watchdog would spend three cycles "attempting" recoveries that never happened, log them
as real, and disconnect anyway.

Note the shape of that fix: the outcome is reported by **the function that knows it**, rather than
guessed at by an availability check beforehand. An earlier version asked
`isChainedSwitchAvailable(ctx)` before dispatching, which covered only the first row of that table.

Turning auto-switch off means *do not move me to another server*; it does not mean *leave me on a
dead tunnel showing a VPN icon*.

Two things worth knowing before changing any of this:

- **The counter is not sticky in general.** A transition that the watchdog did not cause still resets
  it. `transitionOutsideRecovery_stillResetsAttempts` guards that distinction.
- **`ServerAutoSwitcher` still clears its own `cycleStartIndex` on `LEVEL_CONNECTED`**, so the switcher's one-full-pass bound is per connect, not per
  session. That hole is now closed *behind* the watchdog rather than in the switcher: the fail-safe
  disconnect ends the chain before the switcher's reset matters. If the watchdog cap is ever removed,
  this reopens.

Coverage: `OpenVpnServiceWatchdogTest` — `watchdogDrivenReconnect_preservesRecoveryAttempts`,
`repeatedUnhealthyReconnects_reachAttemptLimitAndFailSafe`,
`transitionOutsideRecovery_stillResetsAttempts`, `healthyTraffic_endsCarryOverSoNextTransitionResets`,
`probeOnlySuccess_clearsFailureStreakButKeepsRecoveryBudget`, `autoSwitchDisabled_failsSafeInsteadOfConsumingBudget`;
plus `ServerAutoSwitcherTest.beginChainedSwitch_returnsFalseWhenAutoSwitchDisabled` and
`beginChainedSwitch_returnsFalseWhenStopDispatchRejected` for the reporting contract itself.

## Auto-switch within country

`vpn/ServerAutoSwitcher.kt` detects a *stalled* connection and rotates to another server in the same
country rather than failing outright.

- **On by default.** `autoSwitchWithinCountry` defaults to `true`, and
  `UserSettingsStore.load()` reads it with `getBoolean(KEY_AUTO_SWITCH_WITHIN_COUNTRY, true)`. A user
  must turn it off, not on.
- Stall threshold comes from `status_stall_timeout_seconds` (default 5, minimum 1) — see
  [../reference/settings-keys.md](../reference/settings-keys.md).
- `requestSwitchNow()` triggers a switch; `nextServerCircular()` picks the next candidate.
- It holds a `probeRequestQueue` from the same Koin instance the service uses, set in
  `OpenVpnService.onCreate()` and cleared in `onDestroy()`.

**It stops after one full cycle — it does not loop.** `ServerAutoSwitcher` records
`cycleStartIndex` on the first switch of a run, and `nextServerCircular(ctx, startIndex)` returns
`null` as soon as the next index would equal that start. On `null` the switcher logs
"completed full server cycle", restores the starting index via
`SelectedCountryStore.setCurrentIndex`, cancels with `resetCycle = true`, clears the reconnect hint,
sets `ConnectionState.DISCONNECTED` and stops the engine.

So a country whose servers are all unreachable produces exactly one pass over the list and then a
clean disconnect at the original selection. That is the termination invariant to test against; the
watchdog's attempt cap is a separate bound on a different path.

One exception: for `DEFAULT_V2` with an empty store, a `null` next server first triggers on-demand
hydration and re-evaluates, rather than concluding there is no alternative.

## Stop and teardown

Stopping is not a single call. The engine lives in another process and can fail to acknowledge, so
the stop intent is **persisted** and retried.

| Constant / key | Purpose |
|---|---|
| prefs `vpn_stop_teardown` | survives process death |
| `PREF_PENDING_STOP_INTENT` | the stop that has not yet been confirmed |
| `PREF_STOP_FAILURE_COUNT` | consecutive dispatch failures |
| `PREF_STOP_STALE_RECONCILE_COUNT` | reconciliations of a stale pending stop |
| `STOP_DISPATCH_MAX_ATTEMPTS` = 3 | bounded management-stop dispatches |
| `STOP_CONFIRMATION_TIMEOUT_MS` = 8 s | how long a dispatched stop waits for `LEVEL_NOTCONNECTED` |
| `FORCE_KILL_GRACE_MS` = 2 s (engine) | SIGTERM-to-SIGKILL delay inside the engine's `forceStopVPN()` |

**Dispatch is not confirmation.** A `stopVPN` call returning true only means the request was sent.
The stop completes only when the engine reports `LEVEL_NOTCONNECTED` (`finishStopFlowConfirmed()`).
Each user stop request then follows this escalation:

1. **Management stop.** Up to `STOP_DISPATCH_MAX_ATTEMPTS` dispatches over the management channel.
2. **Immediate escalation.** A dispatch that returns false while the engine still reports
   `LEVEL_CONNECTED` or `LEVEL_VPNPAUSED` (an orphaned control plane) escalates at once, because
   retrying the same call cannot succeed. Exhausting the attempts also escalates.
3. **Force stop, once per stop request.** The app calls the engine's AIDL method `forceStopVPN()`,
   which does not use the management channel. The engine sends SIGTERM to the exact openvpn process
   objects it tracks in its process-wide registry, then SIGKILLs any of them still alive after 2 s.
   Result codes: `0` no session, `1` termination requested, `2` unsupported or session still
   starting (the in-process core is only interrupted). Every code is followed by waiting for
   `LEVEL_NOTCONNECTED`. None of them is treated as a completed stop.
4. **Bounded confirmation.** If no `LEVEL_NOTCONNECTED` arrives within `STOP_CONFIRMATION_TIMEOUT_MS`,
   a timeout before the force stop goes back to step 1, or to step 2 once the attempts are exhausted,
   and a timeout after the force stop surfaces
   `ConnectionStateManager.VpnError.STOP_FAILED`. `STOP_FAILED` is the one case where the UI must not
   show `DISCONNECTED`. The controller notification stays, and the user can retry Stop. A repeated
   stop request while force-stop confirmation is in flight is coalesced rather than started again.

A session that a server switch replaces is marked in the engine before it is signalled, so its exit
does not report `LEVEL_NOTCONNECTED` to the app.

During teardown the state manager holds `DISCONNECTING` sticky while the engine reports
`NOPROCESS`, `EXITING` or `DISCONNECTED`, so the UI does not flicker through an intermediate state.
See [vpn-connection.md](vpn-connection.md).

## How the three interact

- The watchdog runs **only** in `CONNECTED`, after warmup.
- A watchdog failure recovers **through the auto-switcher**. There is no same-server recovery path:
  if `autoSwitchWithinCountry` is off, `beginChainedSwitch` returns without acting, so the watchdog
  has no mechanism at all and fails safe immediately (`recovery_unavailable`) rather than counting
  attempts that never happened.
- A user-initiated stop takes precedence: the stop flow is what persists across process death, and a
  pending stop is reconciled on next start.
- **Every path is bounded, but by a different thing each time.** The stop path: 3 management dispatch
  attempts, one force stop, then `STOP_FAILED`. The auto-switcher: one full pass over the country's
  servers when they fail to *connect*. The watchdog: 3 recoveries until traffic actually flows, carried across its own
  reconnects, then a fail-safe disconnect. The watchdog's is the bound that covers servers which
  connect cleanly and carry nothing — the case the other two miss.

*Last verified against: `vpn/OpenVpnService.kt` watchdog and stop constants, `vpn/ServerAutoSwitcher.kt`, `vpn/ConnectionState.kt`, `vpn/OpenVpnServiceWatchdogTest.kt` (2026-08-01); stop escalation and `forceStopVPN` against engine `ca9d925b` (2026-10-16).*
