# SUB-04 SSE Debounce — Docs Audit Evidence

**Date:** 2026-06-25  
**Branch:** mp/sse-reliability-fixes (feature/sub-04-client-event-debounce merged into base)  
**Story:** docs/userstories/MP-20260623-sse-reliability-fixes/SUB-04-client-event-debounce.md  
**SDLC gate:** manualQa = passed, defects = [] — gate satisfied before docs work began.

## Changed files in scope

- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClient.kt`
- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/sse/SseServerEventsClientTest.kt`
- `tests/manual-e2e/stories/sub-04-client-event-debounce/` (QA suite, no durable docs impact)

## Behavior change introduced by SUB-04

`servers-changed` events are now routed through a `MutableSharedFlow(extraBufferCapacity=1, DROP_OLDEST)` with `debounce(500 ms)` before calling `doSync()`. Bursts of N events collapse into one sync call per quiet period. `onOpen` continues to call `doSync()` directly (no debounce).

New constant: `DEBOUNCE_MS = 500L`. Injectable `debounceMs` constructor parameter.

## Docs checked

| Document | Drift found | Action |
|---|---|---|
| `CLAUDE.md` (Key entry points table) | None — description "triggers server sync on `onOpen` and on `servers-changed` push events" remains factually correct; sync still fires for both, debounce is an implementation detail not visible at table description level. | No change |
| `src/docs/server-sync-flow.md` — Trigger Matrix | Minor gap: `servers-changed` row did not mention the 500 ms debounce. Document already mentions debouncing in the Main foreground row, so consistency and completeness called for it here. | **Updated** |
| `docs/runbooks/how-to.md` — "Verify SSE client connection on device" Step 4 | Minor gap: stated sync fires "followed shortly" after `servers-changed` log line; after SUB-04 the actual delay is ≥ 500 ms (debounce window). A developer timing log output would be confused. | **Updated** |
| `docs/runbooks/solutions.md` | No new non-obvious problem introduced by SUB-04. Debounce using `MutableSharedFlow` + `debounce()` is standard Kotlin coroutines pattern. | No change |
| `docs/runbooks/android-qa.md` | Existing `SseServerEventsClient` logcat tags cover new behavior. No new ADB commands needed for debounce verification; unit tests cover it. | No change |

## Updates applied

### 1. `src/docs/server-sync-flow.md`

Added note in Trigger Matrix "SSE server-changed push event" row: events are routed through `MutableSharedFlow` with `debounce(500 ms)` so a burst of N events collapses into one sync call (added in SUB-04).

### 2. `docs/runbooks/how-to.md`

Updated Step 4 "Verify event-triggered sync": explained the ≥ 500 ms debounce delay between `servers-changed` log line and `ServersV2SyncCoordinator` fetch logs. Clarified that `onOpen` sync fires without debounce (direct call).

## Verdict

GATE: PASS — 2 minor doc gaps corrected, no critical or major drift found.
