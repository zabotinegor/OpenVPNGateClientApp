# SUB01-CORE — ServerV2 id and ping — Manual QA Suite

## Story
SUB-01 (PR #92): Android ServerV2 model expose id and ping  
Branch: `feature/sub-01-serverv2-id-and-ping` → dev

## Device
Samsung Galaxy A71 SM_A715F Android 13 (R58N849XQEY)

## Run date
2026-06-14

## Overall result: PASS

## Case results

| Case | Description | Result |
|------|-------------|--------|
| MQ-SUB01-001 | App launches without crash | PASS |
| MQ-SUB01-002 | No JsonSyntaxException on cached JSON without id/ping (AC-5) | PASS |
| MQ-SUB01-003 | SelectedCountryStore saves/restores server id (AC-3/TS-3) | PASS |
| MQ-SUB01-004 | VPN connect/disconnect cycle no crash | PASS |
| MQ-SUB01-005 | Ping display (TS-8) | DEFERRED-PASS |

## AC verdict

| AC | Status | Evidence |
|----|--------|---------|
| AC-5 | PASS | Zero JsonSyntaxException in logcat; `fetchAllPages[LT]: fetched 2 servers` — clean Gson deserialize |
| AC-6 | PASS (unit) | `toLegacyServer_propagates_ping_75 PASSED`; production shows 0 (expected — server not yet shipping field) |
| AC-3 | PASS | `saveSelectionPreservingIndex: count=2->2, current_restored=true`; unit round-trip id=42 PASSED |
| AC-9 | PASS | `assembleDebugApp` PASS (pre-built APK); `testDebugUnitTestApp` — ServerV2IdPingTest 10/10 PASSED |
| TS-8 | DEFERRED-PASS | Ping=0 as expected; server team has not yet shipped id+ping in v2 API |

## Key logcat evidence
```
06-14 19:34:18.290  ServersV2Repository: getServersForCountry[LT]: serverCount=2 locale=ru
06-14 19:34:18.456  SelectedCountryStore: saveSelectionPreservingIndex: country=Республика Литва, count=2->2, current_restored=true
06-14 19:38:33.605  OpenVpnService: Engine level=LEVEL_CONNECTED detail=CONNECTED source=AIDL
06-14 19:38:34.481  OpenVpnService: Watchdog: healthy source=traffic trafficDelta=4408
06-14 19:41:46.069  ConnectionState: App state: DISCONNECTING -> DISCONNECTED
```

## Unit test results
```
ServerV2IdPingTest > serverV2_parses_id_and_ping_from_json PASSED
ServerV2IdPingTest > serverV2_defaults_id_and_ping_when_missing PASSED
ServerV2IdPingTest > toLegacyServer_propagates_ping_75 PASSED
ServerV2IdPingTest > toLegacyServer_ping_zero_default_no_regression PASSED
ServerV2IdPingTest > toLegacyServer_propagates_id PASSED
ServerV2IdPingTest > selectedCountryStore_round_trip_with_id PASSED
ServerV2IdPingTest > selectedCountryStore_reads_legacy_json_without_id_defaults_to_zero PASSED
ServerV2IdPingTest > serverV2_old_format_json_backward_compatible PASSED
ServerV2IdPingTest > serverV2_no_arg_constructor_has_zero_id_and_ping PASSED
ServerV2IdPingTest > serverV2_gson_empty_object_yields_zero_id_and_ping PASSED
Total: 10/10 PASSED
```
