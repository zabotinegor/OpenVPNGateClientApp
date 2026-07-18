---
id: CASE-SUB05-003
title: TV countries screen — D-pad long-press adds and removes a favorite country
surface: android-tv
suite: SUITE-SUB-05-favorites-manual-e2e
acceptance: AC2 (country half)
---

## Preconditions

- Fresh debug build of the commit under test installed on the TV
  (`adb -s <tv> install -r tv-debug.apk`); fingerprint recorded via `dumpsys package`.
- App launched via the Leanback category (LAUNCHER does not resolve on TV builds):
  `adb -s <tv> shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LEANBACK_LAUNCHER 1`;
  MainActivity reached (~8 s after splash; confirm with `dumpsys window | grep mCurrentFocus`).
- Favorites state known and recorded:
  `adb -s <tv> shell "run-as com.yahorzabotsin.openvpnclientgate cat shared_prefs/favorites_prefs.xml"`.
- Long-press injection method validated per
  [android-tv-dpad-qa-runbook.md](../../../environment/android-tv-dpad-qa-runbook.md):
  `input keyevent --longpress` delivers a SHORT press on MIBOX4 — use the `sendevent` held-key
  workaround (on MIBOX4: "Xiaomi RC" = `/dev/input/event2`, KEY_SELECT scancode 353):

  ```
  adb -s <tv> shell "sendevent /dev/input/event2 1 353 1 && sendevent /dev/input/event2 0 0 0 && sleep 1.2 && sendevent /dev/input/event2 1 353 0 && sendevent /dev/input/event2 0 0 0"
  ```

  On other TV hardware re-run `getevent -pl` to find the equivalent device/scancode.
- RU-locale note: favorites strings are localized ("Избранное", "Добавить в избранное",
  "Удалить из избранного"); the dialog Cancel button is framework-localized ("ОТМЕНА").

## Steps

1. Open the countries screen (ServerListActivity): D-pad to the country selector row on the main
   screen and press OK (`input keyevent 23`).
2. D-pad DOWN/UP to focus a non-favorite country row (focused row = `CardView` with
   `focused="true"` in the uiautomator dump); note its displayed name.
3. Inject the held-key long-press (command above). Dump UI and assert a FavoriteActionDialog is
   present: `id/alertTitle` = the focused row's country name, single action item
   `android:id/text1` = "Add to favorites", Cancel button `android:id/button2`; no
   PopupWindow/ListPopupWindow container anywhere in the hierarchy.
4. Press OK directly (`input keyevent 23`) — the dialog's list already has focus (do NOT press
   DPAD_DOWN first: that moves focus to Cancel). Assert:
   - the dialog closes and the country now appears in a pinned "Favorites" section at the top
     (`section_header_title` text "Favorites", `focusable=false`);
   - `favorites_prefs.xml` `favorite_country_codes` contains the uppercase ISO code;
   - after the toggle, focus jumps to the toolbar back button (known behavior) — press DPAD_DOWN
     to re-enter the list.
5. D-pad to the pinned favorite row (assert the section header is skipped by focus — exactly one
   data row per DPAD press across the boundary) and inject the long-press again. Assert the
   action item now reads "Remove from favorites".
6. Press BACK (`input keyevent 4`). Assert the dialog is dismissed with no toggle: pinned section
   still present, prefs unchanged.
7. Inject the long-press once more and press OK to activate "Remove from favorites". Assert:
   - the pinned Favorites section (header + row) disappears (if this was the only favorite);
   - `favorite_country_codes` no longer contains the code.
8. Regression spot-check: short-press OK on a country row and assert normal navigation into the
   servers screen with no dialog; go BACK.

## Expected

D-pad long-press opens a remote-navigable FavoriteActionDialog whose action label reflects the
current state; OK toggles and updates the pinned section + prefs immediately; BACK dismisses
without action; no PopupMenu appears on TV; short-press navigation unchanged; no
crash/WindowLeaked/BadTokenException in logcat.

## Cleanup

Restore `favorites_prefs.xml` to its recorded pre-test state via UI toggles, unless carrying a
favorite forward into CASE-SUB05-004 per suite order — record the carried state explicitly.
