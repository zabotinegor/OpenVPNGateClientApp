# US-08 MQ-04: Preserve Index When Server Exists

## Preconditions
- Device: Mi 9 SE.
- Existing persisted selection with valid server index.

## Steps
1. Execute language switch scenarios under DEFAULT_V2.
2. Read persisted selection store values after switch attempts.

## Expected Result
- Selected server index remains valid and deterministic.

## Actual Result
- Selected index remained `0` and app remained stable (no crash).
- IP/address stayed valid in main flow.

## Evidence
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-final-selection-summary.txt`
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-final-main.xml`

## Pass/Fail
PASS