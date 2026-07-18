---
id: CASE-SUB07-004
title: Polish locale - favorites section title and server long-press actions/toast translated
area: Locale
surface: android
---

## Preconditions
- Same build/install/locale state as CASE-SUB07-003 (Polish system language).
- Navigate into a country's server list screen with multiple servers.

## Steps
1. Open the per-country server list screen.
2. Long-press a non-favorited server row.
3. Assert the action label text.
4. Tap "Dodaj do ulubionych" to favorite the server.
5. Assert the toast text shown after adding.
6. Assert the pinned Favorites section header text now visible at the top of the server list.
7. Long-press the now-favorited server row again.
8. Assert the action label text has flipped to the remove variant.
9. Tap "Usuń z ulubionych" to remove the favorite.
10. Assert the toast text shown after removing.
11. Assert the pinned Favorites section disappears again once empty.

## Assertions
- Section title reads "Ulubione".
- Add action label reads "Dodaj do ulubionych".
- Added toast reads "Dodano do ulubionych".
- Remove action label reads "Usuń z ulubionych".
- Removed toast reads "Usunięto z ulubionych".
- No English fallback text; no truncation/overflow on server list rows/dialog/toast.

## Evidence Required
- uiautomator dump or screenshot of the section header, long-press menu, and toast for both
  add and remove actions on the server list screen.

## Cleanup
- Ensure the test server is not left favorited (verified by step 11 removal).
- Restore device system language to English (prior default) after all cases complete.
