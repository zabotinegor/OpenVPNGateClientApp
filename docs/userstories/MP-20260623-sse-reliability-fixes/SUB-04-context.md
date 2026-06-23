# Orchestration Context: SUB-04

## Discovered during master-plan BA (do not re-discover)

- Affected directories:
  - `src/core/.../servers/sse/SseServerEventsClient.kt` — `onEvent`, `handleServersChangedEvent`, client coroutine scope
- Stack markers: Kotlin coroutines, `kotlinx.coroutines.flow.MutableSharedFlow`, `debounce`, `BufferOverflow.DROP_OLDEST`
- Integration points: `ServerSelectionSyncCoordinator.sync()`, `clientScope` (lifecycle-bound coroutine scope)

## Key decisions made

- Debounce window: 500 ms (shorter than server's 300 ms window to ensure client collapses even when server debounce hasn't fired)
- Flow type: `MutableSharedFlow<Unit>` with `extraBufferCapacity = 1` and `DROP_OLDEST` — avoids blocking the `onEvent` callback thread
- The `_syncTrigger` flow is collected in `init` or `onStart`; cancelled when `clientScope` is cancelled
- `onOpen` sync from SUB-03 is a separate code path and is NOT debounced (one-shot on reconnect)

## Dependencies from prior sub-plans

- None. SUB-03 and SUB-04 both touch `SseServerEventsClient.kt` — if run in parallel, coordinate to avoid merge conflicts on that file.

## Skip in BA step

- Full repo scan (already done)
- Event storm analysis (see D:\Apps\OpenVPNClient\OpenVPNGateClientReports\SSE improvements\SSE-analysis.md P2 section)
