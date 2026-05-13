# US-06 - Build-Time URL Unification and Primary-Domain Routing

## User story

As an Android VPN client maintainer,
I want the app build to accept only one primary backend domain and one fallback CSV URL,
so that endpoint configuration is simpler, duplicated URL settings are removed, and runtime behavior across server sources, release notes, and update checks stays predictable.

## Background

### Current repository evidence

The client currently requires three build-time endpoint values in `src/core/build.gradle.kts`:

- `PRIMARY_SERVERS_URL`
- `FALLBACK_SERVERS_URL`
- `PRIMARY_SERVERS_V2_URL`

Those values are exposed as `BuildConfig` constants through `ApiConstants`.

The current runtime behavior is split across several paths:

- `UserSettingsStore.resolveServerUrls()` returns `PRIMARY_SERVERS_URL` plus `FALLBACK_SERVERS_URL` for `LEGACY`, only `FALLBACK_SERVERS_URL` for `VPNGATE`, only the custom URL for `CUSTOM`, and only `PRIMARY_SERVERS_V2_URL` for `DEFAULT_V2`.
- `ServerRepository.getServers()` already persists a source switch from `LEGACY` to `VPNGATE` when the primary CSV URL fails and the fallback CSV succeeds.
- `DefaultVersionReleaseRepository.getLatestRelease()` currently uses `settingsStore.resolveServerUrls(settings)`, which means the "What's New" feature is still coupled to the selected server source.
- `DefaultUpdateCheckRepository.checkForUpdate()` already ignores the selected server source, but it trusts both `PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` as update hosts.
- `CoreDi.kt` currently builds the Retrofit base URL for `ServersV2Api` from `ApiConstants.PRIMARY_SERVERS_V2_URL`.

Repository docs also still describe three required build-time endpoint values in `README.md` and `AGENTS.md`.

### Backend evidence

The local backend repository at `D:\Apps\OpenVPNClient\OpenVPNGateClientServer` exposes all required routes under one versioned API host:

- legacy CSV servers via `api/v1/servers/active`
- v2 servers via `api/v2/servers` and `api/v2/servers/countries/active`
- release notes via `api/v1/versions/number/{versionNumber}/build/{buildNumber}` and `api/v2/versions/number/{versionNumber}/build/{buildNumber}`
- update checks via `api/v1/versions/check-update` and `api/v2/versions/check-update`

This means the client can derive all trusted primary-domain routes from one base URL instead of carrying separate build-time inputs for v1 server CSV, v2 server API, and version/update features.

### Motivation

| Problem | Detail |
| --- | --- |
| Duplicated build settings | The app accepts multiple URLs that point to the same primary backend domain and differ only by route or API version. |
| Inconsistent trust model | Server loading, "What's New", and "Get Update" do not all derive their URLs the same way. |
| Source coupling leak | "What's New" still changes behavior when the user changes the server source, even though it is an app-release feature and not a server-list source feature. |
| Fallback ambiguity | The desired fallback chain for the primary source is not fully specified across DEFAULT_V2, Legacy CSV, and VPN Gate fallback. |
| Documentation drift | User docs, technical docs, and AI-agent guidance still describe the older three-URL configuration model. |

## Acceptance criteria

### AC-1 - Build-time URL unification

| ID | Criterion |
| --- | --- |
| AC-1.1 | The app build accepts exactly two server-endpoint inputs for runtime endpoint configuration: `PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL`. |
| AC-1.2 | `PRIMARY_SERVERS_URL` is the primary backend domain base URL only, for example `https://openvpngateclient.azurewebsites.net`, not a pre-expanded route such as `/api/v1/servers/active`. |
| AC-1.3 | The client derives trusted primary-domain routes in code from `PRIMARY_SERVERS_URL` instead of requiring `PRIMARY_SERVERS_V2_URL` or another separate primary-domain build input. |
| AC-1.4 | `FALLBACK_SERVERS_URL` remains a full CSV endpoint URL for the VPN Gate fallback list, for example `https://www.vpngate.net/api/iphone/`. |
| AC-1.5 | Build validation and local override handling continue to require HTTPS and reject placeholder hosts for both configured inputs. |
| AC-1.6 | `servers.local.json`, environment-variable lookup, Gradle property lookup, and generated `BuildConfig` values are updated to the new two-URL contract without introducing a new third trusted endpoint input for the same primary domain. |

### AC-2 - Derived route model from the primary domain

| ID | Criterion |
| --- | --- |
| AC-2.1 | Legacy CSV for `ServerSource.LEGACY` is derived from the primary domain as `{PRIMARY_SERVERS_URL}/api/v1/servers/active`. |
| AC-2.2 | DEFAULT_V2 server-country and server-list requests are derived from the primary domain as `{PRIMARY_SERVERS_URL}/api/v2/servers/countries/active` and `{PRIMARY_SERVERS_URL}/api/v2/servers...`. |
| AC-2.3 | "What's New" release-note requests use only the primary domain and derive their route from the primary base instead of the selected server source URL list. |
| AC-2.4 | "Get Update" requests use only the primary domain and derive their route from the primary base instead of `FALLBACK_SERVERS_URL`, `VPNGATE`, or `CUSTOM` source URLs. |
| AC-2.5 | Route derivation must preserve any safe shared path-prefix handling already needed by repository code, but no user-visible feature may require a dedicated `PRIMARY_SERVERS_V2_URL`-style build input after this story. |

### AC-3 - Server source behavior preservation

| ID | Criterion |
| --- | --- |
| AC-3.1 | `ServerSource.CUSTOM` keeps working as a direct CSV source exactly as before. |
| AC-3.2 | `ServerSource.VPNGATE` keeps working as a direct CSV source exactly as before, using only `FALLBACK_SERVERS_URL`. |
| AC-3.3 | `ServerSource.LEGACY` keeps working as the legacy CSV source backed by the primary domain first and the VPN Gate CSV fallback second. |
| AC-3.4 | `ServerSource.DEFAULT_V2` keeps working as the "Client for OpenVPN Gate" source backed by the primary domain v2 API. |
| AC-3.5 | Existing saved-source compatibility remains intact: already stored enum keys continue to map to the intended sources without data loss or unexpected reset. |

### AC-4 - Fallback chain for server-source switching and refresh

| ID | Criterion |
| --- | --- |
| AC-4.1 | When the app needs to load servers for the primary "Client for OpenVPN Gate" flow and the primary v2 route fails, the fallback chain is explicitly defined as: primary v2 -> Legacy CSV on the same primary domain -> `FALLBACK_SERVERS_URL`. |
| AC-4.2 | If the primary v2 route fails but Legacy CSV on the same primary domain succeeds, the app must continue with the Legacy-compatible data path instead of failing immediately. |
| AC-4.3 | If both the primary v2 route and the primary-domain Legacy CSV route fail, the app must attempt `FALLBACK_SERVERS_URL` before surfacing a load failure. |
| AC-4.4 | The fallback chain above applies to source-change-triggered sync and other shared sync entry points that currently hydrate server data for the active source. |
| AC-4.5 | Custom server failures do not fallback to any trusted or untrusted alternate source. A failing custom URL remains a failing custom URL. |
| AC-4.6 | The implementation must define and preserve one clear persistence behavior for auto-fallback source transitions. Unless repository evidence during implementation proves a stronger existing contract, the safe default is to reuse current CSV behavior and persist the working fallback source when the shared source-switch flow advances to a lower-priority trusted source. |
| AC-4.7 | Cache fallback behavior remains safe: when network requests fail and valid cache exists, the app may still use the relevant cache without violating the trusted-source order above. |

### AC-5 - "What's New" and "Get Update" independence from server source selection

| ID | Criterion |
| --- | --- |
| AC-5.1 | Changing the server source in Settings does not change the host used by "What's New". |
| AC-5.2 | Changing the server source in Settings does not change the host used by "Get Update". |
| AC-5.3 | A custom server URL never becomes a trusted host for release notes or update checks. |
| AC-5.4 | VPN Gate fallback host never becomes a trusted host for release notes or update checks. |
| AC-5.5 | Cache keys for release notes and update checks are updated as needed so that host independence from server source selection does not serve stale data from an obsolete source-derived cache key model. |

### AC-6 - Regression-safe automated coverage

| ID | Criterion |
| --- | --- |
| AC-6.1 | Unit tests cover route derivation from `PRIMARY_SERVERS_URL` for Legacy CSV, DEFAULT_V2, release notes, and update checks. |
| AC-6.2 | Unit tests cover the fallback chain `primary v2 -> Legacy CSV on primary domain -> FALLBACK_SERVERS_URL`. |
| AC-6.3 | Unit tests confirm custom server errors do not fallback. |
| AC-6.4 | Unit tests confirm "What's New" and "Get Update" remain on the primary domain regardless of selected server source or custom URL content. |
| AC-6.5 | Existing automated tests for Legacy CSV behavior, DEFAULT_V2 parity, cache handling, and update/version parsing continue to pass or are updated only where the new endpoint contract requires expectation changes. |
| AC-6.6 | Instrumented or E2E regression coverage is added or updated for both mobile and TV where the affected flows are shared or user-visible. |

### AC-7 - Documentation and AI-agent governance updates

| ID | Criterion |
| --- | --- |
| AC-7.1 | `README.md` is updated to describe the new two-URL build contract, local override examples, and release build commands. |
| AC-7.2 | `AGENTS.md` is updated so AI agents no longer assume `PRIMARY_SERVERS_V2_URL` is a required build input. |
| AC-7.3 | Relevant technical docs in `src/docs/` are updated where the old URL model or old fallback model is described. |
| AC-7.4 | Any implementation-created or newly required user-facing notes for source behavior, update behavior, or setup steps are added where repository docs expect them instead of leaving the new behavior undocumented. |
| AC-7.5 | Documentation changes remain aligned with the backend route contract validated from the local backend repository. |

## Out of scope

- Changing backend route contracts or adding new backend endpoints
- Replacing the existing VPN Gate fallback CSV source with another provider
- Adding v2 support to custom server URLs
- Redesigning the Settings UI labels or source-selection UX beyond changes strictly required by the fallback contract
- Refactoring unrelated networking, DI, or cache infrastructure outside the touched endpoint-resolution behavior

## Risks and open questions

| ID | Risk or question | Current handling |
| --- | --- | --- |
| R-1 | The current DEFAULT_V2 path uses a dedicated Retrofit base URL built from `PRIMARY_SERVERS_V2_URL`; moving to a derived route model may require refactoring how Retrofit is configured. | Keep the requirement implementation-neutral but require removal of the third build-time input and preservation of the current API contract. |
| R-2 | "What's New" currently uses `resolveServerUrls(settings)` and therefore changes behavior with the selected source. | AC-5 makes decoupling mandatory and requires cache-key review. |
| R-3 | Update checks already try both v2 and v1 version routes, but they trust both primary and fallback hosts today. | AC-2.4 and AC-5 require narrowing trusted update hosts to the primary domain only. |
| R-4 | Auto-fallback persistence semantics for DEFAULT_V2 are not fully specified in current code. | This story assumes reuse of the existing persisted-source fallback behavior unless implementation discovery finds a better established contract that should be documented explicitly. |
| R-5 | Some code currently derives version routes from a source URL that contains `/api/v1/...`; switching to a pure domain base requires safer route builders. | Covered by AC-2 and AC-6 with targeted tests. |
| R-6 | Documentation drift is broad: README, AGENTS guidance, and technical flow docs currently mention the three-URL model. | AC-7 requires a documentation audit inside the implementation scope. |

## Implementation notes

These notes are guidance for likely affected areas, not a mandated design.

### Likely affected files

- `src/core/build.gradle.kts`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/AppConstants.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/settings/UserSettingsStore.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/di/CoreDi.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerRepository.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Api.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Repository.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerSelectionSyncCoordinator.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/settings/SettingsViewModel.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/versions/VersionReleaseRepository.kt`
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/updates/UpdateCheckRepository.kt`
- `README.md`
- `AGENTS.md`
- `src/docs/server-sync-flow.md`

### Expected implementation themes

- Introduce one authoritative primary-domain helper that derives the trusted primary routes for legacy CSV, v2 servers, release notes, and update checks.
- Replace direct usage of `PRIMARY_SERVERS_V2_URL` with primary-domain route derivation.
- Keep `FALLBACK_SERVERS_URL` as an explicit full fallback CSV endpoint, not a derived route.
- Separate server-source URL resolution from trusted app-update and release-note URL resolution.
- Reuse existing shared sync and cache flows where possible instead of creating an isolated fallback implementation for one screen only.
- Keep logs compliant with `src/docs/logging-policy.md`; do not log secrets, raw credentials, or full sensitive URLs.

### Compatibility expectations

- Existing persisted server-source values must remain readable.
- Existing DEFAULT_V2 behavior introduced by US-01 and parity behavior introduced by US-02 must remain intact except where the endpoint-resolution contract is intentionally changed by this story.
- Release builds, debug builds, and local `servers.local.json` overrides must all continue to work after the configuration contract is simplified.

## Test scenarios

| ID | Scenario | Expected result |
| --- | --- | --- |
| TS-1 | Build the app with only `PRIMARY_SERVERS_URL` and `FALLBACK_SERVERS_URL` configured | Build succeeds and no third primary-domain endpoint input is required |
| TS-2 | Resolve URLs for `LEGACY` | The first request targets `{PRIMARY_SERVERS_URL}/api/v1/servers/active`; the second trusted request targets `FALLBACK_SERVERS_URL` |
| TS-3 | Resolve URLs for `DEFAULT_V2` | v2 country and server requests target the primary domain only |
| TS-4 | Fail DEFAULT_V2 primary request during a source-change sync | The shared fallback chain attempts Legacy CSV on the same primary domain before VPN Gate fallback |
| TS-5 | Fail both DEFAULT_V2 primary and primary-domain Legacy CSV requests | The app attempts `FALLBACK_SERVERS_URL` before surfacing failure |
| TS-6 | Fail a custom server request | No fallback to primary or VPN Gate occurs |
| TS-7 | Load "What's New" while `CUSTOM`, `VPNGATE`, `LEGACY`, and `DEFAULT_V2` are selected in turn | All requests stay on the primary domain |
| TS-8 | Run update check while `CUSTOM`, `VPNGATE`, `LEGACY`, and `DEFAULT_V2` are selected in turn | All requests stay on the primary domain |
| TS-9 | Use a custom server URL with a hostile or unrelated host | That host is never used for release notes or update checks |
| TS-10 | Run mobile regression for startup, source switching, refresh, "What's New", and "Get Update" | No regression in user-visible behavior; fallback order matches the story |
| TS-11 | Run TV regression for startup, source switching, refresh, "What's New", and "Get Update" | Shared behavior matches mobile where the flow is shared |
| TS-12 | Review updated docs and agent guidance after implementation | All references to the old three-URL contract are removed or deliberately explained |

## Definition of done

- Source code uses a two-URL build contract for primary domain plus fallback CSV.
- Legacy, DEFAULT_V2, VPN Gate, and Custom source behaviors match the acceptance criteria above.
- "What's New" and "Get Update" are both primary-domain-only and independent from server source selection.
- Automated tests are added or updated for route derivation, fallback order, source independence, and regressions.
- Mobile and TV E2E or instrumented regression coverage is executed with retained evidence.
- README, technical docs, and AI-agent instructions are updated for the new contract.
- The implementation keeps client behavior aligned with the validated backend route contract.