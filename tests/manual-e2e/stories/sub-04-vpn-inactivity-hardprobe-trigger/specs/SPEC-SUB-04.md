# SPEC-SUB-04: VPN Inactivity → Hardprobe Trigger Integration — Manual QA Spec

## Story reference
- Story ID: SUB-04
- Story path: docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-04-vpn-inactivity-hardprobe-trigger.md
- Branch: feature/sub-04-vpn-inactivity-hardprobe-trigger
- Device: Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY)

## What was changed
- `ServerAutoSwitcher` — enqueues a hardprobe via `ProbeRequestQueue` when switching away from a
  server due to connection failure or timeout
- `OpenVpnService` — enqueues a hardprobe when the watchdog detects a traffic-stall and triggers
  recovery
- Guard: no probe is enqueued on user-initiated disconnect, on `LEVEL_NONETWORK` device-loss, or
  when `serverId == 0`
- DI: `ProbeRequestQueue` wired in `CoreDi.kt` (introduced in SUB-02, visible in logcat at startup)

## QA scope
This sub-plan introduces no new UI surfaces. The Android QA surface covers:
1. DI graph initialization — `ProbeRequestQueue` and `HardProbeApiClient` must be resolvable at
   startup with no Koin error
2. Autoswitch path — probe is enqueued when the VPN autoswitches away from a failing server
3. Negative path — user-initiated disconnect must NOT enqueue a probe

## Acceptance criteria covered
- AC-8: Manual QA: connect to a VPN server, trigger autoswitch (e.g., disconnect WiFi briefly),
  and verify a probe request reaches the server (check server logs or API response).

## Out of scope
- Backend endpoint `POST /api/v2/servers/{id}/probe` (server-side unchanged)
- Watchdog-driven probe path (requires sustained traffic-stall; covered by unit tests)
- `LEVEL_NONETWORK` device-loss negative path (covered by unit tests)
- Zero-id guard (covered by unit tests)
- Web / API / DB surfaces (Android-only client app)

## Test cases
- MQ-SUB04-001: App launches, no Koin / ProbeRequestQueue errors in logcat
- MQ-SUB04-002: Connect VPN, trigger autoswitch, verify ProbeRequestWorker/probe tag in logcat
- MQ-SUB04-003: Connect VPN, user disconnect, verify NO probe enqueued
