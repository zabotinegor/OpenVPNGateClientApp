---
id: TV-DRAWER-FALSE-CLICK-001
title: Drawer opening must block main-screen connection action
area: TV Main Screen Drawer Interaction
surface: android-tv
---

## Preconditions
- TV debug build with drawer guard fix is installed.
- Android TV device is connected via ADB TCP.
- User is on the main screen.
- Connection controls are visible.
- Connection can be in disconnected or connected state.
- **Run before each test**: `adb shell am force-stop com.android.vpndialogs` to clear any stale system VPN permission dialogs from prior runs. Failure to do this causes false failures because `com.android.vpndialogs` persists across force-stops of the main app.

## Steps
1. Focus the toolbar navigation (burger) control and press OK to open the drawer.
2. While the drawer is still opening, press OK repeatedly.
3. Wait until drawer is fully open.
4. Navigate in the drawer and verify focus is on drawer items.
5. Start closing the drawer and press OK repeatedly during the closing transition.
6. Wait until drawer is fully closed.
7. Verify primary focus returns to the main connection control.

## Assertions
- Main screen action controls (`start_connection_button`, `pause_connection_button`, server selector) do not trigger while drawer is opening/open/closing.
- No accidental connect/stop/pause/server-select action is fired while drawer animation is in progress.
- Drawer item focus is active after opening.
- After drawer closes, main connection control becomes interactive again.

## Evidence Required
- Screenshot with open drawer and no state change on connection control.
- Log snippet around drawer open/close transitions.
- Optional short recording showing repeated OK presses during drawer opening with no false action.

## Cleanup
- Leave app on main screen with drawer closed.
- Run `adb shell am force-stop com.android.vpndialogs` to dismiss any system VPN dialog opened during the test.

## Notes on ADB Key Injection
- ADB `input keyevent` injects keys one at a time with dispatch-wait (~380 ms per key on MIBOX4). This is far slower than a real user's rapid remote presses (~100 ms/key).
- The guard is designed for real-user interaction speeds and is verified by the Espresso instrumented suite (`MainActivityTvDrawerGuardTest`) which uses fast on-device injection.
- For ADB-based AC1-CLOSE validation, send all spam keys as a single on-device shell session: `adb shell "input keyevent KEYCODE_BACK; input keyevent 23; input keyevent 23; ..."` to reduce TCP round-trip overhead.
- If VPN is already authorized on the device, `ConfirmDialog` will not appear even for legitimate button presses. Use Espresso test results as authoritative evidence for AC1 and AC3 in this case.
