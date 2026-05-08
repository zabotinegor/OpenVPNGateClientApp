# US-03 - DEFAULT_V2 Countries Deserialization and Fallback Stability

## User story

As an Android VPN client user,
I want DEFAULT_V2 country loading to remain stable in minified and non-minified builds,
so that startup, sync, and country selection do not fail when parsing country responses and the app still handles offline/cache conditions safely.

## Background

US-01 and US-02 introduced and aligned DEFAULT_V2 lazy-loading behavior. Current runtime evidence shows a crash/fatal error path during country fetch with this chain:

- getCountries: network failed and no cache available
- root cause in Gson/Retrofit deserialization (Unable to invoke no-args constructor and Abstract class can't be instantiated)
- obfuscated stack traces (r8-map-id), indicating release/minified execution paths are in scope

Repository discovery confirms:

- Countries are loaded through ServersV2Api.getCountries() and cached through ServersV2Repository.getCountries().
- DefaultServersV2SyncCoordinator and DefaultMainSelectionInteractor depend on this path for startup/refresh readiness.
- Mobile and TV release variants keep isMinifyEnabled=true, so parity must hold under shrinking/obfuscation.
- Current unit tests cover normal parsing and cache fallback for countries, but no explicit regression test targets deserialization incompatibility under malformed/shape-mismatch payloads or converter/model drift.

This story defines robustness and regression-safety requirements for the DEFAULT_V2 countries fetch path without changing the backend contract.

## Acceptance criteria

### AC-1 - Countries deserialization robustness

| ID | Criterion |
| --- | --- |
| AC-1.1 | ServersV2Api.getCountries() response parsing must use a concrete, deterministic model contract that cannot attempt to instantiate abstract types during runtime parsing. |
| AC-1.2 | DEFAULT_V2 country parsing must succeed for the expected backend response shape used by /api/v2/servers/countries/active. |
| AC-1.3 | If country response parsing fails for any reason, the error must be reported through existing logging policy with actionable context and no sensitive payload logging. |
| AC-1.4 | The implementation must remain compatible with minified release builds for both mobile and TV launchers. |

### AC-2 - Failure handling parity and app stability

| ID | Criterion |
| --- | --- |
| AC-2.1 | On countries fetch/parse failure with a valid countries cache present, DEFAULT_V2 must continue using cache and avoid a user-visible crash regression. |
| AC-2.2 | On countries fetch/parse failure with no cache present, the app must fail gracefully through existing safe error paths and must not produce an unrecoverable crash loop. |
| AC-2.3 | syncCountries() and syncSelectedCountryServers() failure paths must preserve current non-crashing behavior contracts for callers (startup, foreground sync, worker sync, settings-triggered sync). |
| AC-2.4 | Legacy CSV sources and their startup/sync behavior must remain unchanged. |

### AC-3 - Contract and DI alignment

| ID | Criterion |
| --- | --- |
| AC-3.1 | Retrofit converter and model declarations used for DEFAULT_V2 countries must be aligned with each other and with the backend contract. |
| AC-3.2 | Any required keep/shrinker guidance for v2 response models must be explicitly captured in existing proguard/r8 configuration locations, limited to the minimal required scope. |
| AC-3.3 | The solution must avoid introducing source-specific duplicate parsing logic in UI layers; parsing responsibility remains in repository/API layer boundaries. |

### AC-4 - Regression safety and observability

| ID | Criterion |
| --- | --- |
| AC-4.1 | Automated tests must include at least one failing-before/failing-now scenario proving countries fetch stability across parse failure and cache fallback cases. |
| AC-4.2 | Existing ServersV2RepositoryTest and ServersV2SyncCoordinatorTest expectations for cache and sync behavior must remain valid or be updated only where required by this fix. |
| AC-4.3 | Logs for this flow must remain compliant with src/docs/logging-policy.md and must not include secrets, raw credentials, or full sensitive URLs. |

## Out of scope

- Changing backend API payload contracts or route semantics
- Redesigning country/server list UI flows
- Reworking Legacy CSV repository behavior
- Broad refactoring of unrelated networking infrastructure
- OpenVPN engine module changes

## Risks and open questions

| ID | Risk or question | Current handling |
| --- | --- | --- |
| R-1 | Runtime error appears in obfuscated stack traces, making class mapping ambiguous. | Use repository-level concrete contracts and targeted regression tests to validate behavior independent of symbol names. |
| R-2 | Shrinker changes can unintentionally affect model metadata or reflection-dependent parsing. | Keep rules, if needed, must be minimal and covered by release-variant validation. |
| R-3 | Overly broad exception handling could hide actionable root causes. | Preserve existing graceful behavior but include focused diagnostics at repository/sync boundaries. |
| R-4 | Fallback semantics may diverge between startup and sync callers. | Validate both MainSelectionInteractor and ServersV2SyncCoordinator call paths in tests/manual QA. |

## Implementation notes

These notes are guidance for likely implementation surfaces, not a mandatory design.

### Likely affected areas

- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Api.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Repository.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2SyncCoordinator.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainSelectionInteractor.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/di/CoreDi.kt
- src/core/proguard-rules.pro and launcher proguard rules only if required by verified shrinker behavior
- src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2RepositoryTest.kt
- src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2SyncCoordinatorTest.kt

### Design intent

- Keep API/repository as the single source of parsing truth for DEFAULT_V2 country data.
- Keep cache fallback behavior deterministic and aligned with US-01/US-02 parity goals.
- Prefer minimal, test-backed fixes over broad networking refactors.

## Test scenarios

### Automated tests

| ID | Scenario |
| --- | --- |
| TS-1 | Countries fetch succeeds with expected /countries/active payload and returns a non-empty CountryV2 list. |
| TS-2 | Countries fetch parse failure with existing cache returns cached countries and does not crash caller flows. |
| TS-3 | Countries fetch parse failure with no cache throws controlled IO failure that callers handle without fatal app crash loop. |
| TS-4 | Sync coordinator (syncCountries, syncSelectedCountryServers) logs and handles countries fetch failure consistently with graceful behavior expectations. |
| TS-5 | Legacy source regression tests remain green after the fix. |

### Manual QA focus

| ID | Scenario |
| --- | --- |
| MQ-1 | Start app with DEFAULT_V2 on a clean state in debug and release-like/minified build; verify country loading and main-screen readiness do not crash. |
| MQ-2 | Simulate no-network with an existing countries cache and verify app remains usable with cached country data. |
| MQ-3 | Simulate no-network with no countries cache and verify app shows safe failure behavior without repetitive fatal crash at startup. |
| MQ-4 | Run the same sanity path on TV launcher build when Leanback target is available. |

## Definition of done

- All acceptance criteria are implemented and validated.
- DEFAULT_V2 countries loading no longer reproduces the abstract-class Gson instantiation failure in supported build variants.
- Automated tests are added/updated for deserialization and fallback regression safety.
- Existing parity behavior from US-01 and US-02 remains intact for startup/sync paths.
- Manual QA confirms no crash regression in the specified scenarios.