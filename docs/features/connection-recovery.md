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

The flag is cleared in exactly three places:

| Cleared when | Why |
|---|---|
| `markWatchdogHealthy` | traffic is flowing again — the chain succeeded, budget refills |
| `triggerWatchdogFailSafeDisconnect` | the chain is over; do not carry into whatever follows |
| a **user-initiated** start (`!isReconnect`) | the user's own connect is a fresh budget; auto-switch reconnects continue the chain |

So the effective contract is: **at most three watchdog recoveries until traffic actually flows**,
then `triggerWatchdogFailSafeDisconnect("attempt_limit_reached")`. A server that connects cleanly but
carries no traffic now terminates instead of being retried forever — the reconnect-storm case, which
matters most on a metered mobile connection.

Two things worth knowing before changing any of this:

- **The counter is not sticky in general.** A transition that the watchdog did not cause still resets
  it. `transitionOutsideRecovery_stillResetsAttempts` guards that distinction.
- **`ServerAutoSwitcher` still clears its own `cycleStartIndex` on `LEVEL_CONNECTED`**
  (`ServerAutoSwitcher.kt:123`), so the switcher's one-full-pass bound is per connect, not per
  session. That hole is now closed *behind* the watchdog rather than in the switcher: the fail-safe
  disconnect ends the chain before the switcher's reset matters. If the watchdog cap is ever removed,
  this reopens.

Coverage: `OpenVpnServiceWatchdogTest` — `watchdogDrivenReconnect_preservesRecoveryAttempts`,
`repeatedUnhealthyReconnects_reachAttemptLimitAndFailSafe`,
`transitionOutsideRecovery_stillResetsAttempts`, `healthyTraffic_endsCarryOverSoNextTransitionResets`.

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
| `STOP_DISPATCH_MAX_ATTEMPTS` = 3 | bounded retry |

`finishStopFlowConfirmed()` clears the pending state once the engine confirms. If the attempts are
exhausted the app surfaces `ConnectionStateManager.VpnError.STOP_FAILED` rather than pretending the
tunnel is down — the one case where the UI must not show `DISCONNECTED`.

During teardown the state manager holds `DISCONNECTING` sticky while the engine reports
`NOPROCESS`, `EXITING` or `DISCONNECTED`, so the UI does not flicker through an intermediate state.
See [vpn-connection.md](vpn-connection.md).

## How the three interact

- The watchdog runs **only** in `CONNECTED`, after warmup.
- A watchdog failure may trigger auto-switch if the setting is on; otherwise it drives recovery
  attempts against the same server.
- A user-initiated stop takes precedence: the stop flow is what persists across process death, and a
  pending stop is reconciled on next start.
- **Every path is bounded, but by a different thing each time.** The stop path: 3 dispatch attempts,
  then `STOP_FAILED`. The auto-switcher: one full pass over the country's servers when they fail to
  *connect*. The watchdog: 3 recoveries until traffic actually flows, carried across its own
  reconnects, then a fail-safe disconnect. The watchdog's is the bound that covers servers which
  connect cleanly and carry nothing — the case the other two miss.

*Last verified against: `vpn/OpenVpnService.kt` watchdog and stop constants, `vpn/ServerAutoSwitcher.kt`, `vpn/ConnectionState.kt`, `vpn/OpenVpnServiceWatchdogTest.kt` (2026-08-01).*
