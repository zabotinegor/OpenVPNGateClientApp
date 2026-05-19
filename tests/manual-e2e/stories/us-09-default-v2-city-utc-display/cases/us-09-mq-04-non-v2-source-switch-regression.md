---
id: US-09-MQ-04
title: Source switch parity keeps Server/Address contract for non-v2 and custom
area: Android
surface: android
---

## Preconditions
- App is installed and working on the Android phone target.
- Source switching is available in Settings.
- A country with server cards is available for Legacy CSV, VPN Gate, and Custom.

## Steps
1. Switch the server source from DEFAULT_V2 to Legacy CSV.
2. Open the country server list and select a server.
3. Verify main details show `Server=current/total` and `Address=IP`.
4. Switch the server source from Legacy CSV to VPN Gate.
5. Repeat the same checks on VPN Gate.
6. Switch source to Custom (with a valid custom endpoint already configured) and repeat the same checks.
7. Capture screenshots for all checked sources.

## Assertions
- Legacy CSV details follow `Server=current/total` and `Address=IP`.
- VPN Gate details follow `Server=current/total` and `Address=IP`.
- Custom details follow `Server=current/total` and `Address=IP`.
- Selection and navigation remain stable across all source switches.

## Evidence Required
- Screenshot of main details for Legacy CSV.
- Screenshot of main details for VPN Gate.
- Screenshot of main details for Custom.
- Optional list-card screenshots if a source-specific rendering issue is suspected.

## Cleanup
- Return the source to DEFAULT_V2 if needed for later debugging.

## Actual Result
- SUPERSEDED.
- Previous execution in this file used the old city/UTC expectation set and did not include Custom source parity under the new details contract.
- Rerun required with current assertions (`Server=current/total`, `Address=IP`) across Legacy/VPN Gate/Custom.
- Evidence:
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-source-dialog-open.xml
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-source-dialog-open.png
	- artifacts/manual-qa/2026-05-19-us09-manual-qa/us09-settings-screen.xml