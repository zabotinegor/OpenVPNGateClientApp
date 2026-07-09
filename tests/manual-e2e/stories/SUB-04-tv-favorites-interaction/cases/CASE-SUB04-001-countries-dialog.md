---
id: CASE-SUB04-001
title: Countries screen — D-pad long-press opens favorite dialog, toggle works, BACK dismisses
surface: android-tv
suite: SUITE-SUB-04-tv-favorites-interaction
acceptance: AC1, AC4 (countries half of AC5)
---

## Preconditions

- Fresh debug build of the commit under test installed on the TV (`adb install -r tv-debug.apk`).
- App launched to MainActivity via Leanback launcher; server list loaded.
- Favorites state known (inspect `run-as com.yahorzabotsin.openvpnclientgate cat shared_prefs/favorites_prefs.xml`).

## Steps

1. Open the countries screen (ServerListActivity) — D-pad to the country selector row and press OK.
2. D-pad DOWN/UP to focus a non-favorite country row; note its displayed name.
3. Long-press OK by injecting a genuinely held key via `sendevent` on the remote's input device
   (validated on MIBOX4 — "Xiaomi RC" = `/dev/input/event2`, KEY_SELECT scancode 353):

   ```
   adb shell "sendevent /dev/input/event2 1 353 1 && sendevent /dev/input/event2 0 0 0 && sleep 1.2 && sendevent /dev/input/event2 1 353 0 && sendevent /dev/input/event2 0 0 0"
   ```

   Do NOT use `input keyevent --longpress KEYCODE_DPAD_CENTER` / `--longpress 23` — on MIBOX4
   (Android 9) it is delivered as a SHORT press (the row click fires instead of the dialog). On
   other TV hardware re-run `getevent -pl` to find the equivalent device/scancode. See
   [android-tv-dpad-qa-runbook.md](../../../environment/android-tv-dpad-qa-runbook.md).
4. Dump UI (`uiautomator dump`) and assert:
   - a dialog is present with title = the focused row's country name;
   - exactly one action item "Add to favorites";
   - a "Cancel" button;
   - no PopupMenu (`android.widget.PopupWindow$PopupViewContainer`/ListPopupWindow) in hierarchy.
5. D-pad to the action item and press OK (short press).
6. Assert dialog closed and country now appears in the pinned Favorites section; favorites_prefs.xml
   contains the country code.
7. Long-press OK on the same country (now favorite, in either section) and assert the action item
   reads "Remove from favorites".
8. Press BACK; assert dialog dismissed with no toggle (favorites unchanged).
9. Long-press again, select "Remove from favorites", assert Favorites section row removed and
   prefs entry cleared.

## Expected

Dialog per AC1 on every long-press; add/remove label reflects state; BACK dismisses without action;
no PopupMenu ever appears; no crash/WindowLeaked/BadTokenException in logcat.

## Cleanup

Restore favorites_prefs.xml to pre-test state (remove any codes added by the test).
