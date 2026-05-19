---
id: US-09-MQ-04
title: Legacy CSV and VPN Gate keep existing server card behavior
area: Android
surface: android
---

## Preconditions
- App is installed and working on the Android phone target.
- Source switching is available in Settings.
- A country with server cards is available for both Legacy CSV and VPN Gate.

## Steps
1. Switch the server source from DEFAULT_V2 to Legacy CSV.
2. Open the country server list and inspect a few server cards.
3. Verify the selected server details and main screen still render without forced city + UTC formatting.
4. Switch the server source from Legacy CSV to VPN Gate.
5. Repeat the same checks on the VPN Gate list and selected/main surfaces.
6. Capture screenshots for both non-v2 sources.

## Assertions
- Legacy CSV cards keep the existing title/subtitle behavior.
- VPN Gate cards keep the existing title/subtitle behavior.
- No city + UTC formatting is forced onto non-v2 sources.
- Selection and navigation remain stable across both source switches.

## Evidence Required
- Screenshot of Legacy CSV cards.
- Screenshot of VPN Gate cards.
- Screenshot of selected/main surfaces after each switch if the formatting is ambiguous.

## Cleanup
- Return the source to DEFAULT_V2 if needed for later debugging.

## Actual Result
- NOT COMPLETED.
- Attempted source-switch verification encountered unstable navigation/state transitions during automated tap-driven execution, preventing a clean, deterministic Legacy/VPN Gate assertion set in this run.
- Partial evidence captured for source options visibility and current DEFAULT_V2 source summary.
- Evidence:
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-source-dialog-open.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-source-dialog-open.png
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-settings-screen.xml