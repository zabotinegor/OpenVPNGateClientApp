---
id: US-09-MQ-01
title: DEFAULT_V2 list cards show city and UTC with clean fallbacks
area: Android
surface: android
---

## Preconditions
- App is installed and launched on the Android phone target.
- Server source is set to DEFAULT_V2.
- Network is available and the server list can refresh.
- At least one country with DEFAULT_V2 servers has both city and UTC populated, and at least one row can be checked for missing city or UTC fallback behavior.

## Steps
1. Open the country server list for a DEFAULT_V2 country.
2. Inspect rows that have both city and UTC values.
3. Inspect at least one row where city or UTC is missing or blank.
4. Rotate once between portrait and landscape if the device and app allow it without interrupting the list.
5. Capture screenshots of the populated row and the fallback row.

## Assertions
- Rows with both values show the combined title in the form `City (UTC...)`.
- The subtitle remains IP only.
- Rows with missing city or UTC do not show empty parentheses or malformed punctuation.
- Fallback rows retain the existing city-or-name display semantics.

## Evidence Required
- Screenshot of a populated DEFAULT_V2 row.
- Screenshot of a fallback DEFAULT_V2 row.
- Optional UI tree or log snippet if the row text is ambiguous.

## Cleanup
- Return the device to portrait orientation if it was rotated.
- Leave the app on the main screen for the next case.

## Actual Result
- FAILED.
- Source was verified as DEFAULT_V2 in settings (`server_summary` = `Client for OpenVPN Gate`), but list cards still rendered raw IP as both title and subtitle for sampled countries.
- In captured country server list snapshots, `server_title` and `server_subtitle` were identical IP values (for example `185.249.198.156` / `185.249.198.156`) and no `City (UTC...)` pattern was present.
- Evidence:
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-settings-screen.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-country-list-2.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-vietnam-servers.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-vietnam-servers.png