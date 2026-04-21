# Server List Synchronization Flow

## Scope
This document describes how server-list synchronization is orchestrated around the shared main UI (speedometer and connection controls) for both mobile and TV launchers.

## Source of Truth
Use `ServerSelectionSyncCoordinator` as the single synchronization entrypoint:
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerSelectionSyncCoordinator.kt`

The coordinator owns this flow:
1. Optional cache reset (`clearCacheBeforeRefresh=true`)
2. Server list fetch via `ServerRepository.getServers(...)`
3. Post-refresh selected-country alignment via `SelectedCountryServerSync.syncAfterRefresh(...)`

## Trigger Matrix
| Trigger | File | Mode |
| --- | --- | --- |
| Splash preload | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/splash/SplashServerPreloadInteractor.kt` | `forceRefresh=false`, `cacheOnly=feature-flag dependent`, `clearCacheBeforeRefresh=false` |
| Main foreground entry (`onStart`) | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainActivityCore.kt` -> `MainViewModel` | `forceRefresh=false`, debounced, `cacheOnly=feature-flag dependent` |
| Main initial selection load | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModel.kt` | Pre-sync before `MainSelectionInteractor.loadInitialSelection(...)` |
| Periodic background refresh | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/refresh/ServerRefreshWorker.kt` | `forceRefresh=true`, `cacheOnly=false`, `clearCacheBeforeRefresh=false` |
| Server source switch in settings | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/settings/SettingsViewModel.kt` | `forceRefresh=true`, `clearCacheBeforeRefresh=true` |
| Custom server URL update in settings | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/settings/SettingsViewModel.kt` | `forceRefresh=true`, `clearCacheBeforeRefresh=false` |

## Decision Conditions
- `cacheOnly` uses `ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(...)`.
- `forceRefresh` bypasses fresh-cache short-circuit in repository fetch logic.
- `clearCacheBeforeRefresh` is used for server-source transitions to avoid stale cross-source reuse.
- Main foreground sync is debounced in `MainViewModel` to avoid duplicate work around lifecycle transitions.

## Storage and State Impact
- Server CSV cache and metadata:
  - SharedPreferences `server_cache`
  - Files in `cacheDir` with `servers_*.csv`
- Current user server selection:
  - `SelectedCountryStore`
- Scheduler metadata:
  - WorkManager unique periodic work `server-list-periodic-refresh`

## Reuse Guidance for Agents
When adding a new place that needs server-list freshness, prefer calling `ServerSelectionSyncCoordinator.sync(...)` instead of directly calling `ServerRepository` plus manual selected-country sync.

Use direct repository access only when no selection alignment is needed.

## Known Guardrails
- Do not call network refresh from launcher-specific code when shared `MainActivityCore` can handle it.
- Keep `mobile` and `tv` launcher modules thin; place orchestration in `src/core`.
- Preserve cancellation semantics: rethrow `CancellationException` in coroutine flows.
