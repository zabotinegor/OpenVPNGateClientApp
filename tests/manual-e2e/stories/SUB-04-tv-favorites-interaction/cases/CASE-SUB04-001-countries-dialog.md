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
3. Long-press OK: `adb shell input keyevent --longpress KEYCODE_DPAD_CENTER` (falls back to
   `adb shell input keyevent --longpress 23`).
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
