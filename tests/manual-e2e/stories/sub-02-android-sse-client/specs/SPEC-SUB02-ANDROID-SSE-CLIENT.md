# SPEC-SUB02-ANDROID-SSE-CLIENT

## Story
SUB-02 — Android SSE Client — Re-poll on Server Change Event

## Feature under test
`SseServerEventsClient` registered via `ProcessLifecycleOwner` in `CoreApp`.

- Starts an SSE connection to `/api/v1/servers/events` when app enters foreground
- Triggers `ServerSelectionSyncCoordinator.sync()` on a `servers-changed` event
- Stops SSE connection gracefully when app goes to background
- Silently backs off with exponential backoff on connection errors (5 s → 5 min)
- Never surfaces errors to the UI

## Scope

Surface: Android only.
Backend SSE endpoint: `https://openvpngateclientgate.azurewebsites.net/api/v1/servers/events`

## Test cases

| Case | Title |
|------|-------|
| MQ-SUB02-001 | App launches cleanly with SSE client wired |
| MQ-SUB02-002 | SSE client attempts connection on foreground |
| MQ-SUB02-003 | SSE client stops gracefully on background |
| MQ-SUB02-004 | Regression — VPN connect and server list still work |

## Pass criteria

- Zero `FATAL EXCEPTION`, `KoinException`, `NoBeanDefFoundException` across all cases
- `SSE client starting` log on foreground
- `SSE client stopping` log on background
- `SSE connection opened` or backoff log on foreground (backend-dependent)
- `LEVEL_CONNECTED` on VPN connect
- Server list populates without error

## Suite

SUB02-CORE
