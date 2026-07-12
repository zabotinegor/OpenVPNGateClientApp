---
id: CASE-SUB07-001
title: Russian locale - favorites section title and country long-press actions/toast translated
area: Locale
surface: android
---

## Preconditions
- Debug APK built from branch `feature/sub-07-favorites-localization` HEAD `533e834`, installed on
  ADB serial `<your-device-serial>`.
- Device system language set to Russian (Настройки > Язык и ввод > Языки > Русский, moved to top).
- At least one country (e.g. Belarus) available and not yet favorited.

## Steps
1. Launch the app (countries list screen).
2. Observe the pinned favorites section state before favoriting anything (should be hidden/absent
   when empty).
3. Long-press a non-favorited country row to open the favorite action menu/dialog.
4. Assert the action label text.
5. Tap "Добавить в избранное" to favorite the country.
6. Assert the toast text shown after adding.
7. Assert the pinned Favorites section header text now visible at the top of the list.
8. Long-press the now-favorited country row (in the pinned section or regular list) again.
9. Assert the action label text has flipped to the remove variant.
10. Tap "Удалить из избранного" to remove the favorite.
11. Assert the toast text shown after removing.
12. Assert the pinned Favorites section disappears again once empty.

## Assertions
- Section title reads "Избранное" (not "Favorites").
- Add action label reads "Добавить в избранное" (not "Add to favorites").
- Added toast reads "Добавлено в избранное" (not "Added to favorites").
- Remove action label reads "Удалить из избранного" (not "Remove from favorites").
- Removed toast reads "Удалено из избранного" (not "Removed from favorites").
- No English fallback text appears anywhere in this flow.
- No visible text truncation/overflow/ellipsis on the section header, dialog/menu action rows, or
  toast for the longer Russian strings.

## Evidence Required
- uiautomator dump or screenshot of the section header, long-press menu, and toast for both
  add and remove actions.

## Cleanup
- Ensure the test country is not left favorited (verified by step 12 removal).
