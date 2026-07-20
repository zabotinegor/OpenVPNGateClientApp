# MQ-US15-003 — Client for OpenVPN Gate (v2) source shows city+UTC display

## Preconditions
- App on Settings screen, "Server list source" expanded, currently on "VPN Gate"

## Steps
1. Select "Client for OpenVPN Gate" radio option
2. Navigate back to MainActivity
3. Observe the server info row (right column, below status)

## Expected
- Right column label reads "ГОРОД" (City) with a city name and UTC offset (v2 extended feature),
  not "АДРЕС" (Address)/raw IP

## Result: PASS
- Main screen showed "ГОРОД" / "Мельбурн (+10:00 UTC)" after switching back to "Client for OpenVPN
  Gate", confirming v2 extended city+UTC display is unaffected by the source-list reduction.

## Evidence
- Screenshot: phone_main_v2_restored.png (retained locally, not committed)

## Run date
2026-07-20
