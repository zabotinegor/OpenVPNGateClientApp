---
id: VPN-PAUSE-002
title: Pause and resume transitions stay smooth and do not bounce
area: VPN Connection Controls
surface: android
---

## Preconditions
- Android app build with pause/resume feature is installed.
- Device has network access.
- User is connected to VPN from the main screen.

## Steps
1. From connected state, tap Pause.
2. Immediately after tapping Pause, sample status text and visible controls during the transition.
3. Wait for state transition to paused.
4. Observe connection controls.
5. Tap Resume.
6. During reconnecting, observe controls and text on `start_connection_button`.
7. Continue sampling until VPN returns to connected.
8. Observe connection controls again.

## Assertions
- Immediately after tapping Pause, UI leaves the connected presentation and does not remain visually stuck in `Connected`.
- During `connected -> paused`, an intermediate pausing state is observable through status text, logs, or focused polling evidence.
- In paused state, two controls are visible: Resume (`pause_connection_button`) and Stop (`start_connection_button`).
- Paused state is visible in status text (or equivalent paused indicator).
- Immediately after tapping Resume, `pause_connection_button` is hidden until VPN returns to connected state.
- During `paused -> connected` transition, `start_connection_button` shows connecting progress statuses (for example TCP/connect/auth/config stages), same behavior as `disconnected -> connected`.
- After Resume is tapped, paused UI must not reappear before connected is restored; specifically, `pause_connection_button` with Resume text must not bounce back into view during reconnect.
- After tapping Resume, VPN returns to connected/running state.
- After resume completes, controls switch back to Pause and Stop connection (same ids; pause-button label changes back).

## Validation Note
- On Android TV, pausing and reconnect transitions can be short enough that broad polling misses one of the transient frames.
- If full-suite evidence shows the flow succeeded but `Pausing` or reconnect frames were not captured, rerun focused sampling before filing a product defect.
- Reference: `tests/manual-e2e/reference/archived/TV-PAUSE-RESUME-SMOOTHER-STATES-2026-04-25.md`

## Evidence Required
- Screenshot or log sample proving UI leaves `Connected` immediately after Pause and enters `Pausing` before `Paused`.
- Screenshot of paused state showing Resume and Stop controls.
- Screenshot during reconnecting after Resume where `pause_connection_button` is hidden and `start_connection_button` shows connecting/progress text.
- Transition log or UI dump proving Resume view does not reappear after reconnect has started.
- Screenshot after resume showing Pause and Stop controls.
- Logcat/app logs for paused and resumed state transitions.
- Optional UI dumps for paused/reconnecting/resumed snapshots with button ids and labels.
- When transient state is hard to capture, retain a focused transition flags snapshot with pausing/progress/no-bounce fields.

## Cleanup
- Tap Stop connection.
- Verify VPN disconnected before leaving the case.
