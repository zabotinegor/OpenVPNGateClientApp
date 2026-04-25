---
id: SPEC-VPN-PAUSE-RESUME-FLOW
title: Main screen pause/resume/stop behavior
surface: android
relatedSuite: VPN-PAUSE-CORE
---

## Behavior Under Test
Main screen connection controls must reflect VPN runtime state and support pause/resume/stop transitions.

## Real-Device Notes (Validated)
- Test device profile used during validation: Android 11 (MIUI).
- Additional validated profile: Android TV 9 (MiBox4 over ADB TCP).
- Connected and paused states use the same stop-control id: `start_connection_button` (text changes to stop action in active states).
- Pause/resume toggle id: `pause_connection_button`.
- Server picker can open with no loaded items on fresh app state; refresh may be required before selecting a country/server.
- On MIUI, `uiautomator dump` may print `theme_compatibility.xml` errors to stderr even when XML dump is generated correctly.
- On Android TV, AC4 transition can be short enough that full-suite polling misses the transient reconnect state; use focused rerun evidence when validating visual parity.

## Acceptance Criteria Mapping
- AC1: When VPN is turned on, user sees Pause and Stop controls, VPN is running.
- AC2: When Pause is tapped in connected state, UI leaves the connected view immediately and enters an intermediate pausing state before paused is shown.
- AC3: When VPN is paused, user sees Resume and Stop controls.
- AC4: When Resume is tapped in paused state, Resume control is hidden during reconnect and connection progress statuses are shown on `start_connection_button` exactly as in `disconnected -> connected` flow.
- AC5: After Resume is tapped, UI must not bounce back to the paused view or re-show Resume while reconnect is already in progress.
- AC6: When Stop is tapped from connected or paused state, user sees only Start connection and VPN is stopped.

## State Model
- Disconnected: Start connection visible.
- Connected: Pause and Stop visible.
- Pausing: Resume/Pause toggle hidden; stop control remains visible; status leaves `Connected` immediately and shows pausing transition.
- Paused: Resume and Stop visible.
- Resuming: Resume hidden; stop control remains visible and renders reconnect progress text via `start_connection_button`.

## Test Data and Dependencies
- Reachable VPN endpoint from configured server source.
- Stable network connection.
- Build with pause/resume support enabled.

## Risks
- Transitional timing can cause flaky visual assertions if checks happen before state settles.
- Device-specific animation delays can affect button visibility checks.
- First-run app permissions can block connection flow until accepted.
- `Pausing` may be shorter than `Paused` on fast devices; capture fast polling evidence rather than relying on a single frame.

## Evidence Policy
- Capture at least one screenshot per assertion point.
- Capture logs for each critical state transition: connected, paused, reconnecting, resumed, disconnected.
- Capture or log at least one focused sample that proves `Pausing` is emitted before `Paused`.
- If AC4 or AC5 is not observed in a full-suite run, retain a focused rerun result proving `pause_connection_button` hide + reconnect progress text + no bounce back to paused.

## Command Runbook (Windows)
Run from repository root and use fresh install before acceptance validation:

```powershell
Set-Location .\src
.\gradlew :mobile:uninstallDebug :mobile:installDebug
```

Then run manual QA automation from repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\manual-e2e\automation\run-mobile-pause-button-qa.ps1 -Serial <DEVICE_SERIAL>
```

TV validation command from repository root:

```powershell
powershell -ExecutionPolicy Bypass -File .\tests\manual-e2e\automation\run-tv-pause-resume-e2e.ps1 -Serial <TV_IP:5555>
```

Reference validation notes:
- `tests/manual-e2e/reference/TV-PAUSE-RESUME-SMOOTHER-STATES-2026-04-25.md`
