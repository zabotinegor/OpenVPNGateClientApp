---
id: CASE-long-press-toggle-menu
storyId: SUB-03
surface: android
ac: [AC-3]
---

# Long-press PopupMenu reflects favorite state

## Preconditions
- App on servers-in-country screen (Main -> country selector -> tap a country with >=2 servers).
- Target server is not a favorite.

## Steps
1. Long-press a non-favorite server row (`adb shell input swipe X Y X Y 800`).
2. Assert a PopupMenu appears anchored near the row with item "Add to favorites".
3. Tap "Add to favorites".
4. Long-press the same server's row in the pinned Favorites section.
5. Assert the PopupMenu now shows "Remove from favorites".
6. Dismiss with BACK.

## Expected
- Menu label matches current favorite state in both the regular list and favorites section.
- No crash, no WindowLeaked when dismissing.

## Notes
- `Server.id == 0` non-favoritability is not producible on-device with the v2 source; verified by unit tests
  (guards at ViewModel, Activity, and FavoritesServerStore layers).
