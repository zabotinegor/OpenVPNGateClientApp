# MQ-SUB01-002 — No JsonSyntaxException when cached JSON without id/ping is loaded

## Preconditions
- App installed from branch HEAD (contains new `id` and `ping` fields with defaults)
- Device has previously cached ServerV2 JSON (from earlier version, without `id`/`ping` keys)
- ADB connected

## Steps
1. Launch app (cached server data present from previous session)
2. Monitor logcat for `ServersV2Repository` cache read and deserialization
3. Check logcat for `JsonSyntaxException`, `GsonException`, or any deserialization error

## Expected
- Server list loads from cache without exception
- `ServersV2Repository: getServersForCountry[LT]: serverCount=N` appears without error
- No `JsonSyntaxException` in logcat

## Result: PASS (AC-5)
- logcat: `OpenVPNGateApp:ServersV2Repository: getServersForCountry[LT]: serverCount=2 locale=ru`
- logcat: `fetchAllPages[LT]: fetched 2 servers (raw=2)` — clean deserialization
- Zero JsonSyntaxException entries in app-scoped logcat across the full session
- Backward-compat confirmed: `optInt(KEY_JSON_SERVER_ID, 0)` returns 0 for pre-existing JSON without "id" key

## Evidence
- logcat grep `JsonSyntax|exception|FATAL` on pid=20642: (empty — zero results)
- logcat: `saveSelectionPreservingIndex: country=Республика Литва, count=2->2, current_restored=true` — confirms cache read succeeded

## Run date
2026-06-14
