# Server List Synchronization Flow

## Scope
This document describes how server-list synchronization is orchestrated around the shared main UI (speedometer and connection controls) for both mobile and TV launchers.

## Main Details Display Contract
The shared connection details surface uses a split contract:
- `Server` value: selected server position as `current/total` in the selected country list for all sources.
- `Address` value: city + UTC for `DEFAULT_V2` when city metadata is available, city only when UTC is missing, and selected server IP for non-`DEFAULT_V2` sources or when city metadata is unavailable.

The `Server` value applies to `DEFAULT_V2`, `LEGACY`, `VPNGATE`, and `CUSTOM`.
The `Address` value is source-aware and only switches to city/UTC formatting for `DEFAULT_V2`.

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

## Hardprobe Trigger Points

When a VPN disconnect or inactivity event fires, the following code paths can enqueue a hardprobe request via `ProbeRequestQueue`:

1. **Autoswitch timeout / immediate switch** (`ServerAutoSwitcher.requestSwitchNow()`): the failing server ID is captured *before* `nextServerCircular()` rotates to the next server. If the VPN status level is `LEVEL_NONETWORK` the ID is forced to 0 (no probe enqueued). If the ID is non-zero, `probeRequestQueue?.enqueue(failingServerId)` is called for the server that stalled. Added in SUB-04.

2. **Watchdog recovery** (`OpenVpnService.handleConnectedProbeResult()`): before watchdog recovery starts, `SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)` is called and a probe is enqueued if the ID is non-zero. The guard matches by server IP to prevent spurious probes when selection changed while connected. Added in SUB-04.

3. **User-initiated disconnect** (`OpenVpnService.finishStopFlowConfirmed()`): when the user explicitly taps Disconnect and the engine confirms the terminal level, `SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)` is called to obtain the active server ID and `probeQueue?.enqueue(serverId)` is called. Added in US-12.

4. **DEFAULT_V2 hydration early-return** (`ServerAutoSwitcher.requestSwitchNow()`): when an auto-switch fires but the server list is empty and DEFAULT_V2 on-demand hydration is triggered, a probe is enqueued for the failing server before the early return, consistent with all other exit paths of `requestSwitchNow`. Added in US-12.

5. **VPN_STATUS engine auto-switch** (`OpenVpnService.updateState()`): when the engine signals a failure level through the `VPN_STATUS` path and `userInitiatedStart=true`, `probeQueue?.enqueue(vpnStatusFailingServerId)` is called for the failing server before the switch. This is the primary probe trigger in fast-failing scenarios. Added in SUB-04.

In all paths, a server ID of 0 silently suppresses probe enqueue (covers both `LEVEL_NONETWORK` device-loss events and legacy CSV servers that have no integer ID from the v2 API). `ProbeRequestQueue` uses WorkManager `KEEP` deduplication, so rapid re-enqueue for the same server ID does not double-fire. It is wired by Koin in `OpenVpnService.onCreate()` and cleared in `onDestroy()`; `ServerAutoSwitcher.probeRequestQueue` is set from the same Koin instance at that time.

See [android-qa-adb-cookbook.md](android-qa-adb-cookbook.md) for logcat filters and device commands useful when verifying these trigger points.