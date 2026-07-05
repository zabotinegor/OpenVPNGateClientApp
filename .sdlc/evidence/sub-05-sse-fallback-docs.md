# Docs — SUB-05 SSE Fallback URL

## Files checked

1. `CLAUDE.md` — Key entry points table, `SseServerEventsClient.kt` row
2. `src/docs/server-sync-flow.md` — SSE Server-Push Sync section, Endpoint derivation subsection
3. `src/docs/android-qa-adb-cookbook.md` — existing QA filters; no SSE section present
4. `docs/runbooks/` — does not exist; runbooks live under `src/docs/`

## Changes made

### CLAUDE.md
Updated the `SseServerEventsClient` row description to mention multi-URL fallback rotation:

> "SSE client — foreground-only long-poll; triggers server sync on connection open (`onOpen`) and on `servers-changed` push events; rotates through multiple candidate URLs (primary → fallback) after `urlFailureThreshold` consecutive failures"

### src/docs/server-sync-flow.md
Replaced the "Endpoint derivation" subsection with an expanded "Endpoint derivation and URL fallback (SUB-05)" subsection that covers:
- `defaultSseUrls()` builds an ordered list: primary from `PRIMARY_SERVERS_URL`, then fallback from `FALLBACK_SERVERS_URL`
- URL rotation logic: after `urlFailureThreshold` (default 3) failures the index advances circularly
- `failuresOnCurrentUrl` reset to 0 on successful `onOpen`
- Edge-case note for `urlFailureThreshold=0` (switches every failure, contained by backoff)

### src/docs/android-qa-adb-cookbook.md
Added a new "SSE Client Verification (SUB-05)" section at the bottom with:
- Log tag: `OpenVPNGateApp:SseServerEventsClient`
- Key logcat filter command
- Table of key log signals and their meanings (startup, URL rotation, backoff, sync trigger)

## GATE: PASS
