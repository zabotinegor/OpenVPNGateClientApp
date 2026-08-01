# Server List Synchronization Flow

## Scope

## Index

Read this list first and jump to the one relevant heading — do not read the whole file.

- [Scope](#scope)
- [Main Details Display Contract](#main-details-display-contract)
- [Source of Truth](#source-of-truth)
- [Trigger Matrix](#trigger-matrix)
- [Decision Conditions](#decision-conditions)
- [Connect-time configData Freshness](#connect-time-configdata-freshness)
- [V2 Server Source (DEFAULT_V2)](#v2-server-source-default_v2)
- [Source-Independent App Metadata Calls](#source-independent-app-metadata-calls)
- [Hardprobe Trigger Points](#hardprobe-trigger-points)
- [Foreground Service Lifecycle Guard in `syncEngineState()`](#foreground-service-lifecycle-guard-in-syncenginestate)
- [SSE Server-Push Sync (SUB-02)](#sse-server-push-sync-sub-02)

---

This document describes how server-list synchronization is orchestrated around the shared main UI (speedometer and connection controls) for both mobile and TV launchers.

## Main Details Display Contract
The shared connection details surface uses a split contract:
- `Server` value: selected server position as `current/total` in the selected country list for all sources.
- `Address` value: city + UTC for `DEFAULT_V2` when city metadata is available, city only when UTC is missing, and selected server IP for non-`DEFAULT_V2` sources or when city metadata is unavailable.

The `Server` value applies to `DEFAULT_V2` and `VPNGATE`.
The `Address` value is source-aware and only switches to city/UTC formatting for `DEFAULT_V2`.

## Source of Truth
Use `ServerSelectionSyncCoordinator` as the single synchronization entrypoint:
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerSelectionSyncCoordinator.kt`

The coordinator owns this flow:
1. Optional cache reset (`clearCacheBeforeRefresh=true`)
2. Source-aware fetch via `ServersV2SyncCoordinator` for `DEFAULT_V2` or `ServerRepository.getServers(...)` for CSV-backed sources
3. When `DEFAULT_V2` primary fetch fails, fall back **directly** to `FALLBACK_SERVERS_URL` (VPN Gate CSV) — there is no intermediate legacy-CSV step
4. Post-refresh selected-country alignment via `SelectedCountryServerSync.syncAfterRefresh(...)` for CSV-backed data

## Trigger Matrix
| Trigger | File | Mode |
| --- | --- | --- |
| Splash preload | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/splash/SplashServerPreloadInteractor.kt` | `forceRefresh=false`, `cacheOnly=feature-flag dependent`, `clearCacheBeforeRefresh=false`. For `DEFAULT_V2`, only country list is pre-fetched; server configs are loaded lazily per country. |
| Main foreground entry (`onStart`) | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainActivityCore.kt` -> `MainViewModel` | `forceRefresh=false`, debounced, `cacheOnly=feature-flag dependent` |
| Main initial selection load | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModel.kt` | Pre-sync before `MainSelectionInteractor.loadInitialSelection(...)`. After the load completes, `updateSelectedServer(...)` is **skipped** when `pendingUserSelectionOverride=true` (user explicitly selected a server while the coroutine was in-flight). Both `loadInitialSelection()` and `syncServersForForegroundIfDue()` carry this double-guard to prevent any async startup path from clobbering an in-flight user selection. `isBackgroundRefresh=true` is also set on the sync call inside `loadInitialSelection()` to signal that this is a non-user-initiated refresh path. |
| Periodic background refresh | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/refresh/ServerRefreshWorker.kt` | `forceRefresh=true`, `cacheOnly=false`, `clearCacheBeforeRefresh=false` |
| SSE connection open (`onOpen`) | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClient.kt` | `forceRefresh=true`, `cacheOnly=false`, `clearCacheBeforeRefresh=false`. Fires immediately on every successful SSE connection open — covers foreground returns, network reconnects, and initial launch. Added in SUB-03 (SSE reconnect correctness). |
| SSE server-changed push event | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClient.kt` | `forceRefresh=true`, `cacheOnly=false`, `clearCacheBeforeRefresh=false`. Fires when the backend pushes a `servers-changed` SSE event. Events are routed through a `MutableSharedFlow` with `debounce(500 ms)` so a burst of N rapid events collapses into a single sync call (added in SUB-04). Independent of the `onOpen` sync; both may fire in rapid succession on reconnect followed by an immediate push. |
| Background UI update (via signal) | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModel.kt` init + `onStoreVersionChanged()` | Cache-only load after `SelectedCountryVersionSignal.version` bump; no network sync |
| Server source switch in settings | `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/settings/SettingsViewModel.kt` | `forceRefresh=true`, `clearCacheBeforeRefresh=true` |

## Decision Conditions
- `cacheOnly` uses `ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(...)`.
- `forceRefresh` bypasses fresh-cache short-circuit in repository fetch logic.
- `clearCacheBeforeRefresh` is used for server-source transitions to avoid stale cross-source reuse.
- Main foreground sync is debounced in `MainViewModel` to avoid duplicate work around lifecycle transitions.

## Connect-time configData Freshness

`MainConnectionInteractor.prepareStart()` decides which server config to pass to the VPN engine. When `preferUserSelection=true` (i.e. `pendingUserSelectionOverride` is set), `prepareStart()` reads `configData` from `SelectedCountryStore.currentServer()` at Connect time rather than from the `selectedServer` field in ViewModel state. This is necessary because SSE sync (or any background sync) can push a new server list from the API between user selection and the Connect tap, causing the ViewModel's cached `selectedServer.config` to become stale while `SelectedCountryStore` holds the fresh value.

## V2 Server Source (DEFAULT_V2)

### Two-Phase Lazy Load
At splash, `ServersV2SyncCoordinator` pre-fetches the country list only. Per-country server lists are fetched lazily when the user selects a country in the main screen.

### Trusted Fallback Chain
When a shared sync entrypoint runs under `DEFAULT_V2`, the app tries the primary v2 routes first. If that fetch fails, the coordinator falls back directly to `FALLBACK_SERVERS_URL` (VPN Gate CSV). Successful fallback persists `VPNGATE` as the working source, reusing the existing persisted-source behavior for CSV flows.

### Components
- `ServersV2Api` → `ServersV2Repository` → `ServersV2SyncCoordinator`
- `SplashServerPreloadInteractor` routes to the v2 path or the legacy path based on `UserSettingsStore.serverSource`.
- `CountryServersInteractor` calls `ServersV2Repository.getServersForCountry()` to drive lazy per-country loads.
- `DefaultServerSelectionSyncCoordinator` owns the `DEFAULT_V2 -> VPN Gate CSV` fallback handoff for shared sync triggers. There is no legacy-CSV hop: `ApiConstants.primaryLegacyServersUrl()` exists but has zero callers.

### Localization
- `ServersV2Repository` resolves locale from `UserSettingsStore.resolvePreferredLocale(...)` and sends it as the `locale` query on both `getCountries(...)` and `getServers(...)` v2 API calls.
- Mapping: `SYSTEM` -> runtime locale language code with `en` fallback when blank, `ENGLISH` -> `en`, `RUSSIAN` -> `ru`, `POLISH` -> `pl`.
- Locale parameterization applies only to `DEFAULT_V2`. `VPNGATE` (CSV-backed) does not use locale-parameterized queries.

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
Stale persisted values from removed sources (`"DEFAULT"`, `"LEGACY"`, `"CUSTOM"`) are silently migrated to `DEFAULT_V2` on load (see `UserSettingsStore.load()` for the authoritative migration logic). New installs default to `DEFAULT_V2`.

## Source-Independent App Metadata Calls
- Release notes (`What's New`) always use routes derived from `PRIMARY_SERVERS_URL` and do not depend on the selected server source.
- Update checks (`Get Update`) always use routes derived from `PRIMARY_SERVERS_URL`. `FALLBACK_SERVERS_URL` is never trusted as an update host.

## Hardprobe Trigger Points

**This section is the canonical, authoritative list** — `docs/guides/adb-cookbook.md` and
`docs/guides/how-to.md` both link here instead of keeping their own copy, after one of those
copies was found to have drifted stale (undercounting the trigger points). If you add or change a
trigger point, update it here only.

When a VPN disconnect or inactivity event fires, the following code paths can enqueue a hardprobe request via `ProbeRequestQueue`:

1. **Autoswitch timeout / immediate switch** (`ServerAutoSwitcher.requestSwitchNow()`): the failing server ID is captured *before* `nextServerCircular()` rotates to the next server. If the VPN status level is `LEVEL_NONETWORK` the ID is forced to 0 (no probe enqueued). If the ID is non-zero, `probeRequestQueue?.enqueue(failingServerId)` is called for the server that stalled. Added in SUB-04.

2. **Watchdog recovery** (`OpenVpnService.handleConnectedProbeResult()`): before watchdog recovery starts, `SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)` is called and a probe is enqueued if the ID is non-zero. The guard matches by server IP to prevent spurious probes when selection changed while connected. Added in SUB-04.

3. **User-initiated disconnect** (`OpenVpnService.finishStopFlowConfirmed()`): when the user explicitly taps Disconnect and the engine confirms the terminal level, `SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)` is called to obtain the active server ID and `probeQueue?.enqueue(serverId)` is called. Added in US-12.

4. **DEFAULT_V2 hydration early-return** (`ServerAutoSwitcher.requestSwitchNow()`): when an auto-switch fires but the server list is empty and DEFAULT_V2 on-demand hydration is triggered, a probe is enqueued for the failing server before the early return, consistent with all other exit paths of `requestSwitchNow`. Added in US-12.

5. **VPN_STATUS engine auto-switch** (`OpenVpnService.updateState()`): when the engine signals a failure level through the `VPN_STATUS` path and `userInitiatedStart=true`, `probeQueue?.enqueue(vpnStatusFailingServerId)` is called for the failing server before the switch. This is the primary probe trigger in fast-failing scenarios. Added in SUB-04.

In all paths, a server ID of 0 silently suppresses probe enqueue (covers both `LEVEL_NONETWORK` device-loss events and legacy CSV servers that have no integer ID from the v2 API). `ProbeRequestQueue` uses WorkManager `KEEP` deduplication, so rapid re-enqueue for the same server ID does not double-fire. It is wired by Koin in `OpenVpnService.onCreate()` and cleared in `onDestroy()`; `ServerAutoSwitcher.probeRequestQueue` is set from the same Koin instance at that time.

See [adb-cookbook.md](../guides/adb-cookbook.md) for logcat filters and device commands useful when verifying these trigger points.

## Foreground Service Lifecycle Guard in `syncEngineState()`

`OpenVpnService.syncEngineState()` is called on every engine-level callback and decides whether
to call `exitControllerForeground()` — which cancels the `startForeground()` safety net established
in `onCreate()`.

**Guard condition (as of 2026-06-25 fix):** `exitControllerForeground()` is NOT called when:

- The engine level is `LEVEL_START` — connection is being established; foreground must stay active.
- The engine level is `UNKNOWN_LEVEL` — engine not yet initialized; foreground must stay active.
- The engine level is an idle level (`LEVEL_NOTCONNECTED` or `LEVEL_NONETWORK`) **and**
  `ConnectionStateManager.reconnectingHint.value` is `true` (a chained auto-switch is pending).

In code this is expressed as:

```kotlin
val idleLevel = level == LEVEL_NOTCONNECTED || level == LEVEL_NONETWORK
val reconnectPending = idleLevel && ConnectionStateManager.reconnectingHint.value
if (controllerForegroundActive
    && level != LEVEL_START
    && level != UNKNOWN_LEVEL
    && !reconnectPending) {
    exitControllerForeground()
}
```

**Conditional idle-level guard:** `LEVEL_NOTCONNECTED` and `LEVEL_NONETWORK` only skip
`exitControllerForeground()` when `reconnectingHint=true`. During a chained auto-switch the
engine is intentionally torn down before the next server is started, so keeping the FGS
notification alive avoids reopening the Android Activity Manager's 5-second foreground-service
timer. If the user then reconnects within that window without a matching `startForeground()` call,
Android throws a `RemoteServiceException` crash.

When `reconnectingHint=false` (no reconnect pending), idle levels cause `exitControllerForeground()`
to be called normally, removing the stale "VPN connecting" notification.

**`ACTION_STOP` and `ACTION_SYNC_STATUS` are unaffected:** both handlers call
`exitControllerForeground()` directly and unconditionally on their own paths, so the
`reconnectingHint` guard does not apply to them.

## SSE Server-Push Sync (SUB-02)

`SseServerEventsClient` opens a persistent HTTP/SSE connection to `GET /api/v1/servers/events`
and calls `ServerSelectionSyncCoordinator.sync(forceRefresh=true, cacheOnly=false)` whenever
the backend pushes a `servers-changed` event. This provides near-instant cache invalidation
without polling.

### Lifecycle

- **Start**: `CoreApp.registerSseLifecycleObserver()` adds `SseServerEventsClient` as a
  `ProcessLifecycleOwner` observer after `startKoin`. On `onStart` the SSE connection loop
  begins; on `onStop` it is cancelled gracefully. The client is therefore active only while the
  app is in the foreground.
- **Backoff**: On network error or non-2xx response the client retries with exponential backoff
  starting at 5 s and capping at 5 min. The backoff counter is reset **only** in `onClosed` /
  `onFailure` via `maybeResetBackoff()`, which applies a stability-threshold guard: the counter
  is reset only when the connection was alive for at least `STABLE_CONNECTION_RESET_DELAY_MS`
  (10 s). Receiving an `onEvent` callback does **not** reset the counter (changed in SUB-03 to
  prevent a hot-reconnect loop when a degraded server sends events then drops the connection).
- **Coexistence**: The SSE path and the WorkManager periodic refresh (`ServerRefreshWorker`) are
  independent. The periodic refresh continues on its own schedule; SSE provides an additional
  faster trigger.

### OkHttp client isolation

`SseServerEventsClient` calls `okHttpClient.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()`
to produce a **per-instance child client** for the SSE connection. The shared singleton `OkHttpClient`
wired by Koin retains its default read timeout and is not mutated.

### Endpoint derivation and URL fallback (SUB-05)

`SseServerEventsClient` accepts an ordered list of candidate SSE URLs (`sseUrlsProvider`). By
default this list is built by `defaultSseUrls()`: only `PRIMARY_SERVERS_URL` is used, via
`PrimaryDomainRoutes.sseServersEventsUrl()`. `FALLBACK_SERVERS_URL` is the VPN Gate CSV URL
(`https://www.vpngate.net/api/iphone/`) and is not an SSE-capable endpoint, so it is
intentionally excluded. URLs are never hardcoded. If the primary derivation returns `null`
(e.g. build property absent), the list falls back to the local placeholder
`https://openvpnclientgate.local/api/v1/servers/events`, which always fails safely on a real
device. When the primary SSE endpoint is unreachable, the WorkManager periodic refresh
(`ServerRefreshWorker`) is the safety net.

After `urlFailureThreshold` (default 3) consecutive failures on the current URL the client
advances `currentUrlIndex` to the next candidate and resets `failuresOnCurrentUrl` to zero.
`reconnectAttempt` is intentionally **not** reset on URL rotation: exponential backoff must keep
accumulating across switches so that a complete outage (all URLs failing) eventually reaches
`MAX_BACKOFF_MS` (5 min) instead of spinning at the initial delay. The rotation is circular:
after the last URL the index wraps back to the primary.

**`onOpen` does not reset `failuresOnCurrentUrl`.** It records `openedAt` and triggers a sync,
nothing more. The counter is cleared only by `maybeResetBackoff()`, called from `onClosed`/`onFailure`
and only once the connection has stayed up for `stableConnectionResetDelayMs`. A URL that accepts the
socket and then drops immediately therefore keeps accumulating failures and still rotates away —
which is the point. `SseServerEventsClient.kt:188` carries a comment saying so; a doc claiming a
reset on open is describing the hot-reconnect loop that guard exists to prevent.

> **Edge case — `urlFailureThreshold=1`**: Setting the threshold to 1 causes the client to switch
> URLs on every single failure. The reconnect loop still applies exponential backoff per attempt,
> so the rotation does not become a tight spin. Values below 1 are rejected at construction time
> by `require(urlFailureThreshold >= 1)`.

### Koin wiring

`SseServerEventsClient` is registered as a `single { ... }` in `CoreDi.kt` (the `coreModule`).
It receives the shared `OkHttpClient` and `ServerSelectionSyncCoordinator` from Koin. The
lifecycle observer is registered in `CoreApp.onCreate()` on the main thread, after `startKoin`
completes, using `ProcessLifecycleOwner.get().lifecycle.addObserver(sseClient)`.

### Required library

`okhttp-sse` (`com.squareup.okhttp3:okhttp-sse`) must be pinned to the same version ref as the
main `okhttp` dependency in `src/gradle/libs.versions.toml` to avoid classpath conflicts. Both
use `version.ref = "square-okhttp"`.