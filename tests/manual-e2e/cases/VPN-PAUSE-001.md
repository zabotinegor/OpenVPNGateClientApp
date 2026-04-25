---
id: VPN-PAUSE-001
title: Connected state shows Pause and Stop controls
area: VPN Connection Controls
surface: android
---

## Preconditions
- Android app build with pause/resume feature is installed.
- Device has network access.
- Test server is selectable and reachable.
- User is on main screen and not connected before starting the case.
- If server shows `Not selected`, choose a server before tapping Start.
- Case is valid for mobile and TV surfaces; on TV use `.tv.SplashActivity` launch path.

## Steps
1. Launch the app.
2. Start VPN connection from the main screen.
3. Wait until connection state becomes connected.
4. Observe connection controls.

## Assertions
- Two controls are visible for active session: Pause and Stop connection.
- Stop action is exposed through `start_connection_button` in connected state (text changes to stop action).
- Pause action is exposed through `pause_connection_button`.
- Connection status indicates active VPN session (connected/running).
- VPN traffic is active (for example, connection timer is running and state is not paused/disconnected).

## Evidence Required
- Screenshot of connected state showing Pause and Stop controls.
- Optional short screen recording from connect action to connected state.
- Logcat snippet or app log line proving connected state.
- Optional UI dump proving ids: `pause_connection_button` and `start_connection_button`.

## Cleanup
- Tap Stop connection.
- Verify VPN disconnected before leaving the case.
