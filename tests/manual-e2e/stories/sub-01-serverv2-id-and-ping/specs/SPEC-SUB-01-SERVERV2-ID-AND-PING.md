# SPEC-SUB-01 — ServerV2 model: id and ping fields

## Story
SUB-01 — Android ServerV2 model: expose server `id` and `ping` from API  
Story path: `docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-01-serverv2-model-id-and-ping.md`

## Diff scope
`origin/dev..HEAD` (PR #92, branch `feature/sub-01-serverv2-id-and-ping` → dev)

## Changed production files
- `src/core/.../servers/ServerV2.kt` — added `id` and `ping` with `@SerializedName` and `= 0` defaults
- `src/core/.../servers/Server.kt` — `toLegacyServer()` maps `ping = ping` (not hardcoded 0)
- `src/core/.../servers/SelectedCountryStore.kt` — stores server `id` alongside existing fields

## Acceptance criteria in scope

| AC | Description |
|----|-------------|
| AC-5 | No crash / no JsonSyntaxException when existing cached JSON (without id/ping) is loaded |
| AC-6 | Server.ping from V2 path reflects ServerV2.ping value (currently 0 — no regression) |
| AC-3 | SelectedCountryStore saves and restores server id |
| AC-9 | assembleDebugApp and testDebugUnitTestApp pass |

## Test cases

| Case ID | Description |
|---------|-------------|
| MQ-SUB01-001 | App launches without crash after APK install |
| MQ-SUB01-002 | No JsonSyntaxException when cached server JSON (without id/ping) is loaded on startup |
| MQ-SUB01-003 | SelectedCountryStore saves and restores selection with server id |
| MQ-SUB01-004 | VPN connect/disconnect cycle completes without crash or fatal exception |
| MQ-SUB01-005 | [DEFERRED-PASS] Ping display shows 0 — expected until server team ships id+ping in v2 API |

## Test suite
`tests/manual-e2e/stories/sub-01-serverv2-id-and-ping/suites/SUB01-CORE.md`

## Device
Samsung Galaxy A71 SM_A715F Android 13 (R58N849XQEY)

## Notes
- TS-8 (MQ-SUB01-005) is deferred: server team has not yet shipped `id` and `ping` in the v2 API response. Ping showing 0 is expected behavior per R-1.
- Unit tests (TS-1 through TS-8B) all pass as automated JVM tests — see `ServerV2IdPingTest.kt`.
