# SPEC-US-15 — Remove Legacy and Custom server sources

## Story
US-15 — Remove Legacy and Custom server sources
Story path: `docs/userstories/US-15-remove-legacy-and-custom-server-sources.md`
Branch: `feature/us-15-remove-legacy-custom-server-sources`
Commit under test: `e427d55`

## Diff scope
`ServerSource` enum reduced from 4 values to 2 (`DEFAULT_V2`, `VPNGATE`); Legacy CSV and Custom-URL
"Server list source" options, the custom-URL input field, and their ViewModel/Activity wiring
removed; `ServerSelectionSyncCoordinator` v2-failure fallback rewired to go directly to `VPNGATE`;
`UserSettingsStore` migrates stale persisted `server_source` values (`"DEFAULT"`, `"LEGACY"`,
`"CUSTOM"`) to `DEFAULT_V2` in-memory on every load.

## Acceptance criteria in scope

| AC | Description |
|----|-------------|
| AC1 | Settings "Server list source" shows exactly 2 radio options: "Client for OpenVPN Gate" and "VPN Gate"; Legacy/Custom options and the custom-URL input field are removed |
| AC4 | A persisted `server_source` of `LEGACY`, `CUSTOM`, or legacy `"DEFAULT"` string migrates to `DEFAULT_V2` on launch with no crash |
| AC7 | server_v2-exclusive features (city+UTC display) continue to work for `DEFAULT_V2`; VPNGATE (CSV-only) shows IP-based display, no city/UTC |
| AC8 | Existing connect/disconnect and server selection flows work correctly for both remaining sources |

## Test cases

| Case ID | Description |
|---------|-------------|
| MQ-US15-001 | Settings screen shows exactly 2 "Server list source" radio options, no Legacy/Custom/URL field |
| MQ-US15-002 | Selecting "VPN Gate" shows IP-based ("АДРЕС") display, no city/UTC |
| MQ-US15-003 | Selecting "Client for OpenVPN Gate" shows city+UTC ("ГОРОД") display |
| MQ-US15-004 | VPN connect/disconnect cycle completes without crash on `DEFAULT_V2` source |
| MQ-US15-005 | Persisted `server_source` of `LEGACY`, `CUSTOM`, or legacy `"DEFAULT"` string migrates cleanly on launch (no crash), Settings shows "Client for OpenVPN Gate" selected |
| MQ-US15-006 | TV Settings screen shows the same 2-option "Server list source" as mobile |

## Test suite
`tests/manual-e2e/stories/US-15-remove-legacy-and-custom-server-sources/suites/US15-CORE.md`

## Devices
- Samsung Galaxy A71 SM_A715F Android 13 (`R58N849XQEY`)
- MIBOX4 Android TV (`192.168.1.94:5555`)

## Notes
- `UserSettingsStore.load()` (src/core/.../settings/UserSettingsStore.kt:50-56) resolves stale
  `"DEFAULT"`/`"LEGACY"`/`"CUSTOM"` strings to `ServerSource.DEFAULT_V2` in-memory on every read;
  it does not rewrite the persisted SharedPreferences file. This is intentional and idempotent —
  every launch resolves correctly regardless of what is on disk — not a defect.
- AC2/AC3/AC5/AC6 (enum exhaustiveness, coordinator fallback wiring, string resource removal) are
  code-level concerns already covered by code review and the unit test suite
  (`testDebugUnitTestApp`, 786/786 passed at this commit); not re-verified manually here.
