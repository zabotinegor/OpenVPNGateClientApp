# US-08 MQ-06: Non-V2 Sources No Relocalization Path

## Preconditions
- Device: Mi 9 SE.
- Non-v2 source active (`VPNGATE` during spot check).

## Steps
1. Change language in Settings while non-v2 source is active.
2. Capture logcat markers.

## Expected Result
- No DEFAULT_V2 relocalization path call for non-v2 sources.
- Non-v2 behavior remains unchanged.

## Actual Result
- Language change event logged for non-v2 state.
- No relocalization path marker observed.
- Behavior is consistent with non-v2 expectation for this spot-check.

## Evidence
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-logcat.txt`

## Pass/Fail
PASS