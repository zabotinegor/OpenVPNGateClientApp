# VPN connection: engine integration and state machine

How this app drives the bundled OpenVPN engine, and the state model the UI reads.

## Index

- [Two processes, one AIDL boundary](#two-processes-one-aidl-boundary)
- [The integration surface](#the-integration-surface)
- [Connection states](#connection-states)
- [Allowed transitions](#allowed-transitions)
- [Engine level to app state](#engine-level-to-app-state)
- [Auxiliary state](#auxiliary-state)
- [Profile construction](#profile-construction)
- [Intent actions](#intent-actions)

---

## Two processes, one AIDL boundary

There are **two** services with similar names. Confusing them is the most common mistake here.

| | Owner | Process | Role |
|---|---|---|---|
| `vpn/OpenVpnService.kt` (this app) | app | main | **controller** — builds profiles, owns app state, drives the engine |
| `de.blinkt.openvpn.core.OpenVPNService` (engine) | submodule | `:openvpn` | the real Android `VpnService`, holds `BIND_VPN_SERVICE` |

They communicate across the process boundary via AIDL — `IOpenVPNServiceInternal`, `IServiceStatus`,
`IStatusCallbacks`. That boundary is why fields in the controller carry `@Volatile`.

Both declare `foregroundServiceType="specialUse"` with subtype `vpn`.

Because the app runs in two processes, `CoreApp.isMainProcess()` gates SSE and WorkManager
registration so they do not start twice.

## The integration surface

The boundary is **not** "one file imports the engine". Six production files in `core` do. What
actually holds is narrower and more useful:

**1. The service and lifecycle surface is confined to `vpn/OpenVpnService.kt`.** Everything that
starts, configures, binds to or tears down the engine lives there and nowhere else: `VpnProfile`,
`ConfigParser` (+`ConfigParseError`), `IOpenVPNServiceInternal`, `ProfileManager`,
`VPNLaunchHelper`, `VpnStatus`, `IServiceStatus`, `IStatusCallbacks`, `TrafficHistory`,
`StatusSnapshot`. The controller implements `VpnStatus.StateListener`, `LogListener` and
`ByteCountListener`. An import of any of these outside that file **is** a boundary violation.

**2. `ConnectionStatus` is deliberately exposed as a value type**, published by
`ConnectionStateManager.engineLevel: StateFlow<ConnectionStatus?>`. It is imported by:

| File | Why |
|---|---|
| `vpn/ConnectionState.kt` | maps engine level onto the 6-value app enum (below) |
| `vpn/ServerAutoSwitcher.kt` | per-level stall thresholds — `thresholdFor(level)` |
| `core/ui/common/components/ConnectionControlsRuntime.kt` | re-publishes `engineLevel` to the UI |
| `core/ui/common/components/ConnectionControlsPresenter.kt` | distinguishes `LEVEL_CONNECTING_NO_SERVER_REPLY_YET` from `LEVEL_CONNECTING_SERVER_REPLIED` for the countdown, and detects teardown |

This exists because the 6-value `ConnectionState` is **deliberately coarser** than the engine's
levels, and the progress UI and the stall detector both need the finer granularity. Treat it as an
accepted seam, not as drift — but note the cost: an upstream rename of a `ConnectionStatus` member
breaks presenter code, not just the service.

**3. One initialisation call.** `CoreApp` calls `GlobalPreferences.setInstance(false, false, false)`
at startup.

**The boundary that holds cleanly is the module one: `mobile` and `tv` contain zero engine
imports.** A `de.blinkt.openvpn` import appearing in a launcher module is unambiguously wrong. Inside
`core`, judge a new import against the three categories above rather than against a file count.

Engine repository, branch and update procedure: [../conventions/engine-submodule.md](../conventions/engine-submodule.md).

## Connection states

`ConnectionState` (`vpn/ConnectionState.kt`) has **six** values:

```
DISCONNECTED · CONNECTING · CONNECTED · PAUSING · PAUSED · DISCONNECTING
```

> **There is no `RESUMING` state.** Resume goes through `CONNECTING` —
> `VpnManager.resumeVpn` → `ConnectionStateManager.beginResumeTransition()` → `updateState(CONNECTING)`.
> Any doc or UI label suggesting otherwise is describing a label, not a state.

## Allowed transitions

`ConnectionStateManager` enforces these; a transition outside the set is rejected.

| From | May go to |
|---|---|
| `DISCONNECTED` | CONNECTING, CONNECTED, PAUSED, DISCONNECTING |
| `CONNECTING` | CONNECTED, PAUSED, DISCONNECTING, DISCONNECTED |
| `CONNECTED` | CONNECTING, PAUSING, PAUSED, DISCONNECTING, DISCONNECTED |
| `PAUSING` | CONNECTING, PAUSED, DISCONNECTING, DISCONNECTED |
| `PAUSED` | CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED |
| `DISCONNECTING` | CONNECTING, CONNECTED, DISCONNECTED |

## Engine level to app state

`updateFromEngine` maps the engine's `ConnectionStatus` onto the app enum:

| Engine level | App state |
|---|---|
| `LEVEL_START`, `LEVEL_CONNECTING_NO_SERVER_REPLY_YET`, `LEVEL_CONNECTING_SERVER_REPLIED`, `LEVEL_WAITING_FOR_USER_INPUT` | `CONNECTING` |
| `LEVEL_CONNECTED` | `CONNECTED` |
| `LEVEL_VPNPAUSED` | `PAUSED` |
| `LEVEL_NONETWORK`, `LEVEL_NOTCONNECTED`, `LEVEL_AUTH_FAILED`, `UNKNOWN_LEVEL` | `DISCONNECTED` |

## Auxiliary state

`ConnectionStateManager` also owns, and the UI reads:

- `VpnError { NONE, AUTH, STOP_FAILED }`
- `engineLevel`, `engineDetail`, `reconnectingHint`
- `connectionStartTimeMs`, `speedMbps`, `downloadedBytes`, `uploadedBytes`
- `resumeTransitionInFlight` — suppresses a stale `PAUSED` arriving mid-resume
- `engineTeardownDetails = {"NOPROCESS", "EXITING", "DISCONNECTED"}` — keeps `CONNECTING`/`DISCONNECTING`
  sticky while the engine tears down, so the UI does not flicker back through an intermediate state

## Profile construction

On connect the controller parses the server's `.ovpn` config, then applies two user settings **in
this order** before starting the engine:

1. `applyAppFilter(profile)` — see [app-filter.md](app-filter.md)
2. `applyDnsSettings(profile)` — see [dns.md](dns.md)

Both are applied at connect time only; changing either while connected requires a reconnect.

## Intent actions

`VpnManager` dispatches: `start`, `stop`, `pause`, `resume`, `stop_if_idle`, `sync_status`.

Pause/resume behaviour is in [pause-resume.md](pause-resume.md); watchdog, auto-switch and the stop
retry path are in [connection-recovery.md](connection-recovery.md).

*Last verified against: `vpn/ConnectionState.kt`, `vpn/OpenVpnService.kt`, `vpn/VpnManager.kt`, `core/CoreApp.kt`, engine `AndroidManifest.xml` (2026-07-31).*
