---
id: CASE-SUB05-001
title: Phone countries screen — touch long-press adds and removes a favorite country
surface: android-mobile
suite: SUITE-SUB-05-favorites-manual-e2e
acceptance: AC1 (country half)
---

## Preconditions

- Fresh debug build of the commit under test installed on the phone
  (`adb -s <phone> install -r mobile-debug.apk`); fingerprint recorded via
  `adb -s <phone> shell dumpsys package com.yahorzabotsin.openvpnclientgate | grep -E "versionName|versionCode|lastUpdateTime"`.
- Screen unlocked (`adb -s <phone> shell input keyevent 224` if needed); app launched via
  `adb -s <phone> shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LAUNCHER 1`
  and MainActivity reached (confirm with `dumpsys window | grep mCurrentFocus`).
- Server source is DEFAULT_V2 (default); country list loads on the countries screen.
- Favorites state known and recorded:
  `adb -s <phone> shell "run-as com.yahorzabotsin.openvpnclientgate cat shared_prefs/favorites_prefs.xml"`.

## Steps

1. From the main screen, tap the country selector row to open the countries screen
   (ServerListActivity).
2. Dump UI (`uiautomator dump /sdcard/ui.xml`) and confirm the target country is visible; pick a
   non-favorite country and note its displayed name and row bounds. Prefer a country that is
   stable in the backend list (multiple servers).
3. Long-press the row. Manually: press and hold ~1 s. Via adb: inject a same-coordinate swipe
   with a long duration at the row's center, e.g.
   `adb -s <phone> shell input swipe <x> <y> <x> <y> 1000`.
4. Assert an anchored PopupMenu appears near the long-pressed row (not screen-centered) with a
   single item "Add to favorites" (uiautomator dump shows a PopupWindow container with the item
   text).
5. Tap "Add to favorites". Assert:
   - a toast "Added to favorites" appears (visual; optionally
     `logcat -d | grep "toggled_favorite action=add"`);
   - a pinned "Favorites" section header (`section_header_title` text "Favorites") appears at the
     top with the country row beneath it, with NO manual refresh;
   - the same country still appears at its normal alphabetical position in the regular list below
     (intentional duplication);
   - `favorites_prefs.xml` now contains the country's uppercase ISO code in
     `favorite_country_codes`.
6. Long-press the favorited country row (either its pinned-section row or its regular-list row)
   and assert the PopupMenu item now reads "Remove from favorites".
7. Dismiss the menu without selecting (tap outside / BACK). Assert no toggle happened (prefs
   unchanged, pinned section still present).
8. Long-press again and tap "Remove from favorites". Assert:
   - toast "Removed from favorites";
   - the pinned Favorites section (header + row) disappears immediately (this was the only
     favorite);
   - the country remains in the regular list;
   - `favorite_country_codes` in `favorites_prefs.xml` no longer contains the code.
9. Regression spot-check: tap a regular (non-favorite) country row and assert it navigates to
   CountryServersActivity normally; go BACK.

## Expected

Anchored PopupMenu reflects current state ("Add to favorites" vs "Remove from favorites");
add pins the country immediately and persists the uppercase code; remove unpins immediately and
clears the code; dismissal without selection changes nothing; short-tap navigation unchanged;
no crash/ANR/WindowLeaked in logcat.

## Cleanup

Restore `favorites_prefs.xml` to its recorded pre-test state (toggle back via UI). The favorite
added here may be intentionally left in place ONLY if proceeding directly to CASE-SUB05-002/005
per suite order — record the carried state explicitly.
