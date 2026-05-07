# US-01 — Country/Server Lazy Loading and New "Client for OpenVPN Gate" Source (API v2)

## User story

**As** an Android client user,  
**I want** the country list to load quickly on startup and servers for a country to be fetched only when I select it,  
**so that** the first screen opens without unnecessary data and I can choose the new "Client for OpenVPN Gate" source backed by the current JSON API v2 instead of the legacy CSV.

---

## Background

### Current behavior

On startup the app downloads the **entire** server list as a single CSV file via `ServerRepository.getServers()`. Countries and servers share one cache; when a country is selected, `CountryServersInteractor.getServersForCountry()` filters from that cache and `loadConfigs()` fetches VPN configs separately.

The "Client for OpenVPN Gate" source (`ServerSource.DEFAULT`) points to `PRIMARY_SERVERS_URL` (CSV, own backend) with an auto-fallback to `FALLBACK_SERVERS_URL` (VPN Gate CSV).

### What is already implemented on the server (US-05)

The backend exposes a clean JSON API v2:

| Route | Returns |
|-------|---------|
| `GET /api/v2/servers/countries/active` | `[ { code, name, serverCount } ]` |
| `GET /api/v2/servers?countryCode=XX&isActive=true&skip=0&take=N` | `{ "items": [ { ip, countryCode, countryName, configData } ], "total": N }` (take ≤ 50) |

`configData` is a Base64-encoded OpenVPN config embedded directly in the server list response, so a separate `loadConfigs()` call is no longer needed.

### Motivation

| Problem | Detail |
|---------|--------|
| Monolithic load | The entire CSV list is fetched on startup even if the user never changes country |
| Outdated default source | CSV v1 is a legacy format; the v2 JSON API is already production-ready |
| Naming confusion | The "Client for OpenVPN Gate" menu item currently points to the CSV source even though the backend of the same name supports JSON v2 |

---

## Acceptance criteria

### AC-1 — New "Client for OpenVPN Gate" source (v2)

| ID | Criterion |
|----|-----------|
| AC-1.1 | A new entry **"Client for OpenVPN Gate"** appears in the server source settings |
| AC-1.2 | When selected, the app calls `GET /api/v2/servers/countries/active` to fetch the country list |
| AC-1.3 | When a country is selected, the app calls `GET /api/v2/servers?countryCode=XX&isActive=true` |
| AC-1.4 | v2 API responses are parsed into `CountryV2` and `ServerV2` client models; `ip`, `countryCode`, `countryName`, `configData` are required fields |
| AC-1.5 | The v2 source is independent of `PRIMARY_SERVERS_URL` / `FALLBACK_SERVERS_URL`; the URL is taken from the new Gradle property `PRIMARY_SERVERS_V2_URL` or a `BuildConfig` constant |

### AC-2 — Legacy source rename

| ID | Criterion |
|----|-----------|
| AC-2.1 | The existing "Client for OpenVPN Gate" entry is renamed to **"Client for OpenVPN Gate (Legacy)"** in all locales (`en`, `ru`, `pl`) |
| AC-2.2 | Legacy source behavior is unchanged: CSV v1 via `PRIMARY_SERVERS_URL` + fallback |
| AC-2.3 | Users who had `DEFAULT` saved in SharedPreferences continue to see the Legacy source (backward compatibility) |
| AC-2.4 | Fresh installs default to the new v2 source (`DEFAULT_V2`) |

### AC-3 — Country lazy loading (v2 source)

| ID | Criterion |
|----|-----------|
| AC-3.1 | With the v2 source, only **countries** (`/countries/active`) are loaded on the splash screen — not all servers |
| AC-3.2 | The country list shows `serverCount` next to the country name (e.g., "Japan · 14") |
| AC-3.3 | The splash timeout (12 s) applies only to country loading; exceeding it does not block navigation |

### AC-4 — Per-country server lazy loading (v2 source)

| ID | Criterion |
|----|-----------|
| AC-4.1 | The server list for a country is loaded **only when that country is selected**, not on startup |
| AC-4.2 | Server loading for a country is done via `/api/v2/servers?countryCode=XX&isActive=true` |
| AC-4.3 | If the response contains ≤ 50 servers, one request is made; if `serverCount > 50`, the client paginates until all servers are fetched |
| AC-4.4 | Fetched servers are saved to `SelectedCountryStore` via `saveSelection()` so the VPN service and auto-switcher continue to work unchanged |
| AC-4.5 | With the v2 source, `loadConfigs()` is not called — `configData` is taken from the `/servers` response |

### AC-5 — v2 caching

| ID | Criterion |
|----|-----------|
| AC-5.1 | The country list is cached with the same TTL mechanism as the current server cache |
| AC-5.2 | Servers per country are cached separately from countries (cache key includes `countryCode`) |
| AC-5.3 | If the v2 API is unavailable and a cache exists, the app uses the cache without surfacing an error |

### AC-6 — Legacy sources unaffected

| ID | Criterion |
|----|-----------|
| AC-6.1 | `LEGACY` (former `DEFAULT`), `VPNGATE`, and `CUSTOM` sources load servers through the existing CSV path (full load on startup) |
| AC-6.2 | No existing CSV-flow tests break |

### AC-7 — TV module

| ID | Criterion |
|----|-----------|
| AC-7.1 | The source settings screen shows both the new and the renamed entry in the TV UI |
| AC-7.2 | v2 lazy loading works on TV the same as on mobile |

---

## Out of scope

- Server-side API v2 — already implemented (US-05); the client adapts to the ready contract
- UI pagination of the server list (scroll-to-load) — only a full per-country fetch in one or more requests
- `CUSTOM` source — does not gain v2 support (remains CSV)
- Offline mode and background sync — behavior unchanged from current
- Auto-switch logic — unchanged; `SelectedCountryStore.getServers()` remains the entry point

---

## Risks and open questions

| # | Risk / question | Resolution |
|---|-----------------|------------|
| R1 | Pagination: countries with > 50 servers require multiple requests | Covered by AC-4.3; implement `while (fetched < serverCount)` before saving to Store |
| R2 | `configData` is required for VPN connection; null/empty field on any server would break connection | Server guarantees non-null for active servers (US-05, AC-1.4); client must filter servers with empty `configData` and log a warning |
| R3 | SharedPreferences migration: `DEFAULT` stored for existing users | Rename enum value `DEFAULT` → `LEGACY`; add migration guard in `UserSettingsStore.load()`: if `"DEFAULT"` is read, return `ServerSource.LEGACY` |
| R4 | `PRIMARY_SERVERS_V2_URL` not yet defined in `src/build.gradle.kts` | Add Gradle property following the pattern of `PRIMARY_SERVERS_URL`; debug builds may use `servers.local.json` |
| R5 | Splash timeout with v2: country loading is faster but may exceed 12 s on slow networks | Not critical — cache fallback applies; timeout does not need changing |
| R6 | TV: source settings screen may require Leanback-specific changes | Assess during implementation; open a sub-task if needed |

---

## Implementation notes

> These are developer guidance notes, not requirements.

### New components in `src/core`

| Component | Purpose |
|-----------|---------|
| `ServersV2Api` (Retrofit interface) | `GET /api/v2/servers/countries/active` and `GET /api/v2/servers` |
| `CountryV2` / `ServerV2` (data classes) | Domain models for v2; separate from `Server` / `Country` (legacy) |
| `ServersV2Repository` | Fetch + cache countries and per-country servers; v2 counterpart of `ServerRepository` |
| `ServersV2SyncCoordinator` | Coordinates splash country loading and on-demand server loading per country |

### Changes to existing components

| File | Change |
|------|--------|
| `UserSettingsStore.kt` | Add `DEFAULT_V2` to `ServerSource`; rename `DEFAULT` → `LEGACY`; add migration guard in `load()` for the `"DEFAULT"` key |
| `UserSettingsStore.resolveServerUrls()` | Add `DEFAULT_V2` branch — returns `listOf(ApiConstants.PRIMARY_SERVERS_V2_URL)` (URL used for v2 API calls and the "What's New" page) |
| `SplashServerPreloadInteractor` | For `DEFAULT_V2`: call `ServersV2SyncCoordinator.syncCountries()`; for all others: existing logic |
| `CountryServersInteractor` | For `DEFAULT_V2`: call `ServersV2Repository.getServersForCountry(countryCode)` instead of filtering from CSV |
| `ServerListViewModel` | For `DEFAULT_V2`: subscribe to `CountryV2` list instead of grouping `Server` by country |
| `SettingsActivity` / `content_settings.xml` | Add `serverDefaultV2` radio button; rename `serverDefault` to the legacy label |
| `strings.xml` (en, ru, pl) | `settings_server_default` → `"Client for OpenVPN Gate (Legacy)"`; add `settings_server_default_v2` = `"Client for OpenVPN Gate"` |
| `src/build.gradle.kts` | Add `PRIMARY_SERVERS_V2_URL` Gradle property and expose as `BuildConfig.PRIMARY_SERVERS_V2_URL` |
| `CoreDi.kt` | Register all new v2 components |

### Key v2 flow

```
Splash:
  ServersV2SyncCoordinator.syncCountries()
    → GET /api/v2/servers/countries/active
    → cache countries (CountryV2[])

ServerListScreen:
  ServerListViewModel -> ServersV2Repository.getCachedCountries()
    → display country list with serverCount

CountrySelected:
  CountryServersInteractor.getServersForCountry(countryCode)
    → GET /api/v2/servers?countryCode=XX&isActive=true (all pages)
    → SelectedCountryStore.saveSelection(countryName, servers)

VPNConnect (unchanged):
  SelectedCountryStore.currentServer() → ip + configData
```

### Approximate files touched

- `src/core/.../settings/UserSettingsStore.kt`
- `src/core/.../servers/ServerRepository.kt` (add new v2 repository alongside or within)
- `src/core/.../servers/CountryServersInteractor.kt`
- `src/core/.../servers/ServerSelectionSyncCoordinator.kt`
- `src/core/.../ui/splash/SplashServerPreloadInteractor.kt`
- `src/core/.../ui/serverlist/ServerListViewModel.kt`
- `src/core/.../ui/settings/SettingsActivity.kt`
- `src/core/src/main/res/values/strings.xml` (+ ru, pl)
- `src/core/src/main/res/layout/content_settings.xml`
- `src/core/build.gradle.kts`
- `CoreDi.kt`

---

## Test plan

Coverage is split across three levels: **unit** (Robolectric + pure Kotlin, `src/core/src/test`), **component/UI** (Espresso/ActivityScenario, `src/mobile/src/androidTest`), and **manual E2E**. The Legacy CSV regression block is validated at unit level (existing tests) and via manual E2E.

---

### Unit tests (Robolectric / pure Kotlin)

Location: `src/core/src/test/.../`

#### UT-1 — `UserSettingsStoreTest` (extend existing)

| ID | Test |
|----|------|
| UT-1.1 | `load_legacy_migration` — SharedPrefs contains `"DEFAULT"`; `load()` returns `ServerSource.LEGACY` |
| UT-1.2 | `load_default_v2` — SharedPrefs contains `"DEFAULT_V2"`; `load()` returns `ServerSource.DEFAULT_V2` |
| UT-1.3 | `load_unknown_key_falls_back_to_legacy` — unknown string → `ServerSource.LEGACY` |
| UT-1.4 | `save_and_load_roundtrip_default_v2` — save `DEFAULT_V2`, reload → `DEFAULT_V2` |
| UT-1.5 | `resolveServerUrls_default_v2_returns_v2_url` — `DEFAULT_V2` → `listOf(PRIMARY_SERVERS_V2_URL)` |
| UT-1.6 | `resolveServerUrls_legacy_returns_primary_and_fallback` — `LEGACY` → list of two URLs |

#### UT-2 — `ServersV2RepositoryTest` (new file)

Uses the `SequenceApi` style analogous to `ServerRepositoryTest`.

| ID | Test |
|----|------|
| UT-2.1 | `getCountries_success` — API returns JSON `[{code,name,serverCount}]`; result parsed into `CountryV2[]` |
| UT-2.2 | `getCountries_caches_result` — second call without force-refresh makes no network request |
| UT-2.3 | `getCountries_cache_expired` — TTL elapsed; new request is made |
| UT-2.4 | `getCountries_api_failure_returns_cache` — API fails; cache exists → returns cache |
| UT-2.5 | `getCountries_api_failure_no_cache_throws` — API fails; no cache → throws exception |
| UT-2.6 | `getServersForCountry_single_page` — serverCount ≤ 50; exactly one HTTP request |
| UT-2.7 | `getServersForCountry_multi_page` — serverCount > 50; all pages fetched until list is complete |
| UT-2.8 | `getServersForCountry_filters_empty_configData` — servers with empty `configData` excluded from result |
| UT-2.9 | `getServersForCountry_caches_by_country_code` — JP and DE caches are independent |
| UT-2.10 | `getServersForCountry_cache_expired` — TTL elapsed → new request |
| UT-2.11 | `getServersForCountry_concurrent_requests_one_http_call` — two concurrent calls for the same country → mutex ensures one HTTP request |

#### UT-3 — `ServersV2SyncCoordinatorTest` (new file)

| ID | Test |
|----|------|
| UT-3.1 | `syncCountries_delegates_to_repository_with_forceRefresh_false` — with `cacheOnly=false, forceRefresh=false` repository is called correctly |
| UT-3.2 | `syncCountries_cacheOnly_does_not_call_network` — `cacheOnly=true` → repository called with `cacheOnly` |
| UT-3.3 | `syncCountries_propagates_repository_exception` — repository throws; exception is propagated |

#### UT-4 — `SplashServerPreloadInteractorTest` (extend existing)

| ID | Test |
|----|------|
| UT-4.1 | (existing) `preload_servers_delegates_to_sync_coordinator_with_expected_flags` |
| UT-4.2 | `preload_v2_delegates_to_v2_coordinator_sync_countries` — with `DEFAULT_V2`, `ServersV2SyncCoordinator.syncCountries()` is called instead of `ServerSelectionSyncCoordinator.sync()` |
| UT-4.3 | `preload_v2_cacheOnly_passed_through` — `cacheOnly` flag is forwarded to the v2 coordinator |

#### UT-5 — `CountryServersInteractorTest` (extend existing)

| ID | Test |
|----|------|
| UT-5.1 | (existing CSV-path tests — do not modify) |
| UT-5.2 | `getServersForCountry_v2_calls_v2_repository` — with `DEFAULT_V2`, `ServersV2Repository.getServersForCountry(countryCode)` is called; CSV repository is not called |
| UT-5.3 | `getServersForCountry_v2_saves_to_selected_country_store` — v2 result is saved via `SelectedCountryStore.saveSelection()` |
| UT-5.4 | `getServersForCountry_v2_configData_taken_from_server` — `configData` comes from the v2 response; `loadConfigs()` is not called |
| UT-5.5 | `getServersForCountry_v2_empty_result_throws_io_exception` — no servers returned → IOException with a descriptive message |

#### UT-6 — `ServerListViewModelTest` (extend existing)

| ID | Test |
|----|------|
| UT-6.1 | (existing tests — do not modify) |
| UT-6.2 | `init_v2_source_emits_country_list_with_server_count` — ViewModel with `DEFAULT_V2` interactor emits `CountryV2[]` with `serverCount` |
| UT-6.3 | `init_v2_source_load_error_emits_snackbar` — country load error → `ServerListEffect.ShowSnackbar` |

---

### Component / UI tests (Espresso, `src/mobile/src/androidTest`)

All tests run via `./gradlew connectedDebugAndroidTestApp`.

#### CT-1 — `SettingsActivityDeviceTest` (extend existing)

| ID | Test |
|----|------|
| CT-1.1 | (existing cache TTL / status timer tests — do not modify) |
| CT-1.2 | `serverSource_default_v2_radio_visible` — settings screen contains the "Client for OpenVPN Gate" (v2) radio button |
| CT-1.3 | `serverSource_legacy_label_correct` — Legacy radio button displays "Client for OpenVPN Gate (Legacy)" |
| CT-1.4 | `serverSource_new_install_selects_default_v2` — without previously saved settings, `DEFAULT_V2` is selected |
| CT-1.5 | `serverSource_migration_from_DEFAULT_shows_legacy_selected` — SharedPrefs with `"DEFAULT"` → Legacy is selected in UI |
| CT-1.6 | `serverSource_select_v2_saves_DEFAULT_V2_to_prefs` — tapping v2 radio → SharedPrefs stores `"DEFAULT_V2"` |
| CT-1.7 | `serverSource_select_legacy_saves_LEGACY_to_prefs` — tapping Legacy radio → SharedPrefs stores `"LEGACY"` |

#### CT-2 — `ServerListActivityDeviceTest` (new file)

| ID | Test |
|----|------|
| CT-2.1 | `country_list_shows_server_count_badge` — with v2 source, each country row shows `serverCount` (e.g., "Japan · 14") |
| CT-2.2 | `country_list_empty_shows_error_state` — API unavailable, no cache → error state is shown |
| CT-2.3 | `country_list_loads_from_cache_when_offline` — cache present, network unavailable → list is shown without error |

> **Note:** CT-2 requires a fake `ServersV2Api` via Koin test override or DI replacement in `setUp()`.
> Follow the `ActivityScenario.launch` + `InstrumentationRegistry` pattern used in `SettingsActivityDeviceTest`.

---

### Manual E2E — new v2 flow

Run on a physical Android device or emulator with real network access.
Save a screenshot for each step under `artifacts/manual-qa/<date>-v2-lazy-loading/`.

| # | Step | Expected result |
|---|------|----------------|
| ME-1 | Fresh install. Open Settings → Server source section | All four entries visible: **"Client for OpenVPN Gate"** (selected), "Client for OpenVPN Gate (Legacy)", "VPN Gate", "Custom server" |
| ME-2 | Close Settings. Restart the app (or navigate to the main screen) | Splash completes; country list is shown with server counts next to each country |
| ME-3 | Confirm no CSV is loaded on startup — check Logcat | No `"Fetching servers"` log entries for Legacy/CSV URLs; `/api/v2/servers/countries/active` is present in Logcat |
| ME-4 | Select a country with ≥ 1 server | Server list screen opens; servers appear (after network delay); no `JSONException` in Logcat |
| ME-5 | Confirm in Logcat that the server request was made **after** country selection | `/api/v2/servers?countryCode=XX` log entry appears after the tap, not before |
| ME-6 | Connect to VPN. Verify the connection works | VPN status shows Connected; internet traffic routes through VPN |
| ME-7 | Disconnect. Switch to a different country | Server list for the new country loads; reconnection succeeds |
| ME-8 | Enable Airplane mode (no network). Restart the app | Countries load from cache; no error; list is displayed |
| ME-9 | In Airplane mode: open a country | Servers load from the country cache; no error (if cache is present) |
| ME-10 | Re-enable network. Switch source to "Client for OpenVPN Gate (Legacy)" in Settings | Forced refresh; country list rebuilt from CSV; servers for the country come from CSV |
| ME-11 | Switch back to "Client for OpenVPN Gate" (v2) | Countries and servers reload from the v2 API |
| ME-12 | TV: repeat ME-1 on Android TV / Leanback emulator | Both entries are displayed; source selection works |

---

### E2E regression — Legacy CSV flow

Goal: confirm that the introduced changes do not break the existing CSV path.
Run on a physical device or emulator.

> **Precondition:** For upgrade testing, validate R-1 and R-2 on a device with the previous version already installed and settings saved. For a clean install, start from R-3.

| # | Step | Expected result |
|---|------|----------------|
| R-1 | Device with previous version (source = "Client for OpenVPN Gate", `DEFAULT`) — install the new build | Source displayed as **"Client for OpenVPN Gate (Legacy)"**; server data preserved |
| R-2 | Without changing the source: navigate to the country list | List loads from CSV (Legacy); countries shown without `serverCount` |
| R-3 | Fresh install. Select "Client for OpenVPN Gate (Legacy)" in Settings | Selection saved; splash loads CSV |
| R-4 | Select a country | Country's servers filtered from CSV; server screen opens |
| R-5 | Connect to VPN via a Legacy server | VPN status shows Connected |
| R-6 | Force-refresh the list (Refresh button) | CSV reloaded; country list updated |
| R-7 | Switch to "VPN Gate" in Settings | List loads from fallback URL (VPN Gate CSV) |
| R-8 | Switch to "Custom server", enter a valid CSV URL | List loads from the specified URL |
| R-9 | Enter an invalid custom URL | Load error shown; cache used if available |
| R-10 | Auto-switch: connect via Legacy, wait for a connection failure | Auto-switcher moves to the next server in the country without issues |

---

### Unit regression — existing CSV-flow green tests

The following test classes **must not break** after implementation. Validated with `./gradlew testDebugUnitTestApp`:

- `ServerRepositoryTest` (all tests)
- `ServerSelectionSyncCoordinatorTest` (all tests)
- `SelectedCountryServerSyncTest` (all tests)
- `ServerAutoSwitcherTest` (all tests)
- `SplashServerPreloadInteractorTest` → UT-4.1 (legacy path)
- `ServerListViewModelTest` → legacy path tests (UT-6.1)
- `CountryServersViewModelTest` (all tests)

---

## Definition of done

### Code and configuration
- [x] `ServerSource`: `DEFAULT` → `LEGACY`; `DEFAULT_V2` added; migration guard in `load()` maps stored `"DEFAULT"` → `LEGACY`
- [x] Gradle property `PRIMARY_SERVERS_V2_URL` defined in `src/build.gradle.kts` and exposed as `BuildConfig.PRIMARY_SERVERS_V2_URL`
- [x] `ServersV2Api`, `CountryV2`, `ServerV2`, `ServersV2Repository`, `ServersV2SyncCoordinator` implemented
- [x] Splash loads only countries when the source is `DEFAULT_V2`
- [x] `CountryServersInteractor` lazy-loads servers via `ServersV2Repository` for `DEFAULT_V2`
- [x] `loadConfigs()` is not called with v2; `configData` is taken from the API response
- [x] Settings UI shows both entries with correct labels in all three locales (`en`, `ru`, `pl`)
- [x] All new v2 components registered in `CoreDi.kt`

### Tests
- [x] Unit tests UT-1 — UT-6 written and green (23 new unit tests; all passing; existing CSV-flow tests unaffected)
- [ ] Component tests CT-1 and CT-2 written and green
- [x] Existing CSV-flow unit tests (see Unit regression) still green
- [x] `./gradlew testDebugUnitTestApp assembleDebugApp` passes without errors
- [ ] `./gradlew connectedDebugAndroidTestApp` passes (CT-1, CT-2, existing device tests)

### Manual E2E
- [x] Scenarios ME-1 — ME-12 passed; screenshots and logcat saved under `artifacts/manual-qa/2026-05-07-us01-v2-lazy-loading-full/`
- [ ] Regression scenarios R-1 — R-10 passed (full upgrade/CSV regression not yet executed)

### Documentation
- [x] `src/docs/server-sync-flow.md` extended with the v2 flow section
- [ ] TV variant verified (ME-12 or a separate `connectedDebugAndroidTestTv` smoke)

---

## E2E test runs

| Date | Suite | Device | Branch / Commit | Result | Report |
|------|-------|--------|-----------------|--------|--------|
| 2026-05-07 | US-01-V2-LAZY-LOADING-CORE (ME-1–ME-12) | Xiaomi Mi 9 SE, Android 11 | feature/us-01-lazy-loading-v2-server-source @ 78db3c3 | ✅ PASS (all 8 executed cases) | `artifacts/manual-qa/2026-05-07-us01-v2-lazy-loading-full/report.md` |

### Known gaps after 2026-05-07 run

| Gap | Tracking |
|-----|---------|
| Component tests CT-1 and CT-2 not yet implemented | Open — needs Espresso test authoring |
| Full CSV-regression E2E (R-1 to R-10) not yet executed | Open — requires upgrade device or emulator snapshot |
| TV smoke (ME-12 / `connectedDebugAndroidTestTv`) not run | Open — Leanback device required |
