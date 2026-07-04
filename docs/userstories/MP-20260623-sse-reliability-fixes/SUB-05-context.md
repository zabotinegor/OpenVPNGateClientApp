# Orchestration Context: SUB-05

## Discovered during master-plan BA (do not re-discover)

- Affected directories:
  - `src/core/.../servers/sse/SseServerEventsClient.kt` — `defaultSseUrl()`, `connectOnce()`, `onFailure`
  - Wherever `FALLBACK_SERVERS_URL` and `PRIMARY_SERVERS_URL` / `ApiConstants` are defined
- Stack markers: OkHttp3 `EventSource`, `PrimaryDomainRoutes.sseServersEventsUrl()`, `ApiConstants`
- Integration points: REST client fallback pattern (already uses a fallback URL list); WorkManager periodic refresh (safety net, must not be removed)

## Key decisions made

- The fallback URL is constructed from `FALLBACK_SERVERS_URL` using the same `sseServersEventsUrl()` helper
- URL switching strategy: primary-first with threshold (e.g. 3 consecutive failures on current URL → try next); not strict round-robin
- This story touches `SseServerEventsClient.kt` — if run in parallel with SUB-03 or SUB-04, coordinate to avoid merge conflicts

## Dependencies from prior sub-plans

- None (independent of all other sub-plans).

## Skip in BA step

- Full repo scan (already done)
- Fallback URL analysis (see OpenVPNGateClientReports/SSE improvements/SSE-analysis.md P5 section)
