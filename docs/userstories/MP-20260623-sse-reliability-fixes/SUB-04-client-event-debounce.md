# SUB-04: Client-Side SSE Event Debounce

**Repository:** OpenVPNClientClientApp  
**dependsOn:** none

## Scope

Prevent the Android client from launching N parallel `sync()` coroutines when the server emits N rapid-succession `servers-changed` events. Replace the direct `sync()` call in `onEvent` with a conflated `MutableSharedFlow` + `debounce()` pipeline so that bursts collapse into a single sync per quiet period.

## Acceptance Criteria

1. `onEvent("servers-changed")` emits to a `MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST)`; the flow consumer applies `debounce(500)` before calling `doSync()`.
2. Receiving 50 `servers-changed` events within 200 ms triggers exactly one `sync()` call, not 50.
3. The debounce introduces no observable user-facing delay for isolated events (single event still syncs within 500 ms + network RTT).
4. The flow is collected on the client's coroutine scope and cancelled with the SSE lifecycle (`onStop`/disconnect).
5. Unit tests verify the debounce collapse behaviour and confirm `sync()` is called exactly once per burst.

## Out of Scope

- Server-side debounce (covered in SUB-01, server repo).
- Reconnect behaviour changes (covered in SUB-03).
- Fallback URL (covered in SUB-05).
