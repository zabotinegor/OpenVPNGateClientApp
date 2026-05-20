---
id: US-09-MQ-01
title: DEFAULT_V2 server list shows city/UTC on cards (2-line), city-only (1-line), or IP fallback
area: Android
surface: android
---

## Preconditions
- App is installed and launched on the Android phone target.
- Server source is set to "Client for OpenVPN Gate" (DEFAULT_V2).
- Network is available and the DEFAULT_V2 server list can refresh with city/UTC metadata.
- At least one country has multiple servers with mixed city/UTC availability (some with both, some with city-only, some IP-only).

## Steps
1. Open the country server list for a DEFAULT_V2 country (preferably Vietnam with known city/UTC data).
2. Observe server cards in the list:
   - Identify cards with BOTH city and timezone data → should render 2 lines (city on line 1, timezone on line 2)
   - Identify cards with city-only data → should render 1 line with city name
   - Identify cards with missing city → should render 1 line with server IP
3. Capture screenshot of the full server list showing mixed card formats.
4. Tap on a server with city+UTC data to select it.
5. Capture screenshot of selection.

## Assertions
- **For servers WITH city+UTC**: Card displays exactly 2 lines: (1) city name, (2) timezone in format `±HH:MM UTC`
  - Example: Line 1: "Ho Chi Minh City", Line 2: "+07:00 UTC"
- **For servers WITH city-only**: Card displays exactly 1 line with city name
  - Example: "Hanoi"
- **For servers WITHOUT city**: Card displays exactly 1 line with server IP
  - Example: "203.0.113.45"
- No malformed placeholder text or mixed city/IP on single cards
- Card layout, country flag, ping/signal indicators remain unchanged

## Evidence Required
- Screenshot of DEFAULT_V2 server list showing mixed card formats (2-line with UTC, 1-line city, 1-line IP)
- Annotated XML tree dump if card text content is unclear in screenshot
- Evidence that source is confirmed as DEFAULT_V2

## Cleanup
- Leave the app on the selected server screen for the next case.

## Actual Result
- UPDATED TO NEW SPECIFICATION (2026-05-20)
- Previous city/UTC implementation was incomplete; server list cards missing UTC subtitle rendering
- Evidence of previous run (FAILED):
  - artifacts/manual-qa/2026-05-20-us09-manual-qa-rerun/mq1-vn-servers.xml (no server_subtitle with UTC found)