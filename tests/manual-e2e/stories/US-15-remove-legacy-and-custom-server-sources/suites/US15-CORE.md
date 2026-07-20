# US15-CORE — Remove Legacy/Custom server sources — Manual QA Suite

## Story
US-15 (branch `feature/us-15-remove-legacy-custom-server-sources`, commit `e427d55`)

## Devices
- Samsung Galaxy A71 SM_A715F Android 13 (`R58N849XQEY`)
- MIBOX4 Android TV (`192.168.1.94:5555`)

## Run date
2026-07-20

## Overall result: PASS

## Case results

| Case | Description | Result |
|------|-------------|--------|
| MQ-US15-001 | Settings shows exactly 2 Server list source options (mobile + TV) | PASS |
| MQ-US15-002 | VPN Gate source shows IP-based display, no city/UTC | PASS |
| MQ-US15-003 | Client for OpenVPN Gate (v2) source shows city+UTC display | PASS |
| MQ-US15-004 | VPN connect/disconnect regression on DEFAULT_V2 source | PASS |
| MQ-US15-005 | Migration of stale LEGACY/CUSTOM/legacy-"DEFAULT" server_source values | PASS |
| MQ-US15-006 | TV Settings shows same 2-option Server list source | PASS |

## AC verdict

| AC | Status | Evidence |
|----|--------|---------|
| AC1 | PASS | uiautomator dump on mobile + TV: exactly "Client for OpenVPN Gate" + "VPN Gate", no Legacy/Custom/URL field |
| AC4 | PASS | Clean launch + correct Settings display for LEGACY, CUSTOM, and legacy "DEFAULT" string persisted values; zero FATAL EXCEPTION |
| AC7 | PASS | VPNGATE shows АДРЕС/IP; DEFAULT_V2 shows ГОРОД/city+UTC — parity preserved |
| AC8 | PASS | Full connect→connected→disconnect cycle clean, data counters active, zero crashes |
| AC2, AC3, AC5, AC6 | Not re-verified manually | Code-level concerns already covered by code review (docs/qa-evidence/us-15-remove-legacy-custom-server-sources-review-2.md) and testDebugUnitTestApp (786/786 passed at e427d55) |

## Automated test suite
`testDebugUnitTestApp`: BUILD SUCCESSFUL (already green/up-to-date at commit e427d55; 786/786 per
prior quality-gate run) — not re-executed as new work, confirmed via Gradle UP-TO-DATE task status.

## Build/deploy verification
- `assembleDebugApp`: BUILD SUCCESSFUL at e427d55 (mobile + tv APKs)
- `mobile-debug.apk` installed on `R58N849XQEY`; `tv-debug.apk` installed on `192.168.1.94:5555`
- TV package `versionName=1.0.4-beta.1`, `lastUpdateTime=2026-07-20 09:14:31` confirms fresh install

## Notes
- `UserSettingsStore.load()` migrates stale `server_source` strings in-memory only (does not
  rewrite the persisted SharedPreferences file) — intentional and idempotent; documented in the
  spec and case MQ-US15-005 to avoid future re-investigation.
- Raw screenshots and logcat captures were retained only in the local session scratchpad per QA
  artifact policy (not committed).
