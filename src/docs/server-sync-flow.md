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
| Splash preload | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/splash/SplashServerPreloadInteractor.kt` | `forceRefresh=false`, `cacheOnly=feature-flag dependent`, `clearCacheBeforeRefresh=false`. For `DEFAULT_V2`, only country list is pre-fetched; server configs are loaded lazily per country. |
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

## V2 Server Source (DEFAULT_V2)

### Two-Phase Lazy Load
At splash, `ServersV2SyncCoordinator` pre-fetches the country list only. Per-country server lists are fetched lazily when the user selects a country in the main screen.

### Components
- `ServersV2Api` → `ServersV2Repository` → `ServersV2SyncCoordinator`
- `SplashServerPreloadInteractor` routes to the v2 path or the legacy path based on `UserSettingsStore.serverSource`.
- `CountryServersInteractor` calls `ServersV2Repository.getServersForCountry()` to drive lazy per-country loads.

### Cache Strategy
- Countries cached in `v2_countries.json`; timestamp stored in SharedPrefs key `servers_v2_cache` / `ts_countries`.
- Servers cached per country in `v2_servers_<code>.json`; timestamp stored as `ts_servers_<code>`.
- TTL is read from `UserSettingsStore.cacheTtlMs`.
- On network error, stale cache is returned if available.

### Pagination
Page size 50. Pages are fetched in a loop until the raw page count is less than 50 or the accumulated total meets or exceeds the declared `serverCount`.

### Filtering
Servers with empty `configData` are dropped silently before caching.

### Migration
A stored `"DEFAULT"` value in SharedPrefs is migrated to `LEGACY` on first load. New installs default to `DEFAULT_V2`.


## Signal-Driven