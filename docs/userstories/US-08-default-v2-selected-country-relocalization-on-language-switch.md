# US-08 - DEFAULT_V2 Selected Country Relocalization on App Language Switch

## Chat title

Story Spec - US-08 DEFAULT_V2 selected country relocalization on language switch

## User story

As an OpenVPN client user,
I want the currently selected DEFAULT_V2 country/server label to relocalize immediately after I change app language,
so that the selected server state stays consistent with the active UI language without requiring manual refresh and reselection.

## Start gate

Do not start work before reading .sdlc/status.json and verifying the required prior step.

## SDLC handoff metadata

- Flow status path: .sdlc/status.json
- Flow ID: dev::US-08
- Required prior step: none
- Story status update command:
  - .github/scripts/update-sdlc-status.ps1 -FlowId "dev::US-08" -Branch "dev" -Step "story" -Status "ready" -StoryId "US-08" -StoryPath "docs/userstories/US-08-default-v2-selected-country-relocalization-on-language-switch.md" -Evidence "docs/userstories/US-08-default-v2-selected-country-relocalization-on-language-switch.md"

## Background

### Current repository evidence

- DEFAULT_V2 country and server labels come from localized v2 payloads (`src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Api.kt`, `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Repository.kt`).
- Selected country display state is persisted as plain country name string in `SelectedCountryStore.saveSelection(country: String, ...)` and later read through `getSelectedCountry()` (`src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/SelectedCountryStore.kt`).
- DEFAULT_V2 startup path reuses stored selection when present and does not relocalize persisted country label (`src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainSelectionInteractor.kt`).
- DEFAULT_V2 country screen initialization receives country name and code independently, but selected-country storage currently keys by country name string (`src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/CountryServersViewModel.kt`, `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/CountryServersInteractor.kt`).
- Existing US-07 manual QA already validates language switch list reload behavior, but does not require immediate relocalization of already selected country label in persisted selection state (`tests/manual-e2e/stories/us-07-default-v2-language-localized-server-list/cases/us-07-mq-05-language-switch-reload.md`).

### Problem

When the user has an already selected DEFAULT_V2 country (example: Russia), changing app language does not immediately relocalize that selected country label (expected: Россия). The user currently needs a manual server list refresh and reselection to see localized selected-country label.

### Goal

Ensure selected DEFAULT_V2 country/server label updates to the new app language automatically after language change, without requiring manual refresh/reselection, while preserving existing LEGACY/VPNGATE/CUSTOM behavior.

## Acceptance criteria

### AC1 - Selected DEFAULT_V2 country label relocalizes on language change

- If source is `ServerSource.DEFAULT_V2` and a selected country/server already exists, changing app language (SYSTEM/ENGLISH/RUSSIAN/POLISH) causes selected country label shown in main flow to match new locale.
- Relocalization happens without manual country re-entry or explicit user refresh action.
- Relocalization uses stable country identity (country code or equivalent invariant key), not fragile matching by old localized country name.

### AC2 - Persisted selection remains valid and index-safe

- Existing selected server index and current server identity are preserved when relocalized data still contains the previously selected server.
- If previously selected server disappears after relocalized refresh, fallback remains deterministic and safe (no crash, no invalid index).
- Last started/last successful selection alignment remains consistent with the relocalized selected-country state.

### AC3 - Language switch triggers selection-state relocalization for DEFAULT_V2

- On app language change, DEFAULT_V2 executes a selected-country relocalization path that refreshes and re-saves selected-country metadata in the new locale.
- This path updates UI-observable selection state in the same app session (for example via existing selected-country version signaling) without requiring app reinstall or manual data clearing.
- Relocalization behavior is compatible with cache policy: it may use locale-safe cache when valid, and must avoid cross-language stale label leakage.

### AC4 - Non-v2 sources unchanged

- `ServerSource.LEGACY` behavior remains unchanged.
- `ServerSource.VPNGATE` behavior remains unchanged.
- `ServerSource.CUSTOM` behavior remains unchanged.
- No locale-driven relabeling regression is introduced for non-v2 CSV flows.

### AC5 - Regression safety and observability

- Automated tests cover at least one failing-before scenario where selected label stayed in old locale after language switch, and prove fixed behavior.
- Existing US-07 tests for locale query/caching remain green or are updated only where required by this story.
- Logs remain compliant with `src/docs/logging-policy.md` and do not include secrets or sensitive URLs.

## Out of scope

- Backend API contract redesign.
- New language options outside SYSTEM/ENGLISH/RUSSIAN/POLISH.
- UI redesign unrelated to selected-country relocalization.
- Broad refactor of all selection persistence beyond changes needed for reliable locale-safe selected-country identity.

## Risks and assumptions

- Assumption: backend v2 continues to provide stable country code identity across locales.
- Risk: if selected-country identity remains name-based in some paths, relocalization may stay partial.
- Risk: forcing fresh selected-country synchronization on every language switch can increase network load; solution should balance cache usage and correctness.
- Risk: relocalization can accidentally reset selected index if store update does not preserve current server identity.

## Implementation notes

- Keep scope in `src/core` shared flow.
- Likely touch points:
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/SelectedCountryStore.kt`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2SyncCoordinator.kt`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerSelectionSyncCoordinator.kt`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainSelectionInteractor.kt`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/settings/SettingsViewModel.kt`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModel.kt`
- Prefer country-code-first matching for relocalization and persist localized display name as a derived value.
- Reuse existing selected-country version signaling to refresh UI state after relocalization write.

## Test scenarios

- TS-1: DEFAULT_V2 + selected country in English -> switch language to Russian -> selected country label updates to Russian without manual refresh/reselection.
- TS-2: DEFAULT_V2 + selected country in Russian -> switch language to English -> selected country label updates to English without manual refresh/reselection.
- TS-3: DEFAULT_V2 + language switch while cache is warm -> no cross-language mixed selected-country labels.
- TS-4: DEFAULT_V2 + selected server still exists after relocalization sync -> current index/current server preserved.
- TS-5: DEFAULT_V2 + selected server removed after relocalization sync -> safe deterministic fallback, no crash.
- TS-6: LEGACY/VPNGATE/CUSTOM regression checks -> behavior unchanged.

## Definition of done

- AC1-AC5 implemented with automated coverage.
- Manual QA confirms selected DEFAULT_V2 country/server label relocalizes immediately after language change without manual refresh/reselection.
- No regressions in non-v2 sources or selected-country store stability.

## Code Implementator handoff seed

Use this story file as source of truth for implementation: docs/userstories/US-08-default-v2-selected-country-relocalization-on-language-switch.md.
