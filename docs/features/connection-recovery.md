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
| `WATCHDOG_MAX_RECOVERY_ATTEMPTS` | 3 | **bounded** — after this it gives up rather than looping |
| `WATCHDOG_DEFAULT_OPENVPN_PORT` | 1194 | assumed port when probing |
| `WATCHDOG_FALLBACK_HTTPS_PORT` | 443 | second probe port, for networks that only permit 443 |

`handleConnectedProbeResult()` interprets each probe. The warmup exists because a freshly established
tunnel legitimately carries no traffic for a moment; without it every connect would trip the
threshold.

The attempt cap is the important property: a permanently dead server produces at most three recovery
attempts and then a clean failure, not an infinite reconnect loop.

## Auto-switch within country

`vpn/ServerAutoSwitcher.kt` detects a *stalled* connection and rotates to another server in the same
country rather than failing outright.

- **Off by default.** Gated on the `auto_switch_within_country` setting.
- Stall threshold comes from `status_stall_timeout_seconds` (default 5, minimum 1) — see
  [../reference/settings-keys.md](../reference/settings-keys.md).
- `requestSwitchNow()` triggers a switch; `nextServerCircular()` picks the next candidate, wrapping
  around the country's list rather than stopping at the end.
- It holds a `probeRequestQueue` from the same Koin instance the service uses, set in
  `OpenVpnService.onCreate()` and cleared in `onDestroy()`.

Because selection is circular, a country whose servers are all unreachable will cycle. The bound here
is the watchdog's attempt cap and the user, not the switcher itself.

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
- Every path is bounded — 3 recovery attempts, 3 stop dispatch attempts. Nothing here retries
  indefinitely, which is deliberate: an unbounded reconnect on a metered mobile connection is worse
  than a clear failure.

*Last verified against: `vpn/OpenVpnService.kt` watchdog and stop constants, `vpn/ServerAutoSwitcher.kt`, `vpn/ConnectionState.kt` (2026-07-31).*
