# MQ-US15-001 — Settings shows exactly 2 Server list source options

## Preconditions
- App installed (debug build) at commit e427d55
- App launched, navigated to drawer (hamburger) → Настройки (Settings)

## Steps
1. Open Settings screen
2. Locate "Источник списка серверов" (Server list source) row
3. Tap the row to expand the radio group
4. Dump UI hierarchy: `adb shell uiautomator dump /sdcard/ui.xml && adb shell cat /sdcard/ui.xml`
5. Extract all `text="..."` attributes and inspect for radio option labels and any EditText/URL field

## Expected
- Exactly 2 radio options present: "Client for OpenVPN Gate" and "VPN Gate"
- No "Client for OpenVPN Gate (Legacy)" option
- No "Свой сервер (введите url)" / Custom option
- No custom-URL EditText input field anywhere on the screen

## Result: PASS
- uiautomator dump text extraction contained exactly these two radio-adjacent strings:
  `text="Client for OpenVPN Gate"`, `text="VPN Gate"` — no Legacy/Custom strings, no EditText/URL
  field present in the full dump.
- Verified identically on both devices (mobile `R58N849XQEY`, TV `192.168.1.94:5555`).

## Evidence
- Screenshots: phone_source.png (mobile Settings expanded), tv_source_options.png (TV Settings
  expanded) — retained locally in session scratchpad, not committed (not required evidence per
  QA artifact policy).

## Run date
2026-07-20
