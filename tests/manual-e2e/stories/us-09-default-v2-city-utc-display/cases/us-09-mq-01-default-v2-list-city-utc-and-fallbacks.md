---
id: US-09-MQ-01
title: Main details show Server as selected position (current/total)
area: Android
surface: android
---

## Preconditions
- App is installed and launched on the Android phone target.
- Server source is set to DEFAULT_V2.
- Network is available and the server list can refresh.
- At least one country has more than one server so position text can be validated as `current/total`.

## Steps
1. Open the country server list for a DEFAULT_V2 country.
2. Select a server that is not the first entry when possible.
3. Return to the main details surface.
4. Verify the `Server` field value format is `current/total` (for example `6/7`).
5. Rotate once between portrait and landscape if the device and app allow it without interrupting the flow.
6. Capture screenshots before and after orientation change.

## Assertions
- `Server` value is shown as `current/total`.
- `Server` value reflects the currently selected server position in the selected country list.
- Orientation change does not break or swap the `Server` value.
- No malformed placeholder text is shown in the `Server` field.

## Evidence Required
- Screenshot of main details showing `Server=current/total`.
- Screenshot after orientation change showing the same contract.
- Optional UI tree dump if the value is ambiguous.

## Cleanup
- Return the device to portrait orientation if it was rotated.
- Leave the app on the main screen for the next case.

## Actual Result
- SUPERSEDED.
- Previous execution in this file used the old city/UTC expectation set and is no longer the active contract.
- Rerun required with current assertions (`Server=current/total`).
- Evidence:
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-settings-screen.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-country-list-2.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-vietnam-servers.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-vietnam-servers.png