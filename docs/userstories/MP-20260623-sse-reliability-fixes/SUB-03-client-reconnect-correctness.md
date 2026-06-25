# SUB-03: Client SSE Reconnect Correctness — onOpen Sync and Backoff Fix

**Repository:** OpenVPNClientClientApp  
**dependsOn:** none

## Scope

Fix two related reconnect bugs in `SseServerEventsClient.kt`: (1) the client shows stale data after reconnecting because no sync is triggered on `onOpen`; (2) `reconnectAttempt.set(0)` in `onEvent` bypasses the stability-threshold guard and allows a hot reconnect loop when the server is in a degraded state.

## Acceptance Criteria

1. `onOpen` triggers `syncCoordinator.sync(forceRefresh = true, cacheOnly = false)` in a new coroutine; sync failures are caught and logged as warnings (not propagated).
2. The `reconnectAttempt.set(0)` call is removed from `onEvent`; backoff reset responsibility is left exclusively to the `onClosed`/`onFailure` stability-threshold check (`STABLE_CONNECTION_RESET_DELAY_MS`).
3. After a 2-minute offline period followed by reconnect, the client fetches fresh server data without waiting for a new `servers-changed` event.
4. When the server sends a `servers-changed` event and immediately drops the connection (degraded state), backoff grows as designed (5 s → 10 s → 20 s → …) and does not reset to 5 s on every reconnect cycle.
5. Unit/instrumentation tests cover the `onOpen` sync call and the absence of `reconnectAttempt.set(0)` in the `onEvent` path.

## Out of Scope

- Client-side event debounce (covered in SUB-04).
- Fallback SSE URL (covered in SUB-05).
- Changes to the `sync()` coordinator implementation itself.
