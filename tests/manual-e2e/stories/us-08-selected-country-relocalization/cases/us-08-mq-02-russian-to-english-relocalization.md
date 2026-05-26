# US-08 MQ-02: Russian To English Relocalization

## Preconditions
- Device: Mi 9 SE (adb id e26d5c2f), Android mobile.
- Settings precondition: `server_source=DEFAULT_V2`, `language=RUSSIAN`.
- Selected country already persisted.

## Steps
1. In Settings, change language from Russian to English.
2. Return to main flow.
3. Capture logcat markers for language change and selected-country synchronization.

## Expected Result
- Selected country label relocalizes to English for the same selected country.
- Relocalization path executes and logs marker.

## Actual Result
- Language change to English was applied.
- Relocalization marker was not observed.
- Follow-up run still kept selected country value `Australia` and later EN->RU switch also stayed `Australia`.

## Evidence
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts2-logcat-generic.txt`
- `manual-qa/2026-05-14-us08-manual-qa/us08-user-settings-current.xml`
- `manual-qa/2026-05-14-us08-manual-qa/us08-ts1-final-main.xml`

## Pass/Fail
FAIL