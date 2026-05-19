---
id: US-09-MQ-03
title: DEFAULT_V2 city and UTC remain stable after reconnect and reopen
area: Android
surface: android
---

## Preconditions
- A DEFAULT_V2 server with city + UTC is already selected from the previous case.
- The app is connected or can be toggled through its normal connection controls.
- App restart is possible from the device without clearing app data.

## Steps
1. Reconnect or refresh the selected server flow using the app controls.
2. Confirm the selected server details still show the same city and UTC.
3. Background or close the app, then reopen it from the launcher or exported splash.
4. Confirm the main screen still shows the same selected city and UTC after relaunch.
5. Capture screenshots before and after the relaunch.

## Assertions
- Reconnect does not drop the selected city or UTC.
- App reopen preserves the same city and UTC display.
- No crash or broken selection flow occurs during reconnect or relaunch.

## Evidence Required
- Screenshot before reconnect or refresh.
- Screenshot after reconnect.
- Screenshot after app reopen.
- Optional logcat snippet if the flow needs confirmation.

## Cleanup
- Leave the app in a stable main-screen state for the source-switch regression case.

## Actual Result
- NOT COMPLETED.
- Persistence/reconnect validation was not completed because prerequisite expected state (actual DEFAULT_V2 city+UTC shown on selected/main surfaces) was not achieved in MQ-01 and MQ-02.
- Evidence available only for precondition failure on main surface placeholder city rendering.
- Evidence:
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-current.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-current.png