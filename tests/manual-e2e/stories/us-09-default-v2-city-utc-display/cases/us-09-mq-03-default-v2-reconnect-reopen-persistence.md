---
id: US-09-MQ-03
title: Reconnect and reopen preserve Server/Address details contract
area: Android
surface: android
---

## Preconditions
- A DEFAULT_V2 server is already selected from the previous case.
- The app is connected or can be toggled through its normal connection controls.
- App restart is possible from the device without clearing app data.

## Steps
1. Reconnect or refresh the selected server flow using the app controls.
2. Confirm the selected server details still show `Server=current/total` and `Address=IP` for the same selection.
3. Background or close the app, then reopen it from the launcher or exported splash.
4. Confirm the main screen still shows `Server=current/total` and `Address=IP` after relaunch.
5. Capture screenshots before and after the relaunch.

## Assertions
- Reconnect does not break `Server=current/total` and `Address=IP` rendering.
- App reopen preserves the same `Server=current/total` and `Address=IP` display for restored selection.
- No crash or broken selection flow occurs during reconnect or relaunch.

## Evidence Required
- Screenshot before reconnect or refresh showing `Server` and `Address` values.
- Screenshot after reconnect.
- Screenshot after app reopen.
- Optional logcat snippet if the flow needs confirmation.

## Cleanup
- Leave the app in a stable main-screen state for the source-switch regression case.

## Actual Result
- SUPERSEDED.
- Previous execution in this file used the old city/UTC expectation set and is no longer the active contract.
- Rerun required with current assertions (`Server=current/total`, `Address=IP`).
- Evidence:
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-current.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-current.png