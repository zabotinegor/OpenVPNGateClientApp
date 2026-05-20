# US-09 - DEFAULT_V2 City/UTC Display & Address Contract

## Chat title

Story Spec - US-09 city/UTC rendering on server select and main screens (DEFAULT_V2 only) + address contract alignment

## User story

As an OpenVPN client user,
I want the server selection list to display city and timezone for "Client for OpenVPN Gate" (DEFAULT_V2) servers,
and the main details view to show city with timezone in place of generic "Address" label when available,
with proper fallbacks to city-only or IP-only based on data availability,
so that I can see geographic and timing information clearly for each server source when using the DEFAULT_V2 API.

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

- Server selection list rendering is driven through:
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/ServerPickerAdapter.kt
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ServerDisplayFormatter.kt
- Main details rendering is driven through shared connection controls and selection store:
  - src/core/src/main/res/layout/view_connection_details.xml
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsView.kt
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsPresenter.kt
- DEFAULT_V2 API payload includes server metadata: city, timezone (UTC offset), IP
- Legacy, VPN Gate, and Custom sources typically provide only IP or basic metadata

### Problem

1. Server list currently shows only title/IP for all sources, missing geographic context
2. Main details "Address" label doesn't disambiguate between city and IP
3. City/UTC rendering is not consistently applied to DEFAULT_V2 source only
4. No fallback logic for missing city or timezone data

### Goal

Implement source-aware city/UTC display on server select and main screens **for DEFAULT_V2 sources only**, with explicit fallbacks:
- **Server select cards**: Show city+UTC (2 lines), city-only (1 line), or IP fallback (1 line)
- **Main address field**: Rename label to "City" and show city+UTC, city-only, or IP based on data availability
- Apply this behavior **only** when source is "Client for OpenVPN Gate" (DEFAULT_V2); other sources keep IP-only display

## Acceptance criteria

### AC-1 - Server select card display (DEFAULT_V2 only)

| ID | Criterion |
| --- | --- |
| AC-1.1 | On the server select list, when source is "Client for OpenVPN Gate" (DEFAULT_V2), each card displays the server entry with contextual city/UTC info. |
| AC-1.2 | **If city AND timezone are available**: Card shows 2 lines: (1) city name, (2) timezone in format `±HH:MM UTC` (example: `+07:00 UTC`). |
| AC-1.3 | **If city is available but timezone is missing**: Card shows 1 line with city name only. |
| AC-1.4 | **If city is missing**: Card shows 1 line with server IP as fallback. |
| AC-1.5 | For non-DEFAULT_V2 sources (Legacy, VPN Gate, Custom), server cards continue to show server title/IP without city/UTC rendering. |
| AC-1.6 | Card layout, ping/signal/country-flag rendering, and list scrolling behavior remain unchanged. |

### AC-2 - Main screen address display (DEFAULT_V2 only)

| ID | Criterion |
| --- | --- |
| AC-2.1 | On the main screen, when source is "Client for OpenVPN Gate" (DEFAULT_V2) and the selected server has city metadata, the label changes from "Address" to "City" (with locale translations). |
| AC-2.2 | **If city AND timezone are available**: Address field shows value in format `<city> (±HH:MM UTC)` (example: `Ho Chi Minh City (+07:00 UTC)`). |
| AC-2.3 | **If city is available but timezone is missing**: Address field shows value as city name only (example: `Ho Chi Minh City`). |
| AC-2.4 | **If city is missing**: Address field keeps label "Address" and shows server IP as before. |
| AC-2.5 | For non-DEFAULT_V2 sources, Address field always shows IP with label "Address" (no city/UTC rendering). |
| AC-2.6 | Main screen Server field continues to show `currentIndex/totalCount` for all sources. |

### AC-3 - Source-agnostic server position contract

| ID | Criterion |
| --- | --- |
| AC-3.1 | In the main details view, the value under `Server` is rendered as `currentIndex/totalCount` for the selected country (for example `6/7`). |
| AC-3.2 | The `Server` position format applies to `DEFAULT_V2`, `LEGACY`, `VPNGATE`, and `CUSTOM`. |
| AC-3.3 | Selecting any server updates the details view so `Server` shows the selected server position in the current list as `current/total`. |
| AC-3.4 | After country changes and subsequent server reselection, `Server=current/total` remains correct. |

### AC-4 - Persistence and refresh behavior

| ID | Criterion |
| --- | --- |
| AC-4.1 | After app reconnect, manual refresh, background/foreground cycle, and app reopen, city/UTC display (or fallback) on main screen remains consistent with restored selection state. |
| AC-4.2 | Server select list cards refresh correctly after source change, showing appropriate city/UTC or IP fallback based on new source. |
| AC-4.3 | After source switch from DEFAULT_V2 to Legacy/VPN Gate/Custom, Address field switches back to IP-only display with "Address" label. |
| AC-4.4 | After source switch to DEFAULT_V2, Address field switches to city/UTC rendering with "City" label when data is available. |

### AC-5 - Null-safety and fallback stability

| ID | Criterion |
| --- | --- |
| AC-5.1 | Display logic handles missing city gracefully (shows IP fallback). |
| AC-5.2 | Display logic handles missing timezone gracefully (shows city-only or IP). |
| AC-5.3 | Details rendering is null-safe and must not show malformed placeholders when selected server or metadata is unavailable. |
| AC-5.4 | Empty or null timezone values do not render UTC label. |

### AC-6 - Automated regression coverage

| ID | Criterion |
| --- | --- |
| AC-6.1 | Tests verify server select card rendering for DEFAULT_V2 with city+UTC, city-only, and IP-fallback cases. |
| AC-6.2 | Tests verify main Address field rendering for DEFAULT_V2 with city+UTC, city-only, and IP-fallback cases. |
| AC-6.3 | Tests verify label switching ("Address" ↔ "City") based on data availability and source. |
| AC-6.4 | Tests verify that non-DEFAULT_V2 sources (Legacy, VPN Gate, Custom) show IP-only with no city/UTC rendering. |
| AC-6.5 | Tests verify source-switching behavior and correct display after rehydration. |
| AC-6.6 | Existing tests unrelated to this contract remain green or are updated only where required by the new UI contract. |

## Out of scope

- Backend API contract redesign (assume DEFAULT_V2 provides city + timezone in response)
- New server-source types or source-selection UX changes beyond city/UTC display
- Redesign of speedometer/status/traffic blocks
- OpenVPN engine module changes
- Changes to server list pagination or filtering beyond city/UTC rendering
- Localization of timezone offset format (remain in ±HH:MM UTC format)

## Risks and open questions

| ID | Risk or question | Current handling |
| --- | --- | --- |
| R-1 | City/UTC data may not be present in all DEFAULT_V2 API responses. | Implement explicit fallback logic: city+UTC → city-only → IP fallback (AC-1.2 to AC-1.4, AC-2.2 to AC-2.4). |
| R-2 | Display may diverge between server list and main screen if formatting is inconsistent. | Use shared `ServerDisplayFormatter` utility for both surfaces to ensure parity. |
| R-3 | Label switching ("Address" ↔ "City") may confuse users or break translations. | Implement locale translation for "City" label and test with multiple languages. |
| R-4 | Timezone rendering may conflict with existing IP display on non-DEFAULT_V2 sources. | Guard all city/UTC logic with source check: only apply to DEFAULT_V2 (AC-1.5, AC-2.5). |
| R-5 | Selection rehydration may restore stale city/UTC text after app reopen. | Require source and server data to be available before rendering city/UTC; fallback to IP if missing (AC-4, AC-5). |
| R-6 | Existing tests may fail if they assert on hard-coded IP-only values. | Update all affected tests to account for city/UTC rendering on DEFAULT_V2; keep other sources IP-only. |

## Implementation notes

These notes are guidance for likely implementation surfaces, not a mandatory design.

### Likely affected areas

- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainContract.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModel.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainActivityCore.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsView.kt
- src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsPresenter.kt
- src/core/src/main/res/layout/view_connection_controls.xml
- src/core/src/main/res/layout/view_connection_details.xml
- src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsPresenterTest.kt
- src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/ui/main/MainViewModelTest.kt

### Design intent

- Keep one shared details contract across all sources for Server/Address values.
- Preserve existing server list card UX while aligning main details semantics.
- Prefer deterministic rendering rules for empty selection or unavailable server lists.

## Test scenarios

### Automated tests

| ID | Scenario |
| --- | --- |
| TS-1 | Selected server index 0 in a 1-item list -> `Server` renders `1/1`; `Address` renders the selected IP. |
| TS-2 | Selected server index 5 in a 7-item list -> `Server` renders `6/7`; `Address` renders the selected IP. |
| TS-3 | Switching sources (`DEFAULT_V2`, `LEGACY`, `VPNGATE`, `CUSTOM`) preserves `Server=current/total` + `Address=IP` contract. |
| TS-4 | Reconnect/rehydration path preserves `Server=current/total` + `Address=IP` for current selection. |
| TS-5 | Empty or invalid selection state does not crash and does not produce malformed details text. |

### Manual QA focus

| ID | Scenario |
| --- | --- |
| MQ-1 | Select a server and verify main details `Server` shows selected position as `current/total` (for example `6/7`). |
| MQ-2 | Verify main details `Address` shows the selected server IP and not position text. |
| MQ-3 | Restart app and verify `Server=current/total` and `Address=IP` remain consistent for restored selection. |
| MQ-4 | Switch source to Legacy CSV and verify the same details contract (`Server=current/total`, `Address=IP`). |
| MQ-5 | Switch source to VPN Gate and verify the same details contract (`Server=current/total`, `Address=IP`). |
| MQ-6 | Switch source to Custom and verify the same details contract (`Server=current/total`, `Address=IP`). |

## Definition of done

- AC-1 through AC-6 are implemented and validated.
- Main details consistently show `Server=current/total` and `Address=IP` for all supported sources.
- Restart/reconnect/source-switch manual checks confirm the same contract.
- Mandatory cross-source regression coverage passes without unintended behavior changes.

## Code Implementator handoff seed

Use this story file as source of truth for implementation: docs/userstories/US-09-default-v2-city-utc-display.md.