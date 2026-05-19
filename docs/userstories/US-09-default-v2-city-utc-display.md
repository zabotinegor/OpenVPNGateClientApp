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
| AC-1.1 | Client v2 server model parses nullable city and UTC fields from api/v2/servers response without breaking existing required fields. |
| AC-1.2 | Mapping from ServerV2 into shared client selection models preserves city and UTC data when present, and remains null-safe when either field is absent. |
| AC-1.3 | Parse or mapping issues for city/UTC must not crash server loading flows; existing graceful error behavior is preserved. |

### AC-2 - Server list card rendering (country server list)

| ID | Criterion |
| --- | --- |
| AC-2.1 | When source is DEFAULT_V2 and both city and UTC are non-null and non-blank, each server card displays city with UTC in the requested format (for example: Canberra (UTC+10)). |
| AC-2.2 | When source is DEFAULT_V2 and city or UTC is null/blank, server card falls back to existing display semantics rather than showing malformed placeholders (for example empty parentheses). |
| AC-2.3 | Card ping/signal/flag behavior remains unchanged. |

### AC-3 - Selected server section rendering

| ID | Criterion |
| --- | --- |
| AC-3.1 | In selected server section, when active source is DEFAULT_V2 and selected server has city and UTC, UI shows city with UTC in the same formatting style as mockup intent. |
| AC-3.2 | If selected DEFAULT_V2 server lacks city or UTC, section uses safe fallback text and remains readable. |
| AC-3.3 | Selection interactions and intent extras used by selection screens continue to work with no regression. |

### AC-4 - Main screen rendering

| ID | Criterion |
| --- | --- |
| AC-4.1 | Main screen displays city and UTC for selected server when source is DEFAULT_V2 and both values are present. |
| AC-4.2 | Main screen keeps existing IP/address and server-position behavior unless explicitly changed by this story requirements; no unrelated metric/status regressions are introduced. |
| AC-4.3 | On app restart and on selected-country sync refresh, city/UTC display remains consistent with persisted selected server data. |

### AC-5 - Source-scoped behavior and regressions

| ID | Criterion |
| --- | --- |
| AC-5.1 | Legacy CSV behavior remains unchanged for server list cards, selected server section, and main screen fields. |
| AC-5.2 | VPN Gate behavior remains unchanged for server list cards, selected server section, and main screen fields. |
| AC-5.3 | No city/UTC formatting logic is forced onto non-v2 sources when those fields are unavailable. |

### AC-6 - Automated regression coverage

| ID | Criterion |
| --- | --- |
| AC-6.1 | Tests cover DEFAULT_V2 city+UTC render-ready mapping from API model through selection/shared models. |
| AC-6.2 | Tests cover null/blank fallback behavior for city/UTC. |
| AC-6.3 | Tests explicitly verify Legacy CSV regression stability (no behavior change required). |
| AC-6.4 | Tests explicitly verify VPN Gate regression stability (no behavior change required). |

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
| TS-1 | DEFAULT_V2 response contains city and UTC -> mapped selected server data exposes both for rendering. |
| TS-2 | DEFAULT_V2 response has null city or null UTC -> UI formatting fallback is used with no malformed text. |
| TS-3 | Country server list card for DEFAULT_V2 renders city+UTC text where available. |
| TS-4 | Main screen and selected server section render city+UTC after selection and after store reload. |
| TS-5 | Legacy CSV regression tests for same screens remain green without expectation changes except where explicitly justified. |
| TS-6 | VPN Gate regression tests for same screens remain green without expectation changes except where explicitly justified. |

### Manual QA focus

| ID | Scenario |
| --- | --- |
| MQ-1 | DEFAULT_V2 selected, open country server list and verify cards show city+UTC per mockup intent when backend fields are populated. |
| MQ-2 | Select a DEFAULT_V2 server and verify selected server section shows city+UTC. |
| MQ-3 | Return to main screen and verify city+UTC is visible and stable after reconnect/reopen. |
| MQ-4 | Switch to Legacy CSV and VPN Gate and verify no new city+UTC artifacts appear and existing behavior is unchanged. |

## Definition of done

- AC-1 through AC-6 are implemented and validated.
- DEFAULT_V2 shows city+UTC in server cards, selected server section, and main screen when both fields are present.
- Null/blank city/UTC behavior is safe and visually clean.
- Mandatory Legacy CSV and VPN Gate regression coverage passes without unintended behavior changes.

## Code Implementator handoff seed

Use this story file as source of truth for implementation: docs/userstories/US-09-default-v2-city-utc-display.md.