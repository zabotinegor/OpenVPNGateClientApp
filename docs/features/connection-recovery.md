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
| `WATCHDOG_MAX_RECOVERY_ATTEMPTS` | 3 | cap **per uninterrupted `CONNECTED` episode** — see below |
| `WATCHDOG_DEFAULT_OPENVPN_PORT` | 1194 | assumed port when probing |
| `WATCHDOG_FALLBACK_HTTPS_PORT` | 443 | second probe port, for networks that only permit 443 |

`handleConnectedProbeResult()` interprets each probe. The warmup exists because a freshly established
tunnel legitimately carries no traffic for a moment; without it every connect would trip the
threshold.

**The attempt cap does not bound a session.** `recoveryAttempts` lives in `watchdogState`, and `trafficPollRunnable` replaces that whole object on
**any** connection-state transition:

```kotlin
if (currentState != lastPolledState) {
    if (currentState == ConnectionState.CONNECTED) resetHealthWatchdog(nowMs = watchdogNowMs())
    else { lastPolledDatapoint = null; resetHealthWatchdog() }
    lastPolledState = currentState
}
```

A recovery attempt calls `watchdogRecoveryStarter`, which reconnects — so the state leaves
`CONNECTED`, the counter is zeroed on the next poll, and it is zeroed again on the way back in. The
counter therefore measures attempts **within one uninterrupted `CONNECTED` episode**, not within a
session.

Two consequences, both easy to get wrong:

- **A flapping tunnel is not bounded at three.** A server that reconnects successfully but never
  carries traffic produces one recovery per episode, indefinitely. Nothing accumulates across
  episodes. When QA looks for "reconnect storms", this is the mechanism that permits them.
- **`WATCHDOG_MAX_RECOVERY_ATTEMPTS` is hard to reach on the common path.** Each attempt triggers the
  state change that resets the counter, so `recoveryAttempts >= 3` — and therefore
  `triggerWatchdogFailSafeDisconnect("attempt_limit_reached")` — realistically only fires when
  recovery is dispatched *without* the connection state changing.

The genuinely bounded thing here is the **failure threshold**: three consecutive bad polls
(`WATCHDOG_FAILURE_THRESHOLD`) before any recovery is dispatched at all, plus a 15-second cooldown
between attempts. Those hold per episode and are what keeps a single bad episode from thrashing.

Whether the counter *should* survive watchdog-driven reconnects is an open product question, not a
settled one — it is recorded here as observed behaviour rather than as intended design.

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
- **The stop path is bounded outright** (3 dispatch attempts, then `STOP_FAILED`). The auto-switcher
  is bounded by one full pass over the country's servers. The watchdog is bounded only *per
  `CONNECTED` episode* — its counter resets on every state transition, so a reconnecting-but-unhealthy
  tunnel is not capped at three across a session. Do not cite the watchdog as evidence that nothing
  here retries indefinitely; it is the one path that can.

*Last verified against: `vpn/OpenVpnService.kt` watchdog and stop constants, `vpn/ServerAutoSwitcher.kt`, `vpn/ConnectionState.kt` (2026-07-31).*
