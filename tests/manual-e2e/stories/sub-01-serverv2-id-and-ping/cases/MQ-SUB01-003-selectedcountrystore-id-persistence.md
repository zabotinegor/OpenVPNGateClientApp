# MQ-SUB01-003 — SelectedCountryStore saves and restores server id (AC-3/TS-3)

## Preconditions
- App installed from branch HEAD
- Server list for country "Республика Литва" (LT) loaded with 2 servers
- ADB connected

## Steps
1. Launch app and let server sync complete
2. Monitor logcat for `SelectedCountryStore` save/restore operations
3. Verify `saveSelectionPreservingIndex` log entry includes `current_restored=true`
4. Verify server count round-trips correctly

## Expected
- `saveSelectionPreservingIndex: country=..., count=N->N, current_restored=true`
- No id-field parsing error in StoredServer deserialization
- `SelectedCountryStore.getServers()` uses `optInt(KEY_JSON_SERVER_ID, 0)` which defaults to 0 for legacy JSON

## Result: PASS (AC-3/TS-3)
- logcat: `SelectedCountryStore: saveSelectionPreservingIndex: country=Республика Литва, count=2->2, current_restored=true`
- logcat: `ensureIndexForConfig: matched by config+ip index=2/2 ip=87.247.127.209`
- Unit test TS-5 (`selectedCountryStore_round_trip_with_id`) PASSED confirming id=42 round-trip
- Unit test TS-6 (`selectedCountryStore_reads_legacy_json_without_id_defaults_to_zero`) PASSED confirming backward-compat

## Evidence
- logcat (pid=20642): two `saveSelectionPreservingIndex` entries, both `current_restored=true`
- Unit test output: `ServerV2IdPingTest > selectedCountryStore_round_trip_with_id PASSED`
- Unit test output: `ServerV2IdPingTest > selectedCountryStore_reads_legacy_json_without_id_defaults_to_zero PASSED`

## Run date
2026-06-14
