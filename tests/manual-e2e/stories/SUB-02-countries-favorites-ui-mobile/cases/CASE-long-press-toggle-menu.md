---
id: CASE-long-press-toggle-menu
title: Long-press on a country row presents an add/remove-favorite PopupMenu reflecting current state
area: Android
surface: android
---

## Preconditions
- Countries screen open.

## Steps
1. Long-press a non-favorited country row in the regular list. Read the PopupMenu action text.
2. Tap the action to favorite the country.
3. Long-press the same country row now shown in the pinned Favorites section. Read the PopupMenu action text.

## Assertions
- Step 1 PopupMenu shows "Add to favorites" for a non-favorited country.
- Step 3 PopupMenu (same country, now favorited) shows "Remove from favorites".
- The PopupMenu appears for rows in both the favorites section and the regular list.

## Evidence Required
- UI hierarchy dumps capturing the PopupMenu text for both states.

## Cleanup
- Remove the favorite added in step 2 if not already covered by another case in the same run.
