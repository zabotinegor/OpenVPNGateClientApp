---
id: US-09-MQ-02
title: DEFAULT_V2 selected server and main screen show actual city and UTC
area: Android
surface: android
---

## Preconditions
- App is on the main screen with DEFAULT_V2 selected.
- Server list is available and a server with city + UTC can be selected.
- Network is available so selection refresh can complete.

## Steps
1. Select a DEFAULT_V2 server that has both city and UTC.
2. Open the selected server details or connection details surface.
3. Verify the city and UTC fields display the actual server values.
4. Return to the main screen and confirm the same city and UTC are shown there.
5. Capture screenshots for both the selected details view and the main screen.

## Assertions
- Selected server details show the actual city and UTC for the chosen server.
- Main screen shows the same city and UTC after selection.
- No placeholder position value replaces the real city.
- No malformed combined text appears when the values are populated.

## Evidence Required
- Screenshot of the selected server details view.
- Screenshot of the main screen after selection.
- Log snippet if needed to prove the selected server identity.

## Cleanup
- Keep the selected DEFAULT_V2 server in place for the persistence case.

## Actual Result
- FAILED.
- Main details surface still showed placeholder-style city value `1/1` instead of actual selected city (and no UTC value paired with city on the main surface).
- This violates the expected propagation of real selected city/UTC from DEFAULT_V2 selection into selected/main details.
- Evidence:
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-main-baseline.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-current.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-main-baseline.png
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-current.png