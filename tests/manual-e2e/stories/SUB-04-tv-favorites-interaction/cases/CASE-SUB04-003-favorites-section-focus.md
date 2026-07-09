---
id: CASE-SUB04-003
title: Pinned Favorites section rows are D-pad focusable; headers are skipped
surface: android-tv
suite: SUITE-SUB-04-tv-favorites-interaction
acceptance: AC2
---

## Preconditions

- At least one favorite country and one favorite server exist (created by CASE-SUB04-001/002 or
  seeded via prior steps).

## Steps

1. On the countries screen with a pinned Favorites section visible, D-pad from the top of the list
   downward through the section boundary.
2. Dump UI after each focus move; assert focus lands on favorite rows and regular rows only —
   never on the "Favorites"/"All countries" section header items.
3. Assert a favorite row inside the pinned section can receive focus and open the dialog via
   long-press (spot check).
4. Repeat steps 1-3 on the servers-in-country screen with a favorite server pinned.

## Expected

All favorite rows reachable via D-pad; headers never focused; focus order is stable (no traps,
no skipped data rows).
