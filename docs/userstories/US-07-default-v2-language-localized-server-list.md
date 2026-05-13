# US-07 - DEFAULT_V2 Language-Aware Server List Localization

## Chat title

Story Spec - US-07 DEFAULT_V2 language-aware server list

## User story

As an OpenVPN client user,
I want the Client for OpenVPN Gate (API v2) source to request server data with my app language,
so that country and server names shown in the UI match the selected app language.

## Start gate

Do not start work before reading .sdlc/status.json and verifying the required prior step.

## SDLC handoff metadata

- Flow status path: .sdlc/status.json
- Flow ID: dev::US-07
- Required prior step: none
- Story status update command:
  - .github/scripts/update-sdlc-status.ps1 -FlowId "dev::US-07" -Branch "dev" -Step "story" -Status "ready" -StoryId "US-07" -StoryPath "docs/userstories/US-07-default-v2-language-localized-server-list.md" -Evidence "docs/userstories/US-07-default-v2-language-localized-server-list.md"

## Background

### Current repository evidence

- `ServerSource.DEFAULT_V2` is a dedicated source in settings, while `LEGACY`, `VPNGATE`, and `CUSTOM` have their own routing path (`src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/settings/UserSettingsStore.kt`).
- API v2 calls currently do not include a locale/language query parameter:
  - `ServersV2Api.getCountries()` uses `GET api/v2/servers/countries/active` without language query.
  - `ServersV2Api.getServers(...)` uses `GET api/v2/servers` with `countryCode`, `isActive`, `skip`, `take` only (`src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Api.kt`).
- UI country list for DEFAULT_V2 uses `CountryV2.name` directly:
  - `ServerListViewModel` maps `CountryV2.name` to `Country.name`.
  - `CountryListAdapter` renders `country.country.name` in UI.
- Language selection and mapping to language codes already exists in project patterns (`SYSTEM`, `ENGLISH`, `RUSSIAN`, `POLISH`) and is used in release/update repositories for locale-aware API requests.

### Problem

DEFAULT_V2 requests are currently language-agnostic, so server list labels may not match the app language selected in Settings.

### Goal

Add language-aware request behavior for DEFAULT_V2 only, and ensure DEFAULT_V2 UI server/country labels follow app language while preserving behavior for LEGACY, VPNGATE, and CUSTOM sources.

## Acceptance criteria

### AC1 - API v2 request includes app language

- When selected source is `ServerSource.DEFAULT_V2` (Client for OpenVPN Gate), API v2 request(s) for server list data include an explicit localization query parameter derived from current app language.
- The language value is resolved from user settings using existing app language model:
  - `SYSTEM` -> current device/app locale language code (fallback `en` when blank)
  - `ENGLISH` -> `en`
  - `RUSSIAN` -> `ru`
  - `POLISH` -> `pl`
- Parameter is applied consistently to both v2 country list and v2 country-server list requests, or to the shared endpoint contract used for list rendering.

### AC2 - UI list reflects app language for DEFAULT_V2

- For `DEFAULT_V2`, country/server labels shown in server list screens are taken from API v2 localized response corresponding to current app language.
- Changing app language and reloading DEFAULT_V2 data updates shown names according to selected language.
- Sorting/grouping behavior remains stable and deterministic after localization.

### RAC3 - Non-v2 sources unchanged

- `LEGACY` source behavior remains unchanged.
- `VPNGATE` source behavior remains unchanged.
- `CUSTOM` source behavior remains unchanged.
- No new language query parameter is introduced for Legacy CSV/VPN Gate/Custom flows.

## Out of scope

- Backend contract redesign beyond using existing supported localization parameter.
- New language options beyond current app settings model.
- UI redesign unrelated to displaying localized list names.

## Risks and assumptions

- Assumption: backend API v2 supports stable localization parameter and fallback behavior for unsupported locales.
- Risk: cache keys for v2 country/server data may need locale dimension; otherwise stale labels from another language may be shown.
- Risk: switching language while cache remains warm can produce mixed-language UX if locale is not part of cache invalidation strategy.

## Implementation notes

- Keep change scope in shared core module.
- Likely touch points:
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Api.kt`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Repository.kt`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/settings/UserSettingsStore.kt` (language resolution helper reuse if needed)
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/ServerListViewModel.kt` (only if adaptation needed)
- Ensure DEFAULT_V2 cache behavior is locale-safe (either locale-aware cache key or clear refresh strategy on language switch).
- Preserve privacy/logging policy: do not log sensitive URLs; language code logging is acceptable at safe level.

## Test scenarios

- TS-1: DEFAULT_V2 + English language -> outgoing v2 request includes `en`; UI list names match English response.
- TS-2: DEFAULT_V2 + Russian language -> outgoing v2 request includes `ru`; UI list names match Russian response.
- TS-3: DEFAULT_V2 + Polish language -> outgoing v2 request includes `pl`; UI list names match Polish response.
- TS-4: DEFAULT_V2 + System language (non-empty locale) -> outgoing request uses system locale language code.
- TS-5: DEFAULT_V2 + System language resolves blank/invalid code -> fallback to `en`.
- TS-6: Switch language in Settings and refresh list -> UI reflects new language without stale old-language labels.
- TS-7: LEGACY source regression -> request/parse behavior unchanged.
- TS-8: VPNGATE source regression -> request/parse behavior unchanged.
- TS-9: CUSTOM source regression -> request/parse behavior unchanged.

## Definition of done

- AC1, AC2, RAC3 satisfied with automated tests.
- Unit tests cover language parameter mapping and source-scoped behavior.
- DEFAULT_V2 cache behavior is verified to prevent cross-language stale labels.
- No regressions in Legacy/VPNGATE/Custom flows.

## Code Implementator handoff seed

Use this story file as source of truth for implementation: `docs/userstories/US-07-default-v2-language-localized-server-list.md`.
