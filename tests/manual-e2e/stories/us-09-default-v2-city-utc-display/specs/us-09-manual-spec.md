# US-09 Manual QA Spec: Server Position and Address Display Contract Across Sources

## Scope
- Story: US-09 server position and address display contract across sources.
- Surfaces: Android mobile mandatory.
- Focus: main details contract where `Server` shows selected position (`current/total`) and `Address` shows selected server IP, including persistence and source-switch regressions.

## Acceptance Criteria Mapping
- AC1: Main details `Server` shows selected position as `current/total`.
- AC2: Main details `Address` shows selected server IP.
- AC3: Contract is source-agnostic across `DEFAULT_V2`, `LEGACY`, `VPNGATE`, and `CUSTOM`.
- AC4: Reconnect/reopen preserve the same details contract.
- AC5: Existing server list card behavior remains stable unless explicitly changed by this story.

## Test Data and Environment
- Android phone target with the app installed and launchable from exported splash.
- DEFAULT_V2, Legacy CSV, VPN Gate, and Custom source switching available in Settings.
- Network access available so server lists can refresh from the active source.
- Use the source-switch pattern documented in `tests/manual-e2e/environment/android-miui-manual-qa-notes.md` when the device is MIUI.

## Risks and Out of Scope
- TV validation is optional and only performed if a Leanback-capable target is available.
- Visual formatting is checked on-device with screenshots showing `Server=current/total` and `Address=IP`; no additional code changes are part of this QA run.
- Backend contract changes are out of scope.