---
id: VPN-PAUSE-003
title: Stop action works from connected and paused states
area: VPN Connection Controls
surface: android
---

## Preconditions
- Android app build with pause/resume feature is installed.
- Device has network access.
- Test uses one reachable VPN server.

## Steps
1. Launch app and connect VPN.
2. In connected state, tap Stop connection (`start_connection_button` in active state).
3. Verify disconnected state and visible controls.
4. Connect VPN again.
5. Tap Pause and wait until paused state is visible.
6. In paused state, tap Stop connection (`start_connection_button` remains stop action).
7. Verify disconnected state and visible controls.

## Assertions
- Stop from connected state disconnects VPN (`AC3a`).
- Stop from paused state disconnects VPN (`AC3b`).
- After each stop action, only Start connection is visible.
- Pause/Resume and Stop controls are hidden in disconnected state.
- `pause_connection_button` is absent in disconnected state.

## Evidence Required
- Screenshot after stop from connected state.
- Screenshot after stop from paused state.
- Logs proving disconnect events for both paths.

## Cleanup
- Ensure VPN is disconnected.
- Return app to default main screen.
