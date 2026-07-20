# MQ-US15-006 — TV Settings shows the same 2-option Server list source

## Preconditions
- TV app installed (debug build) at commit e427d55 on MIBOX4 (`192.168.1.94:5555`)
- Launched via `adb shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LEANBACK_LAUNCHER 1`
  (LAUNCHER category does not resolve on TV builds — see
  `tests/manual-e2e/environment/android-tv-dpad-qa-runbook.md`)

## Steps
1. Confirm launch reaches `tv.MainActivity` (`dumpsys window | grep mCurrentFocus`)
2. Open drawer (hamburger) → Настройки (Settings)
3. Tap "Источник списка серверов" (Server list source) row to expand
4. Dump UI hierarchy and inspect radio option labels

## Expected
- Same 2 options as mobile: "Client for OpenVPN Gate" and "VPN Gate", no Legacy/Custom

## Result: PASS
- TV launched cleanly to `tv.MainActivity` showing "Client for OpenVPN Gate" with city+UTC display
  ("Самара (+04:00 UTC)").
- Settings → Server list source expanded showed exactly `text="Client for OpenVPN Gate"` and
  `text="VPN Gate"` in the uiautomator dump, no Legacy/Custom strings.

## Evidence
- Screenshots: tv_main.png, tv_source_options.png (retained locally, not committed)

## Run date
2026-07-20
