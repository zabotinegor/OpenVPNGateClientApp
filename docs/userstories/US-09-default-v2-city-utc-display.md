# US-09 - DEFAULT_V2 City and UTC Display Across Server Selection Surfaces

## Chat title

Story Spec - US-09 DEFAULT_V2 city and UTC display

## User story

As an OpenVPN client user,
I want server city and UTC to be shown when I use Client for OpenVPN Gate v2,
so that I can understand server location and time zone before and after selection.

## Start gate

Do not start work before reading .sdlc/status.json and verifying the required prior step.

## SDLC handoff metadata

- Flow status path: .sdlc/status.json
- Flow ID: dev::US-09
- Required prior step: none
- Story status update command:
  - .github/scripts/update-sdlc-status.ps1 -FlowId "dev::US-09" -Branch "dev" -Step "story" -Status "ready" -StoryId "US-09" -StoryPath "docs/userstories/US-09-default-v2-city-utc-display.md" -Evidence "docs/userstories/US-09-default-v2-city-utc-display.md"

## Background

### Current repository evidence

- The local backend latest commit on main is b6a1975 with message "Add city and UTC fields to v2 server list response".
- Backend v2 DTO now includes nullable fields:
  - OpenVPNClientServer/OpenVPNGate/OpenVPNGate.Domain/DTOs/VpnServerV2ListItemDto.cs
  - public string? City { get; set; }
  - public string? Utc { get; set; }
- Backend route remains the v2 servers endpoint:
  - OpenVPNClientServer/OpenVPNGate/OpenVPNGate/Controllers/ServersV2Controller.cs
  - Route api/v{version}/servers returns PagedResult<VpnServerV2ListItemDto>.
- On the Android client, v2 model and mapping do not currently carry city or UTC:
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerV2.kt has only ip, countryCode, countryName, configData.
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerV2.kt maps city to empty string in toLegacyServer().
- Server-card rendering currently shows title from city-or-name fallback and subtitle as IP only:
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/ServerPickerAdapter.kt.
- Main/selected server text is driven through shared connection controls and selection store:
  - src/core/src/main/res/layout/view_connection_controls.xml
  - src/core/src/main/res/layout/view_connection_details.xml
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsView.kt
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsPresenter.kt

### Problem

DEFAULT_V2 now provides city and UTC from backend, but the client currently drops these fields and cannot render the expected city plus UTC combination in server list and selected/main surfaces.

### Goal

Use backend city and UTC for DEFAULT_V2 to render user-visible server location text in the three requested surfaces while preserving Legacy CSV and VPN Gate behavior unchanged.

## Acceptance criteria

### AC-1 - DEFAULT_V2 contract alignment for city and UTC

| ID | Criterion |
| --- | --- |
| AC-1.1 | Client v2 server model (`ServerV2`) parses nullable `city` and `utc` fields from `api/v2/servers` response without breaking existing required fields (`ip`, `countryCode`, `countryName`, `configData`). |
| AC-1.2 | Shared server model (`Server`) carries a `utc` field so that UTC survives the `ServerV2.toLegacyServer()` mapping and is available for UI rendering. |
| AC-1.3 | Selected-country storage persists `utc` alongside `city` so that both values are reloaded correctly after app restart or sync refresh. |
| AC-1.4 | Mapping and storage for `city`/`utc` are null-safe; absent or null values are treated as empty strings without crashing server loading flows. |

### AC-2 - Server list card rendering (country server list)

| ID | Criterion |
| --- | --- |
| AC-2.1 | Server card rendering is source-scoped: the city/UTC two-line layout applies only when the active source is `DEFAULT_V2`. Non-DEFAULT_V2 sources use the prior single-line behavior (city-or-name title + IP subtitle). |
| AC-2.2 | When source is `DEFAULT_V2` and both city and a valid UTC offset are present: card shows two lines — line 1 is city; line 2 is the timezone formatted as `+HH:MM UTC` or `-HH:MM UTC` (for example `+09:00 UTC`). |
| AC-2.3 | When source is `DEFAULT_V2` and city is present but UTC is absent or blank: card shows one line with city; the timezone line is hidden. |
| AC-2.4 | When source is `DEFAULT_V2` and city is absent or blank: card shows one line with server IP; the timezone line is hidden. |
| AC-2.5 | UTC normalization accepts varied backend formats (for example `UTC+9`, `UTC+5:30`, `GMT+12`, `+05:00`) and produces a canonical `+/-HH:MM UTC` output; malformed or unrecognisable inputs are treated as absent. |
| AC-2.6 | Card ping, signal, and flag behavior remains unchanged regardless of source or city/UTC availability. |

### AC-3 - Selected server section rendering

| ID | Criterion |
| --- | --- |
| AC-3.1 | Selected server section is source-gated: city/UTC location text is shown only when the active source is `DEFAULT_V2`. |
| AC-3.2 | When source is `DEFAULT_V2` and selected server has both city and a valid UTC offset, the section renders city with formatted UTC (using the same `+/-HH:MM UTC` normalization as AC-2.5). |
| AC-3.3 | When source is `DEFAULT_V2` and city or UTC is absent, the section falls back to the server IP/address and remains readable with no malformed placeholders. |
| AC-3.4 | Selection interactions and intent extras used by selection screens continue to work with no regression. |

### AC-4 - Main screen rendering

| ID | Criterion |
| --- | --- |
| AC-4.1 | Main screen location rendering is source-gated: city/UTC is shown only when the active source is `DEFAULT_V2`. |
| AC-4.2 | When source is `DEFAULT_V2` and selected server has both city and a valid UTC offset, the main screen displays location text using the same `+/-HH:MM UTC` formatting. |
| AC-4.3 | When source is `DEFAULT_V2` and city or UTC is absent, the main screen falls back to IP/address display; no unrelated metric or status regressions are introduced. |
| AC-4.4 | On app restart and on selected-country sync refresh, city/UTC display remains consistent with persisted selected server data (AC-1.3 guarantees UTC survives store round-trip). |

### AC-5 - Source-scoped behavior and regressions

| ID | Criterion |
| --- | --- |
| AC-5.1 | Legacy CSV behavior remains unchanged for server list cards, selected server section, and main screen fields. |
| AC-5.2 | VPN Gate behavior remains unchanged for server list cards, selected server section, and main screen fields. |
| AC-5.3 | No city/UTC formatting logic is forced onto non-v2 sources when those fields are unavailable. |

### AC-6 - Automated regression coverage

| ID | Criterion |
| --- | --- |
| AC-6.1 | Tests cover `ServerV2` model parsing of nullable `city` and `utc` fields and their propagation through `toLegacyServer()` into the shared `Server` model. |
| AC-6.2 | Tests cover UTC normalization for representative input formats (`UTC+9`, `UTC+5:30`, `GMT+12`, `+05:00`) and confirm canonical `+/-HH:MM UTC` output; malformed input is confirmed absent/hidden. |
| AC-6.3 | Tests cover `SelectedCountryStore` UTC persistence: `utc` is serialized on save and deserialized correctly on reload. |
| AC-6.4 | Tests cover all three server list card rendering cases for `DEFAULT_V2`: (a) city+UTC → two-line layout, (b) city only → one-line city with timezone hidden, (c) no city → one-line IP with timezone hidden. |
| AC-6.5 | Tests explicitly verify that non-`DEFAULT_V2` source rendering for server list cards is unchanged (city-or-name title + IP subtitle, no UTC line). |
| AC-6.6 | Tests cover Legacy CSV regression stability: server list, selected section, and main screen behavior unchanged. |
| AC-6.7 | Tests cover VPN Gate regression stability: server list, selected section, and main screen behavior unchanged. |

## Out of scope

- Backend API contract redesign beyond already added City and Utc fields
- New server-source types or source-selection UX changes
- Redesign of speedometer/status/traffic blocks unrelated to city/UTC display
- OpenVPN engine module changes

## Risks and open questions

| ID | Risk or question | Current handling |
| --- | --- | --- |
| R-1 | UTC format from backend may vary (for example UTC+10 vs GMT+10). | Treat backend UTC as display payload for this story; avoid client-side timezone recomputation unless required by future stories. |
| R-2 | Shared model currently stores city but has no explicit UTC field. | Implementation should add minimal data-path changes required for persistence/display while preserving existing selection behavior. |
| R-3 | Main-screen details currently blend server-position and location text responsibilities. | Keep story scope focused on requested display surfaces and require no unrelated behavior shifts. |
| R-4 | Non-v2 sources do not provide city/UTC. | Enforce source-scoped logic and mandatory regression tests for Legacy and VPN Gate. |

## Implementation notes

These notes are guidance for likely implementation surfaces, not a mandatory design.

### Likely affected areas

- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServerV2.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/ServersV2Repository.kt (if normalization/serialization adaptation is needed)
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/SelectedCountryStore.kt (if UTC persistence is introduced there)
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/ServerPickerAdapter.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsView.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsPresenter.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainActivityCore.kt
- src/core/src/main/res/layout/item_server_row.xml
- src/core/src/main/res/layout/view_connection_controls.xml
- src/core/src/main/res/layout/view_connection_details.xml
- src/core/src/main/res/values/strings.xml and localized resources if new labels/formats are required

### Design intent

- Keep DEFAULT_V2-specific city/UTC formatting logic centralized in shared UI/presenter helpers rather than duplicated per screen.
- Preserve legacy/shared server selection data flow unless minimal extension is needed for UTC.
- Prefer deterministic formatting and explicit fallback rules for null/blank city/UTC values.

## Test scenarios

### Automated tests

| ID | Scenario |
| --- | --- |
| TS-1 | `ServerV2` parses `city` and `utc` from backend response and `toLegacyServer()` copies both to shared `Server` model. |
| TS-2 | `ServerV2` with null `city` and null `utc` maps to empty strings; no crash, no malformed text anywhere downstream. |
| TS-3 | UTC normalization: `UTC+9` → `+09:00 UTC`; `UTC+5:30` → `+05:30 UTC`; `GMT+12` → `+12:00 UTC`; `+05:00` → `+05:00 UTC`; malformed input → empty/hidden. |
| TS-4 | `SelectedCountryStore` round-trip: server saved with non-blank `utc` is reloaded with the same `utc` value. |
| TS-5 | `DEFAULT_V2` card: city + valid UTC → two lines (city line 1, `+09:00 UTC` line 2, IP hidden). |
| TS-6 | `DEFAULT_V2` card: city present, UTC absent → one line city, timezone line hidden. |
| TS-7 | `DEFAULT_V2` card: city absent → one line IP, timezone line hidden. |
| TS-8 | Non-`DEFAULT_V2` card (Legacy/VPN Gate): city-or-name title + IP subtitle, no UTC line rendered. |
| TS-9 | Main screen and selected server section render city+UTC after selection and after store reload for `DEFAULT_V2`. |
| TS-10 | Legacy CSV and VPN Gate regression: existing tests for list, selected section, and main screen remain green. |

### Manual QA focus

| ID | Scenario |
| --- | --- |
| MQ-1 | `DEFAULT_V2` selected, open country server list. Verify: servers with city + UTC show two lines (city top, `+/-HH:MM UTC` bottom); card ping/signal/flag unchanged. |
| MQ-2 | `DEFAULT_V2` selected, open country server list. Verify: servers with city but no UTC show one line (city); timezone row not visible. |
| MQ-3 | `DEFAULT_V2` selected, open country server list. Verify: servers with no city show one line (IP); timezone row not visible. |
| MQ-4 | Select a `DEFAULT_V2` server with city+UTC and return to main screen. Verify selected server section and main screen show city with formatted UTC text. |
| MQ-5 | Restart app with a `DEFAULT_V2` selection that includes city+UTC. Verify city+UTC display is consistent after restart (UTC survived store round-trip). |
| MQ-6 | Switch source to Legacy CSV. Verify server list cards show city-or-name title + IP subtitle with no UTC line artifacts. |
| MQ-7 | Switch source to VPN Gate. Verify server list cards show city-or-name title + IP subtitle with no UTC line artifacts. |

## Definition of done

- AC-1 through AC-6 are implemented and validated.
- DEFAULT_V2 shows city+UTC in server cards, selected server section, and main screen when both fields are present.
- Null/blank city/UTC behavior is safe and visually clean.
- Mandatory Legacy CSV and VPN Gate regression coverage passes without unintended behavior changes.

## Code Implementator handoff seed

Use this story file as source of truth for implementation: docs/userstories/US-09-default-v2-city-utc-display.md.