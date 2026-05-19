# US-09 Manual QA Spec: DEFAULT_V2 City and UTC Display Across Server Selection Surfaces

## Scope
- Story: US-09 default-v2 city and UTC display across server selection surfaces.
- Surfaces: Android mobile mandatory.
- Focus: country server list cards, selected server section, main screen persistence after reconnect/reopen, and source-switch regressions for Legacy CSV and VPN Gate.

## Acceptance Criteria Mapping
- AC1: DEFAULT_V2 data path carries city and UTC into selection and main screen state.
- AC2: DEFAULT_V2 server cards render combined city + UTC title with IP subtitle.
- AC3: Selected server section shows the actual city and UTC for DEFAULT_V2 selections.
- AC4: Main screen keeps city + UTC after reconnect and app reopen.
- AC5: Legacy CSV and VPN Gate remain unchanged.

## Test Data and Environment
- Android phone target with the app installed and launchable from exported splash.
- DEFAULT_V2, Legacy CSV, and VPN Gate source switching available in Settings.
- Network access available so server lists can refresh from the active source.
- Use the source-switch pattern documented in `tests/manual-e2e/environment/android-miui-manual-qa-notes.md` when the device is MIUI.

## Risks and Out of Scope
- TV validation is optional and only performed if a Leanback-capable target is available.
- Visual formatting is checked on-device; no additional code changes are part of this QA run.
- Backend contract changes are out of scope.