---
id: CASE-SUB04-004
title: Long-press on a row inside the pinned Favorites section shows the Remove dialog
surface: android-tv
suite: SUITE-SUB-04-tv-favorites-interaction
acceptance: AC1, AC4
---

## Preconditions

- At least one favorite country (countries screen) and one favorite server (servers screen) pinned.

## Steps

1. Countries screen: D-pad focus the favorite row inside the pinned Favorites section (not the
   duplicate row in the regular list).
2. Long-press OK; dump UI.
3. Assert dialog title = country name and the single action item reads "Remove from favorites".
4. Press BACK to dismiss without action; assert favorite unchanged.
5. Repeat 1-4 on the servers screen for the pinned favorite server row.

## Expected

Dialog opens from pinned-section rows exactly as from regular rows; label reflects favorite state;
BACK leaves state untouched.
