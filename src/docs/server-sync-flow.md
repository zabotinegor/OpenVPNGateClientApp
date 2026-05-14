# Server List Synchronization Flow

## Scope
This document describes how server-list synchronization is orchestrated around the shared main UI (speedometer and connection controls) for both mobile and TV launchers.

## Source of Truth
Use `ServerSelectionSyncCoordinator` as the single synchronization entrypoint:
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerSelectionSyncCoordinator.kt`

The coordinator owns this flow:
1. Optional cache reset (`clearCacheBeforeRefresh=true`)
2. Source-aware fetch via `ServersV2SyncCoordinator` for `DEFAULT_V2` or `ServerRepository.getServers(...)` for CSV-backed sources
3. When `DEFAULT_V2` primary fetch fails, fallback to legacy CSV on the same primary domain, then `FALLBACK_SERVERS_URL`
4. Post-refresh selected-country alignment via `SelectedCountryServerSync.syncAfterRefresh(...)` for CSV-backed data

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

### Trusted Fallback Chain
When a shared sync entrypoint runs under `DEFAULT_V2`, the app tries the primary v2 routes first. If that fetch fails, the coordinator falls back to the legacy CSV route derived from `PRIMARY_SERVERS_URL`, and then to `FALLBACK_SERVERS_URL`. Successful fallback persists the working CSV-backed source (`LEGACY` for primary-domain CSV, `VPNGATE` for the final fallback), reusing the existing persisted-source behavior for CSV flows.

### Components
- `ServersV2Api` → `ServersV2Repository` → `ServersV2SyncCoordinator`
- `SplashServerPreloadInteractor` routes to the v2 path or the legacy path based on `UserSettingsStore.serverSource`.
- `CountryServersInteractor` calls `ServersV2Repository.getServersForCountry()` to drive lazy per-country loads.
- `DefaultServerSelectionSyncCoordinator` owns the `DEFAULT_V2 -> primary legacy CSV -> VPN Gate CSV` fallback handoff for shared sync triggers.

### Localization
- `ServersV2Repository` resolves locale from `UserSettingsStore.resolvePreferredLocale(...)` and sends it as the `locale` query on both `getCountries(...)` and `getServers(...)` v2 API calls.
- Mapping: `SYSTEM` -> runtime locale language code with `en` fallback when blank, `ENGLISH` -> `en`, `RUSSIAN` -> `ru`, `POLISH` -> `pl`.
- Locale parameterization applies only to `DEFAULT_V2`. CSV-backed sources (`LEGACY`, `VPNGATE`, `CUSTOM`) keep existing behavior.

### Selected-Country Relocalization on Language Change
- Trigger: language selection change in `SettingsViewModel` under `DEFAULT_V2` starts a forced selected-country synchronization path.
- Country matching is code-first against the refreshed localized country list to avoid stale-name mismatches after locale switch.
- Server list rewrite uses `SelectedCountryStore.saveSelectionPreservingIndex(...)` so current server identity/index is retained when available.
- Display label rewrite uses `SelectedCountryStore.updateSelectedCountryName(...)`, which bumps `SelectedCountryVersionSignal` so main UI reloads selection state in-session.
- If the previously selected server is missing in refreshed data, index selection falls back deterministically and safely.
- Non-v2 sources do not execute this relocalization path and keep existing CSV-backed behavior.

### Cache Strategy
- Countries cached per locale in `v2_countries_<locale>.json`; timestamp stored in SharedPrefs key `servers_v2_cache` / `ts_countries_<locale>`.
- Servers cached per country and locale in `v2_servers_<code>_<locale>.json`; timestamp stored as `ts_servers_<code>_<locale>`.
- TTL is read from `UserSettingsStore.cacheTtlMs`.
- On network error or parse failure (including Gson deserialization exceptions), stale cache is returned if available. If no cache exists, IOException is propagated to the caller for graceful handling. This behavior is implemented in `ServersV2Repository.getCountries()` and `ServersV2Repository.getServersForCountry()`.

### Pagination
Page size 50. Pages are fetched in a loop until the raw page count is less than 50 or the accumulated total meets or exceeds the authoritative `page.total` field from the API response (or `serverCount` as a fallback when `total=0`).

### Filtering
Servers with empty `configData` are dropped silently before caching.

### Migration
A stored `"DEFAULT"` value in SharedPrefs is migrated to `LEGACY` on first load. New installs default to `DEFAULT_V2`.

## Source-Independent App Metadata Calls
- Release notes (`What's New`) always use routes derived from `PRIMARY_SERVERS_URL` and no longer depend on the selected server source or custom CSV URL.
- Update checks (`Get Update`) always use routes derived from `PRIMARY_SERVERS_URL`. `FALLBACK_SERVERS_URL` and custom server URLs are never trusted as update hosts.