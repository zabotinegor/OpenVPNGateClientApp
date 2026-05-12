---
id: VPN-PAUSE-CORE
title: VPN pause/resume connection controls acceptance suite
surface: android
includes:
  - VPN-PAUSE-001
  - VPN-PAUSE-002
  - VPN-PAUSE-003
---

## Objective
Validate pause/resume/stop behavior on the main VPN screen according to acceptance criteria.

## Environment (Validated)
- Real Android device over ADB (validated with Android 11 / MIUI).
- Real Android TV device over ADB TCP (validated with Android TV 9 / MiBox4).
- Fresh app install is required for acceptance-level run.
- Device should be unlocked and on Home before launch sequence.

## Scope
- Main screen connection controls in connected, paused, reconnecting, and disconnected states.
- Intermediate pause transition behavior (`connected -> pausing -> paused`).
- State transitions caused by Pause, Resume, and Stop actions.
- Resume path parity: `paused -> connected` must render reconnect progress statuses on `start_connection_button` as in `disconnected -> connected`.
- Resume path stability: once reconnecting starts, UI must not bounce back to the paused view.
- Stop path is validated separately for `connected -> disconnected` and `paused -> disconnected`.

## Out of Scope
- Notification UI action verification.
- Background kill/restart recovery.
- Visual polish outside control/state semantics.

## Execution Order
1. VPN-PAUSE-001
2. VPN-PAUSE-002
3. VPN-PAUSE-003

## Recommended Automation Path
- Mobile script: `tests/manual-e2e/automation/run-mobile-pause-button-qa.ps1`
- TV script: `tests/manual-e2e/automation/run-tv-pause-resume-e2e.ps1`
- Scripts perform launch/setup, pause/resume/stop checks, screenshots, and report generation.
- Default output goes to `%TEMP%`; use `-OutputDir` to persist a run.

## Commands (Windows)
```powershell
Set-Location .\src
.\gradlew :mobile:uninstallDebug :mobile:installDebug
```

```powershell
Set-Location ..
powershell -ExecutionPolicy Bypass -File .\tests\manual-e2e\automation\run-mobile-pause-button-qa.ps1 -Serial <DEVICE_SERIAL>
```

```powershell
Set-Location ..
powershell -ExecutionPolicy Bypass -File .\tests\manual-e2e\automation\run-tv-pause-resume-e2e.ps1 -Serial <TV_IP:5555>
```

## Known Device Caveats
- `adb shell uiautomator dump` on MIUI may emit `theme_compatibility.xml` errors; do not treat stderr noise as a failed XML dump by default.
- During first connect, system VPN permission prompt may appear and must be accepted.

## Exit Criteria
- All included cases pass.
- No blocker or major defect remains for acceptance criteria AC1-AC6.
- If AC2, AC4, or AC5 fail only in full-suite polling, focused rerun evidence may be used to confirm transient UI parity.

## Reference Validation
- `tests/manual-e2e/reference/archived/TV-PAUSE-RESUME-SMOOTHER-STATES-2026-04-25.md`
