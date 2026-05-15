# US-08 MQ-03: Cache Warm Language Switch No Mixed Labels

## Preconditions
- Device: Mi 9 SE (adb id e26d5c2f).
- DEFAULT_V2 selected, cached state present.

## Steps
1. Keep cached selected country state warm.
2. Switch app language in Settings.
3. Return to main screen and inspect labels.

## Expected Result
- No cross-language mix on the same main screen.
- Selected country display label follows active language.

## Actual Result
- Main screen controls switched to Russian.
- Selected-country control remained `Australia` (English), creating mixed-language UI.

## Evidence
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-final-main.xml`
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-final-main.png`

## Pass/Fail
FAIL