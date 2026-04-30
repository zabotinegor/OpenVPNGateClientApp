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
| Background UI update (via signal) | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModel.kt` init + `onStoreVersionChanged()` | Cache-only load after `SelectedCountryVersionSignal.version` bump; no network sync |
| Server source switch in settings | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/settings/SettingsViewModel.kt` | `forceRefresh=true`, `clearCacheBeforeRefresh=true` |
| Custom server URL update in settings | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/settings/SettingsViewModel.kt` | `forceRefresh=true`, `clearCacheBeforeRefresh=false` |

## Decision Conditions
- `cacheOnly` uses `ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(...)`.
- `forceRefresh` bypasses fresh-cache short-circuit in repository fetch logic.
- `clearCacheBeforeRefresh` is used for server-source transitions to avoid stale cross-source reuse.
- Main foreground sync is debounced in `MainViewModel` to avoid duplicate work around lifecycle transitions.

## Signal-Driven Cache Refresh Pattern
When `ServerRefreshWorker` completes a background periodic refresh, it bumps `SelectedCountryVersionSignal.version` to notify the UI layer of cache updates without forcing a network call. This pattern ensures the main UI stays fresh without blocking foreground interaction.

**How it works:**
1. Background worker (`ServerRefreshWorker`) completes sync via `ServerSelectionSyncCoordinator.sync(...)`
2. Worker bumps `SelectedCountryVersionSignal.version` (via `SelectedCountryServerSync.syncAfterRefresh(...)`)
3. `MainViewModel.init()` observes version bumps with `drop(1)` (skips initial value)
4. Signal emission triggers `onStoreVersionChanged()` callback
5. `onStoreVersionChanged()` implements double-guard race condition protection:
   - Early guard: Skip if user selection is pending (do not overwrite user choice)
   - Cache-only load: Fetch updated selection from cache (no network call)
   - Late guard: Skip state update if user selection became pending during load
6. Selected server UI updates with new data from cache (country, countryCode, IP, config)

**Race condition safety:**
- If user selects a server while background refresh is in progress, `pendingUserSelectionOverride=true` blocks both early and late
- Cache load completes after flag is set, but late guard prevents applying stale data
- User selection takes precedence; background refresh does not overwrite it

**Testing:**
- Use `SelectedCountryVersionSignal.bump()` in unit tests to simulate background sync completion
- `BlockingMainSelectionInteractor` pattern allows testing race conditions between signal and user selection
- See `MainViewModelTest` for `store version bump` tests covering guard conditions and concurrent scenarios


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
