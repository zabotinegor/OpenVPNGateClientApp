# US-08 MQ-01: English To Russian Relocalization

## Preconditions
- Device: Mi 9 SE (adb id e26d5c2f), Android mobile.
- Build commit: f101f48217d824520be0f3cec8065ed06421efce.
- Settings precondition: `server_source=DEFAULT_V2`, `language=ENGLISH`.
- Existing selected country persisted in selection store.

## Steps
1. Open Settings from app drawer.
2. Ensure Server source is set to Client for OpenVPN Gate (DEFAULT_V2).
3. Change language from English to Russian in-session.
4. Return to main flow and inspect selected server label.
5. Capture logcat filtered by relocalization markers and selection store markers.

## Expected Result
- Selected country label relocalizes to Russian immediately.
- Relocalization path logs are present.
- Selected server index remains valid.

## Actual Result
- App language switched to Russian, but selected label remained `Australia` in main flow.
- Relocalization marker `syncSelectedCountryServersForRelocalization` was not observed.
- `syncSelectedCountryServers` logged skip (`country 'Australia' (code=AU) not in country list`).
- Selected index remained valid (`selected_country_index=0`).

## Evidence
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-final-main.png`
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-final-main.xml`
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-final-logcat.txt`
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-final-selection-summary.txt`

## Pass/Fail
FAIL