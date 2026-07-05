# Orchestration Context: SUB-02

## Discovered during master-plan BA (do not re-discover)

- Affected directories:
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/` — `ServersV2Api`, `ServersV2SyncCoordinator`, `ServersV2Repository`, `ServerListInteractor`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/refresh/` — `ServerRefreshScheduler`, `ServerRefreshWorker`, `ServerRefreshFeatureFlags`
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/di/CoreDi.kt` — DI registration
- Stack markers found: Kotlin, Retrofit + OkHttp (existing network layer), WorkManager (periodic refresh)
- Integration points:
  - `ServersV2SyncCoordinator` — call this on SSE event to trigger re-fetch
  - `ServerRefreshWorker` (WorkManager) — must remain unchanged; SSE is purely additive
  - App lifecycle: start SSE on foreground, stop on background (use `ProcessLifecycleOwner` or equivalent)
  - OkHttp is already a dependency — use `okhttp3.sse.EventSources` (from `com.squareup.okhttp3:okhttp-sse`) for SSE

## Key decisions made

- Transport: OkHttp SSE (`EventSource`) — no additional major dependencies
- Payload: listen for `event: servers-changed`; ignore all other event types and heartbeat comments
- Reconnect: exponential backoff starting at 5 s, capping at 5 min; reset on successful event receipt
- Lifecycle: foreground = connect; background = disconnect (no Doze/background battery impact)
- WorkManager polling untouched

## Dependencies from prior sub-plans

- SUB-01 output: `GET /api/v1/servers/events` SSE endpoint is live and emitting `servers-changed` events on server activation/deactivation

## Skip in BA step

- Full repo scan (already done)
- Stack detection (Kotlin/OkHttp/WorkManager already confirmed)
- Architecture overview (core module structure already mapped)
