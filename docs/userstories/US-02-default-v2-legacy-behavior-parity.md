# US-02 - DEFAULT_V2 Legacy Behavior Parity

## User story

As an Android VPN client user,
I want the DEFAULT_V2 source to behave like the Legacy CSV source during startup, server switching, and automatic refresh,
so that moving to JSON lazy loading does not change how the app chooses an initial server, rotates to the next server, or keeps the selected country data usable over time.

## Background

US-01 introduced the DEFAULT_V2 source, country-first lazy loading, and per-country server loading from API v2.

Repository discovery shows that the Legacy CSV flow depends on a shared set of core behaviors:

- `MainSelectionInteractor` bootstraps an initial usable selection before the main screen needs a current server.
- `SelectedCountryStore` is the runtime source of truth for the selected country, the current server index, and the last started / last successful config.
- `ServerAutoSwitcher` rotates inside the stored selected-country server list by moving the index in `SelectedCountryStore`.
- `ServerSelectionSyncCoordinator` and `SelectedCountryServerSync` keep the selected-country store aligned after foreground syncs, periodic refresh, and settings-triggered refresh.

At the moment, DEFAULT_V2 has the lazy-loading foundation from US-01, but parity with the Legacy lifecycle is not yet specified. Without explicit parity requirements, the app can load countries successfully while still diverging from Legacy behavior in these areas:

- the first usable server selection when no stored selection exists
- auto-switch behavior when the selected country list has not yet been hydrated in the current app session
- automatic refresh behavior that should keep the selected-country store aligned without requiring the user to reopen the country screen

This story defines the missing parity requirements while preserving the v2 data contract and the lazy-loading model from US-01.

## Acceptance criteria

### AC-1 - Initial selection parity for DEFAULT_V2

| ID | Criterion |
| --- | --- |
| AC-1.1 | When `ServerSource.DEFAULT_V2` is active and there is no usable current selection in `SelectedCountryStore`, app startup must materialize a usable selected-country server list before the main screen depends on a current server. |
| AC-1.2 | The initial DEFAULT_V2 selection must follow the same user-visible behavior as Legacy bootstrap: the app must end startup with a selected country, a selected server, a non-empty config, and a stable current index in `SelectedCountryStore`. |
| AC-1.3 | On a clean state, DEFAULT_V2 must select the first available country from the cached or freshly fetched country list, fetch that country's servers, store them through the shared selected-country flow, and make the first stored server the active default selection. |
| AC-1.4 | If `SelectedCountryStore` already contains a valid current server for the active country, DEFAULT_V2 startup must reuse that stored selection instead of replacing it with the first country / first server. |
| AC-1.5 | The user must be able to connect from the main screen after startup with DEFAULT_V2 without first opening the country screen manually. |

### AC-2 - Selected-country store parity for DEFAULT_V2

| ID | Criterion |
| --- | --- |
| AC-2.1 | Every DEFAULT_V2 code path that resolves a country into servers must store those servers in `SelectedCountryStore` with enough data for the shared VPN and auto-switch flows to work unchanged, including config, country code, and IP. |
| AC-2.2 | Saving DEFAULT_V2 servers for the currently selected country during refresh or rehydration must preserve the current index when the previously selected server still exists in the refreshed list. |
| AC-2.3 | If the previously selected DEFAULT_V2 server is no longer present after refresh, the app must fall back to the same shared selection semantics used by Legacy flows: no crash, no invalid index, and a deterministic current server from the refreshed stored list. |
| AC-2.4 | DEFAULT_V2 must continue to support `last started` and `last successful` config alignment through the existing shared store APIs instead of introducing a separate v2-only selection state. |

### AC-3 - Auto-switch parity for DEFAULT_V2

| ID | Criterion |
| --- | --- |
| AC-3.1 | `ServerAutoSwitcher` must keep using the same thresholds, trigger conditions, and retry flow for DEFAULT_V2 as for Legacy. |
| AC-3.2 | When auto-switch is triggered for DEFAULT_V2, the next server must be taken from the same selected-country store ordering used by Legacy, not from a separate transient list. |
| AC-3.3 | If auto-switch is triggered for DEFAULT_V2 and the selected-country server list is missing or stale for the active country, the app must attempt to hydrate or refresh that country's v2 server list through the shared selection pipeline before concluding that no next server is available. |
| AC-3.4 | Once the selected-country list is available, DEFAULT_V2 must rotate through servers with the same circular progression and exhaustion behavior as Legacy. |
| AC-3.5 | DEFAULT_V2 auto-switch must not require the user to reopen the country screen after app start, after refresh, or after a reconnect attempt in order to continue rotating servers within the selected country. |

### AC-4 - Automatic refresh parity for DEFAULT_V2

| ID | Criterion |
| --- | --- |
| AC-4.1 | All existing automatic sync trigger points that apply to Legacy must still execute when DEFAULT_V2 is active, including splash preload, main foreground sync, periodic background refresh, server source switch, and custom URL update. |
| AC-4.2 | For DEFAULT_V2, automatic sync must keep the country cache current and must also refresh the currently selected country's server list whenever the shared Legacy flow would have refreshed selected-country data after a sync. |
| AC-4.3 | After a DEFAULT_V2 refresh of the currently selected country, the app must update the selected-country store through the shared alignment path so that the main screen, reconnect flow, and auto-switcher observe refreshed data without requiring manual country re-entry. |
| AC-4.4 | If a DEFAULT_V2 refresh cannot reach the network but relevant v2 cache exists, the app must continue to use the cached countries and cached selected-country servers without surfacing a new parity regression relative to Legacy cache behavior. |
| AC-4.5 | DEFAULT_V2 refresh behavior must respect the existing `cacheOnly`, `forceRefresh`, and source-transition cache clearing rules used by the shared synchronization flow. |
| AC-4.6 | When a sync is triggered with `forceRefresh=true` (periodic worker, source-switch, or forced manual refresh), the flag must be propagated to `ServersV2Repository.getServersForCountry()` for the currently selected country, not only to `getCountries()`. Updating the country list while leaving a stale per-country server cache in place is not sufficient. |
| AC-4.7 | After DEFAULT_V2 post-refresh alignment writes updated servers to `SelectedCountryStore`, `SelectedCountryVersionSignal` must be bumped so that `MainViewModel` and the main screen observe the refreshed selection without requiring an app restart or manual action. |
| AC-4.8 | If the per-country server cache TTL has expired at the time of a foreground or periodic sync, the alignment path must attempt a network re-fetch for the active country rather than silently reusing stale cached data. |

### AC-5 - Shared behavior parity for main and TV surfaces

| ID | Criterion |
| --- | --- |
| AC-5.1 | The parity requirements in AC-1 through AC-4 apply to the shared core flow and therefore must hold for both mobile and TV launchers unless a launcher-specific limitation is explicitly documented during implementation. |
| AC-5.2 | No Legacy CSV source behavior may regress while adding DEFAULT_V2 parity. |

### AC-6 - Observability and regression safety

| ID | Criterion |
| --- | --- |
| AC-6.1 | Logs may distinguish DEFAULT_V2 parity flows for diagnosis, but they must follow the existing logging policy and must not log secrets or raw sensitive config data. |
| AC-6.2 | Automated tests must cover the DEFAULT_V2 parity cases for initial selection, selected-country refresh alignment, and auto-switch readiness. |
| AC-6.3 | Existing Legacy CSV tests for startup selection, refresh, and server switching must continue to pass unchanged or with only expectation updates that are strictly required by the new DEFAULT_V2 scope. |

## Out of scope

- Changing the API v2 contract, pagination rules, or backend sort order
- Replacing lazy loading with a full upfront DEFAULT_V2 server download for all countries
- Changing the Legacy CSV behavior, auto-switch thresholds, or refresh debounce policy
- Adding a new user-facing settings toggle for parity behavior
- Redesigning the country or server list UI

## Risks and open questions

| ID | Risk or question | Current handling |
| --- | --- | --- |
| R-1 | DEFAULT_V2 country data is available at splash, but selected-country servers may still be absent when startup, reconnect, or auto-switch needs them. | Covered by AC-1 and AC-3: the client must hydrate the selected-country list through the shared selection flow before treating the state as unavailable. |
| R-2 | Legacy selected-country refresh alignment is implemented through `SelectedCountryServerSync`, which is CSV-specific today. | Implementation must introduce a v2-equivalent alignment path or extend the shared coordinator without changing Legacy behavior. |
| R-3 | The currently selected country is stored by country name, while v2 refresh and lazy fetches use `countryCode`. | Implementation should resolve the active country code from cached countries and define a safe fallback when the code cannot be resolved from fresh data. |
| R-4 | API v2 response order defines the first server and server rotation order for DEFAULT_V2 parity. | This story assumes the backend response order is the authoritative order for initial selection and circular switching unless repository evidence changes during implementation. |
| R-5 | A selected country may disappear or return zero valid servers after refresh. | Implementation should keep behavior deterministic and safe: no crash, no invalid index, and a fallback to the first valid refreshed server or the existing safe empty-state behavior when none exist. |
| R-6 | `SelectedCountryVersionSignal.bump()` is called inside `saveSelectionPreservingIndex()` but not inside `saveSelection()`. A v2 alignment path that calls the wrong save variant will silently skip the signal and leave the main screen stale. | AC-4.7 explicitly requires the bump; implementation must verify which save variant is used and add an explicit bump if needed. |

## Implementation notes

These notes are guidance for likely implementation surfaces, not a mandatory design.

### Likely affected areas

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainSelectionInteractor.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/CountryServersInteractor.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerSelectionSyncCoordinator.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/SelectedCountryServerSync.kt` or a shared / parallel v2 alignment component
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Repository.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2SyncCoordinator.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/refresh/ServerRefreshWorker.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/ServerAutoSwitcher.kt`

### Design intent

- Keep `SelectedCountryStore` as the shared runtime source of truth for current-country server rotation.
- Preserve the lazy-loading model from US-01: preload countries at splash, but fetch servers only for the active country when they are needed for initial selection, refresh alignment, or server switching.
- Prefer extending the shared synchronization flow over adding parallel one-off refresh logic in UI layers.
- Keep DEFAULT_V2 behavior aligned with Legacy by reusing the same post-refresh selection alignment semantics where feasible.

## Test scenarios

### Automated tests

| ID | Scenario |
| --- | --- |
| TS-1 | DEFAULT_V2 startup with empty store loads countries, hydrates the first country server list, saves selection, and returns a usable initial selection for the main screen. |
| TS-2 | DEFAULT_V2 startup with an already stored current server reuses the stored selection without replacing it with the first country. |
| TS-3 | DEFAULT_V2 refresh of the selected country preserves the current index when the same config and IP still exist after refresh. |
| TS-4 | DEFAULT_V2 refresh of the selected country falls back safely when the previously selected server disappears. |
| TS-5 | DEFAULT_V2 auto-switch path can obtain the next server after startup without forcing the user to reopen the country list. |
| TS-6 | DEFAULT_V2 auto-switch hydrates the selected-country list when the store is empty or stale and then continues shared circular switching behavior. |
| TS-7 | Periodic refresh and foreground sync keep DEFAULT_V2 selected-country data aligned without regressing Legacy CSV tests. |
| TS-8 | `forceRefresh=true` sync re-fetches both the country list and the currently selected country's server list from the network, not from the stale per-country cache. |
| TS-9 | After DEFAULT_V2 alignment writes refreshed servers, `SelectedCountryVersionSignal` version is incremented exactly once so that observers receive the update. |

### Manual QA focus

| ID | Scenario |
| --- | --- |
| MQ-1 | Fresh install with DEFAULT_V2 selected: the main screen shows a connect-ready server after startup without manually opening the country list. |
| MQ-2 | Connect with DEFAULT_V2, force an auto-switch condition, and verify the app rotates to the next server in the same selected country. |
| MQ-3 | Leave the app, return through foreground sync, and verify the selected country remains connect-ready under DEFAULT_V2. |
| MQ-4 | Trigger periodic or forced refresh with DEFAULT_V2 and verify the selected-country store remains usable for reconnect without reopening the country list. |
| MQ-5 | Verify the same parity flows on TV for the shared core behavior. |

## Definition of done

- All acceptance criteria are implemented for `ServerSource.DEFAULT_V2`.
- Shared startup, refresh, and auto-switch flows behave the same as Legacy from the user's perspective while keeping v2 lazy loading.
- Automated tests cover the new parity behaviors and Legacy regression coverage remains green.
- Manual QA confirms the DEFAULT_V2 source remains connect-ready after startup and after automatic refresh paths on the supported launcher surfaces.
- No new logging-policy violations or source-regression issues are introduced.