# SUB-02: Android SSE Client — Re-poll on Server Change Event

**Master Plan:** MP-20260621-server-push-sse  
**dependsOn:** SUB-01 (server repo — SSE endpoint must be deployed)  
**Repository:** OpenVPNClientClientApp (Android client)

## Scope

Add an SSE client to the Android app that connects to `GET /api/v1/servers/events`. On receiving a `servers-changed` event, trigger an immediate re-fetch of the server list via the existing `ServersV2SyncCoordinator`. The existing `WorkManager` periodic refresh remains enabled as a fallback for when the push connection is unavailable.

## Acceptance Criteria

1. When the app is in the foreground and connected, an SSE connection is established to `/api/v1/servers/events`; the connection is maintained with automatic exponential-backoff reconnect (max 5 min between attempts).
2. On receiving a `servers-changed` event, `ServersV2SyncCoordinator` (or equivalent) triggers a server list re-fetch within 2 seconds.
3. The SSE connection is started when the app comes to the foreground and stopped (gracefully closed) when the app goes to the background, preventing battery drain.
4. The existing `WorkManager` periodic refresh (`ServerRefreshWorker`) continues to run unchanged; the SSE client is purely additive.
5. If the SSE connection is unavailable (network error, server 503), the client backs off and retries silently; no error is surfaced to the user UI.
6. Unit tests cover: event parsing, reconnect backoff strategy, lifecycle start/stop, and the trigger-re-fetch integration.

## Out of scope

- Delta events (add/remove payloads) — only the `servers-changed` ping is handled.
- Web admin UI.
- Replacing or modifying `WorkManager` scheduling logic.
- Changes to the server repository.
