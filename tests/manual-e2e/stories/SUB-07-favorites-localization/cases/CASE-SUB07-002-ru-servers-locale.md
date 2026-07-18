---
id: CASE-SUB07-002
title: Russian locale - favorites section title and server long-press actions/toast translated
area: Locale
surface: android
---

## Preconditions
- Same build/install/locale state as CASE-SUB07-001 (Russian system language).
- Navigate into a country's server list screen (e.g. Belarus, has multiple servers).

## Steps
1. Open the per-country server list screen.
2. Long-press a non-favorited server row to open the favorite action menu/dialog.
3. Assert the action label text.
4. Tap "Добавить в избранное" to favorite the server.
5. Assert the toast text shown after adding.
6. Assert the pinned Favorites section header text now visible at the top of the server list.
7. Long-press the now-favorited server row again.
8. Assert the action label text has flipped to the remove variant.
9. Tap "Удалить из избранного" to remove the favorite.
10. Assert the toast text shown after removing.
11. Assert the pinned Favorites section disappears again once empty.

## Assertions
- Section title reads "Избранное".
- Add action label reads "Добавить в избранное".
- Added toast reads "Добавлено в избранное".
- Remove action label reads "Удалить из избранного".
- Removed toast reads "Удалено из избранного".
- No English fallback text and no truncation/overflow on server list rows/dialog/toast.

## Evidence Required
- uiautomator dump or screenshot of the section header, long-press menu, and toast for both
  add and remove actions on the server list screen.

## Cleanup
- Ensure the test server is not left favorited (verified by step 11 removal).
