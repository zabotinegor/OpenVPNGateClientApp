---
id: US-09-MQ-04
title: Source switch shows correct label/value: "City" for DEFAULT_V2, "Address" for other sources
area: Android
surface: android
---

## Preconditions
- App is installed and working on the Android phone target.
- Currently on DEFAULT_V2 source with a selected server showing city/UTC.
- Source switching is available in Settings.
- Legacy CSV, VPN Gate, and Custom sources are available.

## Steps
1. Confirm current main screen shows "City" label with city/UTC format (from previous case).
2. Capture screenshot of DEFAULT_V2 main screen.
3. Open Settings and switch server source from DEFAULT_V2 to Legacy CSV.
4. Wait for server list to refresh/reload.
5. Select a server from Legacy CSV country list.
6. Observe main screen: should now show "Address" label (not "City") with IP value.
7. Capture screenshot of Legacy main screen.
8. Repeat steps 3-7 for VPN Gate source.
9. Repeat steps 3-7 for Custom source (if configured).
10. Return to DEFAULT_V2 source and reselect a server.
11. Confirm main screen reverts back to "City" label with city/UTC format.
12. Capture screenshot of reverted main screen.

## Assertions
- **DEFAULT_V2 source**:
  - Label shows "City"
  - Value shows city/UTC format (example: "Ho Chi Minh City (+07:00 UTC)")
- **Legacy CSV source**:
  - Label shows "Address"
  - Value shows server IP
  - NO city/UTC rendering or "City" label
- **VPN Gate source**:
  - Label shows "Address"
  - Value shows server IP
  - NO city/UTC rendering or "City" label
- **Custom source**:
  - Label shows "Address"
  - Value shows server IP
  - NO city/UTC rendering or "City" label
- **Switching back to DEFAULT_V2**:
  - Label reverts to "City"
  - Value shows city/UTC format again

## Evidence Required
- Screenshot of DEFAULT_V2 main screen with "City" label
- Screenshot of Legacy main screen with "Address" label and IP
- Screenshot of VPN Gate main screen with "Address" label and IP
- Screenshot of Custom main screen with "Address" label and IP (if available)
- Screenshot of DEFAULT_V2 after switch-back showing "City" label restored
- Annotated XML tree dumps if label switching is unclear

## Cleanup
- Leave source at DEFAULT_V2 for documentation clarity.

## Actual Result
- UPDATED TO NEW SPECIFICATION (2026-05-20)
- Previous run showed navigation drift during source switching (BLOCKED on MQ-7)
- City/UTC requirement now explicitly scoped to DEFAULT_V2 only
- Evidence of previous issue:
  - artifacts/manual-qa/2026-05-20-us09-manual-qa-rerun/mq6-source-dialog.xml