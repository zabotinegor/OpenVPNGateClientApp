# SUB-05: Client Fallback SSE URL Support

**Repository:** OpenVPNClientClientApp  
**dependsOn:** none

## Scope

Extend `SseServerEventsClient` to cycle through a prioritised list of candidate SSE URLs (primary + fallback) on repeated connection failure, matching the existing REST fallback pattern already used for server list fetches.

## Acceptance Criteria

1. `SseServerEventsClient` accepts an ordered list of SSE endpoint URLs (primary + at least one fallback); on `onFailure` after exhausting the current URL's backoff budget (or a configurable attempt threshold), the client switches to the next URL.
2. The fallback URL is derived from `FALLBACK_SERVERS_URL` (the same constant used by the REST client) to ensure parity.
3. When the primary URL recovers, the client returns to it on the next reconnect cycle (round-robin or priority-first — implementation choice).
4. If all candidate URLs fail, the client continues retrying the list with the existing exponential backoff — no silent permanent failure.
5. A unit test simulates primary URL failure and verifies the fallback URL is tried.
6. WorkManager periodic refresh remains as a safety net and is not removed.

## Out of Scope

- Reconnect correctness / onOpen sync (covered in SUB-03).
- Client-side debounce (covered in SUB-04).
- Server-side changes.
