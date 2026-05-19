---
id: US-09-MQ-02
title: Main details show Address as selected server IP
area: Android
surface: android
---

## Preconditions
- App is on the main screen with DEFAULT_V2 selected.
- Server list is available and a server can be selected.
- Network is available so selection refresh can complete.

## Steps
1. Select a DEFAULT_V2 server.
2. Open the selected server details or connection details surface.
3. Verify the `Address` field displays the selected server IP.
4. Return to the main screen and confirm the same IP is shown under `Address`.
5. Capture screenshots for both the selected details view and the main screen.

## Assertions
- Selected server details show the selected server IP under `Address`.
- Main screen shows the same IP under `Address` after selection.
- `Address` does not contain position text like `1/1` or `6/7`.
- No malformed placeholder text appears in the `Address` field.

## Evidence Required
- Screenshot of the selected server details view.
- Screenshot of the main screen after selection showing `Address=IP`.
- Log snippet if needed to prove the selected server identity.

## Cleanup
- Keep the selected DEFAULT_V2 server in place for the persistence case.

## Actual Result
- SUPERSEDED.
- Previous execution in this file used the old city/UTC expectation set and is no longer the active contract.
- Rerun required with current assertions (`Address=selected server IP`).
- Evidence:
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-main-baseline.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-current.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-main-baseline.png
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-current.png