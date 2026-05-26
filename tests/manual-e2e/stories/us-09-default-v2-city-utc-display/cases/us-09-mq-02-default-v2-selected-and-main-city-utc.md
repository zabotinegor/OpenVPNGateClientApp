---
id: US-09-MQ-02
title: Main screen shows "City" label with city/UTC format (or fallbacks) for DEFAULT_V2
area: Android
surface: android
---

## Preconditions
- App is on the server list with DEFAULT_V2 source selected.
- A server with city/UTC metadata is available for selection.
- Network is available to ensure selection and UI update.

## Steps
1. Select a DEFAULT_V2 server that has city AND timezone metadata.
2. Wait for the main screen to update with selected server details.
3. Observe the main screen address section:
   - Check the label: should show "City" (or localized equivalent), NOT "Address"
   - Check the value: should show format `<city> (±HH:MM UTC)` (example: `Ho Chi Minh City (+07:00 UTC)`)
4. Capture screenshot of the main screen.
5. Rotate device between portrait and landscape (if supported) and verify the label/value remain consistent.
6. Capture screenshot after rotation.

## Assertions
- **When server has city+UTC**: 
  - Label shows "City" (not "Address")
  - Value shows `<city> (±HH:MM UTC)` format
  - Example: Label="City", Value="Ho Chi Minh City (+07:00 UTC)"
- **When server has city-only** (timezone missing):
  - Label still shows "City"
  - Value shows city name only
  - Example: Label="City", Value="Hanoi"
- **When server has no city** (IP fallback):
  - Label shows "Address"
  - Value shows server IP
  - Example: Label="Address", Value="203.0.113.45"
- Orientation change does not break label/value rendering
- No malformed placeholder text

## Evidence Required
- Screenshot of main screen showing "City" label with city/UTC format
- Screenshot after orientation change showing same contract
- Annotated XML tree dump if label or value text is unclear
- Evidence confirming selected server source is DEFAULT_V2

## Cleanup
- Keep the selected DEFAULT_V2 server for the persistence case.

## Actual Result
- UPDATED TO NEW SPECIFICATION (2026-05-20)
- Previous run showed main screen missing UTC in address value (FAILED)
- Evidence of previous failures:
  - artifacts/manual-qa/2026-05-20-us09-manual-qa-rerun/mq4-main-after-select.xml (city-only, no UTC)