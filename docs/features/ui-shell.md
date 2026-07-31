# UI shell: splash, main screen, and navigation

The screens the user actually spends time in, and how they are wired.

## Index

- [Splash and startup](#splash-and-startup)
- [Main screen composition](#main-screen-composition)
- [Connection controls](#connection-controls)
- [Speedometer and session counters](#speedometer-and-session-counters)
- [Navigation and the drawer](#navigation-and-the-drawer)
- [Server list screens](#server-list-screens)

---

## Splash and startup

`ui/splash/SplashActivityCore` with `SplashServerPreloadInteractor`; `mobile` and `tv` each keep a
thin `SplashActivity` wrapper.

Two things run **in parallel**: one GIF playback loop, and a server-list preload. The main screen
opens when both complete.

- If preload outlives the GIF, splash shows a loading spinner until preload finishes.
- **If preload fails, startup continues anyway** — the failure is logged as a warning and the user
  reaches the main screen. Startup is deliberately not gated on network success.

## Main screen composition

`ui/main/MainActivityCore` with `MainViewModel` and `MainContract`, split into two interactors:

| Component | Owns |
|---|---|
| `MainConnectionInteractor` | connect/disconnect/pause/resume intent dispatch |
| `MainSelectionInteractor` | selected country/server and reacting to selection changes |
| `MainLogger` | screen-scoped diagnostics |
| `UpdateCheckInteractor`, `VersionReleaseInteractor` | update prompt and What's New |

The details display is split by field and source: the `Server` field shows position as
`current/total` within the selected country for all sources; the `Address` field shows city + UTC for
`DEFAULT_V2` when geo metadata exists, city alone when UTC is missing, and the server IP otherwise.

## Connection controls

`ui/common/components/ConnectionControlsView` with `ConnectionControlsPresenter`,
`ConnectionControlsUseCase` and `ConnectionControlsRuntime`.

Control ids are **stable across every active state** — only the label/action on
`start_connection_button` and the visibility of `pause_connection_button` change. `start_connection_button`
doubles as Stop once connected. Which controls are visible in which phase is in
[pause-resume.md](pause-resume.md).

Connecting requires two runtime grants, both passed into
`MainAction.ConnectionButtonClicked(hasNotificationPermission, hasVpnPermission)` — see
[notifications.md](notifications.md).

## Speedometer and session counters

`ui/common/components/SpeedometerView` renders throughput. The underlying values —
`speedMbps`, `downloadedBytes`, `uploadedBytes`, `connectionStartTimeMs` — are owned by
`ConnectionStateManager` and fed from the engine's byte-count callbacks, not computed in the view.
See [vpn-connection.md](vpn-connection.md#auxiliary-state).

## Navigation and the drawer

Destinations are modelled by `MainDestination`. `reopenDrawerAfterReturn` restores drawer state when
returning from a pushed screen, so navigating out and back does not silently close it.

Multi-window is handled explicitly rather than assumed away.

On TV the drawer additionally runs an interaction guard against false clicks — see [tv.md](tv.md).

## Server list screens

`ui/serverlist/` — `ServerListActivity`/`ServerListViewModel` for countries and
`CountryServersActivity`/`CountryServersViewModel` for servers within one, with `CountryListAdapter`,
`ServerPickerAdapter` and `FavoriteActionDialog`.

Rows are rendered through `ui/common/components/ServerDisplayFormatter`. Latency is bucketed by
`Int.toSignalStrength()` in `servers/SignalStrength.kt`:

| `ping` (ms) | Strength |
|---|---|
| 0–99 | STRONG |
| 100–249 | MEDIUM |
| 250+ | WEAK |

`ping = 0` means *unknown / not yet measured*, not "instant" — it is the default for servers that
have never been probed.

Favourites pinning and section rendering: [favorites.md](favorites.md).

*Last verified against: `ui/splash/*`, `ui/main/*`, `ui/common/components/*`, `ui/serverlist/*`, `servers/SignalStrength.kt` (2026-07-31).*
