# SPEC-SUB-05: Client Fallback SSE URL Support

**Story:** SUB-05-client-fallback-sse-url  
**Surface:** Android device

## Objective

Verify that `SseServerEventsClient` accepts an ordered list of SSE endpoint URLs and
cycles through them on repeated failures. In the default production configuration only
`PRIMARY_SERVERS_URL` is SSE-capable; `FALLBACK_SERVERS_URL` is a CSV URL and is
excluded from `defaultSseUrls()`. WorkManager periodic refresh is the safety net when
the primary SSE endpoint is unreachable.

## Device

Samsung Galaxy A71 SM-A715F Android 13 (<your-device-serial>)

## Prerequisites

- Debug APK built from `feature/sub-05-client-fallback-sse-url` (commit bc7275a)
- Device connected via ADB

## Test cases

| ID | Description |
|----|-------------|
| MQ-SUB05-001 | App launches without crashes; SSE client starts with 1 candidate URL (PRIMARY only) |
| MQ-SUB05-002 | SSE connects to primary URL first; logcat shows correct URL |
| MQ-SUB05-003 | WorkManager SystemJobService remains active (periodic refresh safety net) |
| MQ-SUB05-004 | Zero FATAL exceptions after cold launch |
