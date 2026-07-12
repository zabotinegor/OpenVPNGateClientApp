---
id: CASE-SUB07-003
title: Polish locale - favorites section title and country long-press actions/toast translated
area: Locale
surface: android
---

## Preconditions
- Debug APK from branch `feature/sub-07-favorites-localization` HEAD `533e834` installed on
  ADB serial `R58N849XQEY`.
- Device system language set to Polish (Ustawienia > Języki i wpisywanie > Języki > Polski, moved
  to top).
- At least one country available and not yet favorited.

## Steps
1. Launch the app (countries list screen).
2. Long-press a non-favorited country row.
3. Assert the action label text.
4. Tap "Dodaj do ulubionych" to favorite the country.
5. Assert the toast text shown after adding.
6. Assert the pinned Favorites section header text now visible.
7. Long-press the now-favorited country row again.
8. Assert the action label text has flipped to the remove variant.
9. Tap "Usuń z ulubionych" to remove the favorite.
10. Assert the toast text shown after removing.
11. Assert the pinned Favorites section disappears again once empty.

## Assertions
- Section title reads "Ulubione" (not "Favorites").
- Add action label reads "Dodaj do ulubionych" (not "Add to favorites").
- Added toast reads "Dodano do ulubionych" (not "Added to favorites").
- Remove action label reads "Usuń z ulubionych" (not "Remove from favorites").
- Removed toast reads "Usunięto z ulubionych" (not "Removed from favorites").
- No English fallback text; no truncation/overflow of the Polish diacritics (ń, ś, ó, ż) in the
  header, menu, or toast.

## Evidence Required
- uiautomator dump or screenshot of the section header, long-press menu, and toast for both
  add and remove actions.

## Cleanup
- Ensure the test country is not left favorited (verified by step 11 removal).
