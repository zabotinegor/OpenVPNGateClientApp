---
id: US-09-MQ-03
title: City/UTC display persists after app reopen and state restoration
area: Android
surface: android
---

## Preconditions
- A DEFAULT_V2 server with city/UTC is selected from the previous case.
- App is running on the main screen with city/UTC display visible.
- App can be backgrounded and relaunched without data wipe.

## Steps
1. Confirm main screen shows the city/UTC format (e.g., "Ho Chi Minh City (+07:00 UTC)") with "City" label.
2. Capture screenshot of current main screen state.
3. Background the app (press Home or use system switcher).
4. Wait 5-10 seconds.
5. Relaunch the app from the launcher or app switcher.
6. Wait for app to restore the previous selection and main screen to update.
7. Confirm main screen still shows the same city/UTC format for the previously selected server.
8. Capture screenshot of main screen after relaunch.

## Assertions
- Main screen after relaunch shows the SAME city/UTC value as before backgrounding
  - Example: Before "Ho Chi Minh City (+07:00 UTC)" → After "Ho Chi Minh City (+07:00 UTC)"
- Label remains "City" (not reset to "Address")
- No crash or blank/placeholder values appear during rehydration
- Orientation is preserved or recovers without loss of display data

## Evidence Required
- Screenshot before backgrounding showing city/UTC display
- Screenshot after app relaunch showing same city/UTC value
- Annotated XML tree dump if persistence is unclear
- Optional logcat snippet showing successful state restoration

## Cleanup
- Leave the app on main screen with selected DEFAULT_V2 server for next case.

## Actual Result
- UPDATED TO NEW SPECIFICATION (2026-05-20)
- Previous run showed city-only value persisting but UTC not rendered (FAILED)
- Evidence of previous persistence issue:
  - artifacts/manual-qa/2026-05-20-us09-manual-qa-rerun/mq5-main-after-restart.xml (city-only, no UTC)