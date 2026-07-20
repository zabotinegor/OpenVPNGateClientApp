# MQ-US15-002 — VPN Gate source shows IP-based display (no city/UTC)

## Preconditions
- App on Settings screen, "Server list source" expanded

## Steps
1. Select "VPN Gate" radio option
2. Navigate back to MainActivity
3. Observe the server info row (right column, below status)

## Expected
- Right column label reads "АДРЕС" (Address) showing a raw IP, not "ГОРОД" (City) with UTC offset
- No city name or UTC offset displayed (CSV-only VPNGATE source has no locale-parameterized city data)

## Result: PASS
- Main screen showed "АДРЕС" / "101.188.56.82" (IP-based) after switching to VPN Gate, replacing
  the previous "ГОРОД" / "Мельбурн (+10:00 UTC)" display shown under the v2 source.

## Evidence
- Screenshot: phone_main_vpngate.png (retained locally, not committed)

## Run date
2026-07-20
