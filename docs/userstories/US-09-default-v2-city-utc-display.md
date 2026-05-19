# US-09 - Server Position and Address Display Contract Across Sources

## Chat title

Story Spec - US-09 server position and address display contract

## User story

As an OpenVPN client user,
I want the main details view to show the current server position as index/total under the Server label,
and the selected server IP under the Address label,
so that server selection status is consistent and easy to understand regardless of source.

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

- Main details rendering is driven through shared connection controls and selection store:
  - src/core/src/main/res/layout/view_connection_controls.xml
  - src/core/src/main/res/layout/view_connection_details.xml
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsView.kt
  - src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/common/components/ConnectionControlsPresenter.kt
- User-facing contract for this story is now source-agnostic in the main details surface:
  - Server field: current index and total count in selected country (`current/total`, for example `6/7`)
  - Address field: selected server IP
- Legacy, DEFAULT_V2, VPN Gate, and Custom must all follow the same details contract.

### Problem

Main details behavior diverged between sources and prior changes mixed city/UTC rendering with server-position rendering. The UI contract must be explicit and stable across all sources.

### Goal

Enforce one shared contract for the main details view: Server always shows `current/total` for the selected country and Address always shows server IP, regardless of source.

## Acceptance criteria

### AC-1 - Main details contract alignment

| ID | Criterion |
| --- | --- |
| AC-1.1 | In the main details view, the value under `Server` is rendered as `currentIndex/totalCount` for the selected country (for example `6/7`). |
| AC-1.2 | The `Server` position format is source-agnostic and applies to `DEFAULT_V2`, `LEGACY`, `VPNGATE`, and `CUSTOM`. |
| AC-1.3 | In the main details view, the value under `Address` is always the selected server IP. |
| AC-1.4 | Details rendering is null-safe and must not show malformed placeholders when the selected list is unavailable or empty. |

### AC-2 - Server list and selection flow consistency

| ID | Criterion |
| --- | --- |
| AC-2.1 | Selecting any server updates the details view so `Server` shows the selected server position in the current list as `current/total`. |
| AC-2.2 | Selecting any server updates the details view so `Address` shows the selected server IP. |
| AC-2.3 | Existing server list card layout, ping/signal/flag behavior, and navigation remain unchanged unless explicitly required by this contract. |
| AC-2.4 | The contract remains correct after country changes and subsequent server reselection. |

### AC-3 - Persistence and refresh behavior

| ID | Criterion |
| --- | --- |
| AC-3.1 | After reconnect, manual refresh, app background/foreground, and app reopen, `Server=current/total` remains consistent with the restored selection state. |
| AC-3.2 | After reconnect, manual refresh, app background/foreground, and app reopen, `Address=IP` remains consistent with the restored selection state. |
| AC-3.3 | Selection interactions and intent extras used by selection screens continue to work with no regression. |

### AC-4 - Cross-source regression safety

| ID | Criterion |
| --- | --- |
| AC-4.1 | `Server=current/total` contract remains correct for `DEFAULT_V2`. |
| AC-4.2 | `Server=current/total` contract remains correct for `LEGACY`. |
| AC-4.3 | `Server=current/total` contract remains correct for `VPNGATE`. |
| AC-4.4 | `Server=current/total` contract remains correct for `CUSTOM`. |
| AC-4.5 | `Address=IP` contract remains correct for all sources. |

### AC-5 - UI contract clarity

| ID | Criterion |
| --- | --- |
| AC-5.1 | Under the `Server` label, only position text `current/total` is shown. |
| AC-5.2 | Under the `Address` label, only the selected server IP is shown. |
| AC-5.3 | The main details view does not repurpose `Server` for city/time-zone text in this story scope. |

### AC-6 - Automated regression coverage

| ID | Criterion |
| --- | --- |
| AC-6.1 | Presenter/view-model tests cover details rendering where `Server` shows `current/total` for valid selected server lists. |
| AC-6.2 | Presenter/view-model tests cover details rendering where `Address` shows selected server IP. |
| AC-6.3 | Tests cover source-agnostic parity for `DEFAULT_V2`, `LEGACY`, `VPNGATE`, and `CUSTOM` under the same details contract. |
| AC-6.4 | Tests cover reconnect/reopen or equivalent rehydration paths to ensure details contract remains stable after state restoration. |
| AC-6.5 | Existing tests unrelated to this contract remain green or are updated only where required by the new UI contract. |

## Out of scope

- Backend API contract redesign
- New server-source types or source-selection UX changes
- Redesign of speedometer/status/traffic blocks unrelated to Server/Address details values
- OpenVPN engine module changes

## Risks and open questions

| ID | Risk or question | Current handling |
| --- | --- | --- |
| R-1 | Main details values may drift between sources if formatting is source-conditional. | Enforce source-agnostic contract with explicit multi-source tests (AC-4, AC-6). |
| R-2 | Selection rehydration may restore stale details text after reopen. | Require reconnect/reopen stability checks in tests and manual QA (AC-3, AC-6). |
| R-3 | Existing text bindings may swap Server and Address values. | Keep explicit assertions that `Server=current/total` and `Address=IP`. |

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