---
id: CASE-favorites-section-hidden-when-empty
title: Pinned Favorites section is hidden when no favorite country is available
area: Android
surface: android
---

## Preconditions
- Countries screen open with at least one favorite currently set and visible in the pinned section.

## Steps
1. Long-press the favorited country row inside the pinned "Favorites" section.
2. Confirm the PopupMenu shows "Remove from favorites" (currently-favorite state).
3. Tap "Remove from favorites".
4. Observe the list without any manual pull-to-refresh or navigation.

## Assertions
- The "Favorites" section header and its row disappear immediately.
- The regular alphabetical list renders unchanged (same countries, same order, same counts) with no
  pinned section artifacts left behind.
- No manual refresh was required to see the section disappear.

## Evidence Required
- UI hierarchy dump before (section present) and after (section absent) the unfavorite action.

## Cleanup
- None; favorites state is already empty after this case.
