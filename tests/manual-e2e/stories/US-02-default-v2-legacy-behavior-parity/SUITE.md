# US-02 Manual E2E Test Suite — DEFAULT_V2 Legacy Behavior Parity

## User Story

**US-02**: `DEFAULT_V2` source must behave like Legacy CSV for initial server selection, auto-switch rotation, and automatic server refresh — without requiring the user to manually open the country list.

Reference: [docs/userstories/US-02-default-v2-legacy-behavior-parity.md](../../../../docs/userstories/US-02-default-v2-legacy-behavior-parity.md)

## Acceptance Criteria Summary

| ID | Criterion |
|----|-----------|
| AC-1.1 | App auto-selects a country from DEFAULT_V2 source on first launch |
| AC-1.2 | Selected country servers are pre-loaded in `SelectedCountryStore` |
| AC-1.3 | Connect button is visible and enabled on main screen without user country selection |
| AC-3.1 | `ServerAutoSwitcher` uses pre-populated store for rotation |
| AC-3.2 | VPN engine LEVEL_NONETWORK → immediate switch to next server in selected country |
| AC-3.3 | Full server cycle is completed and logged when all servers fail |
| AC-4.1 | Foreground sync triggers `syncSelectedCountryServers` |
| AC-4.2 | Forced refresh (`force_refresh=true`) triggers network fetch |
| AC-4.3 | After forced refresh, `SelectedCountryStore` remains aligned |
| AC-4.5 | Connect button remains active after forced refresh |

## Test Cases

| Case | File | Surface | Description |
|------|------|---------|-------------|
| MQ-1 | cases/US-02-MQ-1.md | Android mobile | Fresh install — initial auto-selection |
| MQ-2 | cases/US-02-MQ-2.md | Android mobile | Auto-switch rotation |
| MQ-3 | cases/US-02-MQ-3.md | Android mobile | Foreground sync |
| MQ-4 | cases/US-02-MQ-4.md | Android mobile | Forced server refresh |
| MQ-5 | cases/US-02-MQ-5.md | Android TV | TV surface parity |

## Scope Notes
- MQ-1 through MQ-4 require only an Android mobile ADB target.
- MQ-5 requires a dedicated Android TV ADB target.
- All 5 cases should be executed on the same build commit/branch.
