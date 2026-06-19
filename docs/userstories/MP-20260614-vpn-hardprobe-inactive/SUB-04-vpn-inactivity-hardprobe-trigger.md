---
id: SUB-04
title: "VPN inactivity → hardprobe trigger integration"
masterPlanId: MP-20260614-vpn-hardprobe-inactive
dependsOn: [SUB-01, SUB-02, SUB-03]
---

# SUB-04: VPN inactivity → hardprobe trigger integration

## Scope

Detect all cases where the VPN becomes inactive due to a server-side failure and enqueue a
hardprobe request for the affected server via the durable queue from SUB-02. Covers both
autoswitch-driven and watchdog-driven inactivity paths in `ServerAutoSwitcher` and `OpenVpnService`.

The BA phase for this sub-plan must enumerate and confirm all trigger points, including:
- `ServerAutoSwitcher.requestSwitchNow()` — timeout after `LEVEL_CONNECTING_NO_SERVER_REPLY_YET`
  or `LEVEL_CONNECTING_SERVER_REPLIED`, plus immediate triggers on `LEVEL_AUTH_FAILED`.
- `LEVEL_NONETWORK` from AIDL source in `ServerAutoSwitcher.onEngineLevel()`.
- Watchdog recovery in `OpenVpnService.handleConnectedProbeResult()` (tunnel up but traffic stalled).
- Any additional inactivity paths discovered during BA.

Only server-failure-caused inactivity should trigger a probe. User-initiated disconnects must NOT
enqueue a probe. The BA phase must verify the existing `isReconnect` / `extraAutoSwitchKey` flag
is usable to distinguish autoswitch from user stop.

## Acceptance Criteria

1. When `ServerAutoSwitcher` switches away from a server due to connection failure or timeout,
   a hardprobe request for that server's `id` is enqueued via `ProbeRequestQueue`.
2. When the watchdog in `OpenVpnService` triggers a recovery (traffic-stall detection), a
   hardprobe request is enqueued for the currently-connected server's `id`.
3. User-initiated disconnects do NOT enqueue a probe.
4. `LEVEL_NONETWORK` from a device-network-loss (not AIDL autoswitch) does NOT enqueue a probe
   (device lost internet, not server failure) — BA phase must confirm the correct distinction.
5. No probe is enqueued if the `serverId` is `0` (unknown / pre-server-team-delivery fallback).
6. Unit tests cover: autoswitch probe enqueue, watchdog probe enqueue, user-stop no-enqueue,
   zero-id guard, and NONETWORK device-loss no-enqueue.
7. The build passes (`assembleDebugApp` + `testDebugUnitTestApp`).
8. Manual QA: connect to a VPN server, trigger autoswitch (e.g., disconnect WiFi briefly), and
   verify a probe request reaches the server (check server logs or API response).

## Out of scope

- Server-side probe execution or result handling.
- UI feedback for probe status.
- Probe request queue implementation (SUB-02) and API client (SUB-03).
- `ServerV2` model changes (SUB-01).

## dependsOn note

Depends on SUB-01 (server id available in model), SUB-02 (durable probe queue), SUB-03 (API client
injected into the queue worker).
