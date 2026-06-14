# BUG: V2 server ping indicator always shows red

## User Story

As a user viewing the country server list, I want the ping indicator dot to reflect the actual server latency (green / yellow / red) so I can quickly identify fast servers.

## Background

The app has two server sources — legacy CSV (`ServerRepository`) and V2 API (`ServerV2`). CSV servers correctly compute `signalStrength` from ping values using a threshold table. V2 servers hardcode `SignalStrength.WEAK` in `toLegacyServer()`, causing every V2 server to display a red dot regardless of actual ping.

**Root cause:** `ServerV2.toLegacyServer()` (`src/core/…/servers/ServerV2.kt`) passes `signalStrength = SignalStrength.WEAK` unconditionally instead of deriving the value from `ping`.

## Acceptance Criteria

- **AC-1:** Given a V2 server with `ping` in `0..99`, when it appears in the country server list, then the dot is green (STRONG).
- **AC-2:** Given a V2 server with `ping` in `100..249`, when it appears in the country server list, then the dot is yellow (MEDIUM).
- **AC-3:** Given a V2 server with `ping ≥ 250`, when it appears in the country server list, then the dot is red (WEAK).

## Out of Scope

- Changing the threshold values.
- Changing CSV server behavior (`ServerRepository`).
- Changing any layout or drawable resources.

## Risks

- Low. Single-line change; the same threshold logic is already proven in `ServerRepository`.

## Implementation Notes

- Extract a `fun Int.toSignalStrength(): SignalStrength` extension in `SignalStrength.kt` to avoid duplicating the when-expression.
- Replace `SignalStrength.WEAK` in `toLegacyServer()` with `ping.toSignalStrength()`.
- Update `ServerRepository` to call the shared helper instead of its inline when-expression.

## Test Scenarios

| ping value | expected SignalStrength |
|-----------|------------------------|
| 0         | STRONG                 |
| 50        | STRONG                 |
| 99        | STRONG                 |
| 100       | MEDIUM                 |
| 249       | MEDIUM                 |
| 250       | WEAK                   |
| 999       | WEAK                   |

## Definition of Done

- [ ] `ping.toSignalStrength()` helper extracted to `SignalStrength.kt`
- [ ] `toLegacyServer()` uses the helper
- [ ] `ServerRepository` uses the helper
- [ ] Unit test covers all threshold bands (at least 0, 50, 99, 100, 249, 250, 999)
- [ ] `./gradlew testDebugUnitTestApp` passes
- [ ] Real-device QA confirms green/yellow/red dots appear correctly
