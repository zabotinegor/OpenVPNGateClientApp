# SPEC-BUG-FGS-CRASH-RAPID-RECONNECT

## Story
`docs/userstories/BUG-fgs-crash-rapid-reconnect-and-probe-type-erasure.md`

## Branch
`fix/fgs-crash-rapid-reconnect`

## Diff scope
`origin/dev..HEAD`

## Changed files (production)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
  - `syncEngineState()`: extends guard to exclude `LEVEL_NOTCONNECTED` and `LEVEL_NONETWORK`
    from the `exitControllerForeground()` call, preventing premature foreground exit on idle levels
- `src/core/consumer-rules.pro`
  - Adds `-keep interface ...ProbeApi { *; }` to prevent R8 from stripping `Response<Unit>`
    generic signature at minification time

## Test surface
Android — physical device Samsung Galaxy A71 SM-A715F Android 13 (ADB serial R58N849XQEY)

## Acceptance criteria under test
1. 3rd rapid-connect crash no longer occurs (3× connect/disconnect → connect → no crash)
2. No `RemoteServiceException` in logcat for `OpenVpnService`
3. `ProbeRequestWorker` no longer throws `IllegalArgumentException` for type erasure
4. No logcat `W/ProbeRequestWorker` for "Response must include generic type"
5. No regression on normal connect / disconnect / reconnect
6. Probe is enqueued and succeeds on VPN disconnect

## Test cases
- `MQ-BUG-RRC-001-rapid-reconnect-no-crash.md`
- `MQ-BUG-RRC-002-normal-vpn-connect.md`
- `MQ-BUG-RRC-003-disconnect-reconnect-stability.md`
- `MQ-BUG-RRC-004-probe-enqueued-on-disconnect.md`

## Suite
`BUG-RRC-CORE.md`
