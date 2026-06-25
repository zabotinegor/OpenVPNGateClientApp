# SPEC-SUB-04: Client-Side SSE Event Debounce

**Story:** `docs/userstories/MP-20260623-sse-reliability-fixes/SUB-04-client-event-debounce.md`  
**Branch:** `feature/sub-04-client-event-debounce`  
**Commit:** c287234

## What changed

`SseServerEventsClient.handleServersChangedEvent()` now emits to a
`MutableSharedFlow<Unit>(extraBufferCapacity=1, DROP_OLDEST)` instead of launching a
direct sync coroutine. A `debounce(500ms)` collector on `clientScope` collapses rapid
bursts into a single `sync()` call. `onOpen` continues to fire a non-debounced sync
immediately on connection open.

## Test surfaces

Android only — no backend API or Web UI code changed.

## Test cases

- MQ-SUB04-001: App foreground → SSE connects, single sync fires on open
- MQ-SUB04-002: Foreground/background lifecycle — SSE stops and restarts
- MQ-SUB04-003: servers-changed event triggers re-fetch (non-burst)
- MQ-SUB04-004: Zero FATAL exceptions across connect/disconnect cycle
