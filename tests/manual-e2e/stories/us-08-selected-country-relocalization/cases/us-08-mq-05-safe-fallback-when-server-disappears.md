# US-08 MQ-05: Safe Fallback When Server Disappears

## Preconditions
- Requires successful relocalization path execution and a dataset change where selected server disappears.

## Steps
1. Prepare DEFAULT_V2 with selected server.
2. Trigger language switch and relocalization fetch.
3. Validate fallback behavior if selected server is absent post-refresh.

## Expected Result
- No crash.
- Deterministic safe index fallback.

## Actual Result
- Could not validate this scenario because relocalization path itself did not execute as expected in MQ-01/MQ-02.
- Primary story behavior failed first and blocked this dependent scenario.

## Evidence
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-final-logcat.txt`
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts2-logcat-generic.txt`

## Pass/Fail
FAIL