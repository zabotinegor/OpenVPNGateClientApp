# MQ-SUB01-005 — Ping display shows 0 [DEFERRED-PASS / TS-8]

## Preconditions
- App connected to DEFAULT_V2 server list
- Server team must have shipped `ping` field in v2 API response

## Steps
1. Open server list view for DEFAULT_V2 country
2. Observe ping value displayed for at least one server

## Expected (post-server-delivery)
- At least one server shows non-zero ping value > 0

## Result: DEFERRED-PASS (TS-8)
This test case is deferred pending server team delivery of `id` and `ping` fields in the v2 API response (tracked in server repo `US-12-server-list-expose-id.md`).

Current behavior: ping displays as 0 for all DEFAULT_V2 servers because the API response does not yet include the `ping` field. This is expected per R-1 in the story.

The implementation is correct: `toLegacyServer()` now maps `ping = ping` (not hardcoded 0), and `ServerV2.ping` defaults to 0 when absent from JSON. No regression — behavior is identical to pre-SUB-01 state for the 0-value case.

AC-6 is verified at the unit level: `ServerV2IdPingTest > toLegacyServer_propagates_ping_75 PASSED` confirms propagation works when the value is non-zero.

## Retest trigger
Re-run this case after server team confirms `ping` is included in `VpnServerV2ListItemDto` API response.

## Run date
2026-06-14
