---
id: CASE-favorites-persist-across-restart
storyId: SUB-03
surface: android
ac: []
---

# Favorites persist across app restart; header focus is skipped

## Preconditions
- At least one favorited server in a known country.

## Steps
1. `adb shell am force-stop com.yahorzabotsin.openvpnclientgate`.
2. Relaunch via launcher (`adb shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LAUNCHER 1`).
3. Navigate Main -> country selector -> the same country.
4. Assert the pinned Favorites section is present with the favorited server, and the server also remains
   in the regular list.
5. Assert the list opens with the header visible at the top and no mis-scroll or crash
   (touch-mode observable part of the header focus-skip; position math covered by unit test
   `fix3_focus_position_is_1_when_favorites_section_present_skips_header`).

## Expected
- Favorites survive process restart; screen opens normally with header at top.
