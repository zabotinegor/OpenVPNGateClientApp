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
- **`ServerAutoSwitcher` still clears its own cycle (`cycleVisited`) on `LEVEL_CONNECTED`**, so the switcher's one-full-pass bound is per connect, not per
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
- `requestSwitchNow()` triggers a switch; `SelectedCountryStore.nextUnvisitedServer(ctx, visited)` picks the next candidate whose identity key is not yet in the cycle's visited set.
- It holds a `probeRequestQueue` from the same Koin instance the service uses, set in
  `OpenVpnService.onCreate()` and cleared in `onDestroy()`.

**It stops after one full cycle — it does not loop.** The cycle is `ServerAutoSwitcher.cycleVisited`,
an in-memory ordered set of server identity keys (see `core/servers/ServerIdentity.kt`). Its first
element is the cycle's start server. `ensureCycle()` seeds it with the current server before the first
advance, and each candidate the switcher starts is added to it. The seed is skipped while the index is
only a parked scan cursor (a refresh removed the in-use server, see [server-sync.md](server-sync.md)):
`SelectedCountryStore.currentAttemptedServerKey()` returns `null` then, so the never-started parked
entry stays unvisited and is still tried. `nextUnvisitedServer(ctx, visited)`
walks the persisted list circularly from the current index and returns the first entry whose key is
not in the set, so it returns `null` only when every key currently in the list has been visited.
Wrap detection is by identity, not position: a background refresh that adds, removes or reorders
entries mid-cycle neither re-offers an attempted server nor ends the cycle early.

Derived identity keys are cached by the raw stored server-list JSON, so the periodic connected-state persistence path does not repeat Base64 decoding, config parsing and SHA-256 hashing on an unchanged list. The key is captured at ACTION_START and reused at success to avoid re-picking a different same-IP entry while connected. A parked index stays unattempted until its entry can be verified against the started config/endpoint.

Identity keys are `id:<id>` when the backend server id is greater than 0. Otherwise the key is
`ep:<endpoint>|<ip>`, built from the config's `proto` and `remote` lines, so a refresh that re-issues
an unchanged server's config keeps its key. A config with no readable `remote` uses
`cfg:<SHA-256 of the config>|<ip>`. A repeated key gets a `#n` ordinal suffix in list order. Repeated `ep:` keys target the same socket, so a reorder that swaps their ordinals only moves a visited mark between profiles of one server. The IP alone is
never an identity, because several servers can share one IP. The cycle is dropped on user stop, on
success, on non-reconnect levels, on exhaustion, on an aborted dispatch, and when the selected
country or pool changes. It is not persisted across process death.

On `null` the switcher logs "completed full server cycle", restores the start server by identity via
`SelectedCountryStore.setCurrentIndexByKey(...)`, cancels with `resetCycle = true`, clears the
reconnect hint, sets `ConnectionState.DISCONNECTED` and stops the engine.

Two other entry points drive the same cycle. The `OpenVpnService` `VPN_STATUS` fallback calls
`ServerAutoSwitcher.nextCycleCandidate()`, the same circular, identity-tracked selection: it wraps
past the last server, returns `null` only when every server was attempted, and then restores the start
server. Its hardprobe target (`getCurrentServerIdIfMatchingLastStarted()`) is 0 while the index is
parked, so a removed server's failure is never attributed to the parked entry. A reconnect `ACTION_START` saves its started
config with `alignIndex=false`, so it does not move the index the cycle is walking. It also leaves a parked flag set: the candidate selection already cleared it, so a flag set again means a
refresh removed that candidate before the start, and the parked entry must not be recorded as the started or
successful server.

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
- A watchdog failure recovers **through the auto-switcher**. There is no same-server recovery path:
  if `autoSwitchWithinCountry` is off, `beginChainedSwitch` returns without acting, so the watchdog
  has no mechanism at all and fails safe immediately (`recovery_unavailable`) rather than counting
  attempts that never happened.
- A user-initiated stop takes precedence: the stop flow is what persists across process death, and a
  pending stop is reconciled on next start.
- **Every path is bounded, but by a different thing each time.** The stop path: 3 dispatch attempts,
  then `STOP_FAILED`. The auto-switcher: one full pass over the country's servers when they fail to
  *connect*. The watchdog: 3 recoveries until traffic actually flows, carried across its own
  reconnects, then a fail-safe disconnect. The watchdog's is the bound that covers servers which
  connect cleanly and carry nothing — the case the other two miss.

*Last verified against: `vpn/OpenVpnService.kt` watchdog and stop constants, `vpn/ServerAutoSwitcher.kt`, `vpn/ConnectionState.kt`, `vpn/OpenVpnServiceWatchdogTest.kt` (2026-08-01).*
