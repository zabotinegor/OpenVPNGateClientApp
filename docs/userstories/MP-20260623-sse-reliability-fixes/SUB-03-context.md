# Orchestration Context: SUB-03

## Discovered during master-plan BA (do not re-discover)

- Affected directories:
  - `src/core/.../servers/sse/SseServerEventsClient.kt` — `onOpen`, `onEvent`, `onClosed`, `onFailure`
- Stack markers: OkHttp3 `EventSource`, Kotlin coroutines, `ProcessLifecycleOwner`
- Integration points: `ServerSelectionSyncCoordinator.sync(forceRefresh, cacheOnly)`, `reconnectAttempt: AtomicInteger`, `STABLE_CONNECTION_RESET_DELAY_MS = 10 s`

## Key decisions made

- P1 (no onOpen sync) and P3 (backoff reset on every event) are combined: both are in `SseServerEventsClient.kt` and address reconnect behaviour as a cohesive fix
- The `onOpen` sync mirrors the pattern already used in `onEvent` (coroutine + `runCatching`)
- Removing `reconnectAttempt.set(0)` from `onEvent` does NOT affect the normal "received event = healthy" path because `onClosed`/`onFailure` already resets backoff when connection was stable for ≥ 10 s

## Dependencies from prior sub-plans

- None (client-side, independent of server sub-plans SUB-01/02).

## Skip in BA step

- Full repo scan (already done)
- SSE reconnect flow analysis (see OpenVPNGateClientReports/SSE improvements/SSE-analysis.md P1 and P3 sections)
