---
id: CASE-SUB04-005
title: Regression — short-press select/connect, D-pad navigation, and drawer unchanged
surface: android-tv
suite: SUITE-SUB-04-tv-favorites-interaction
acceptance: AC3
---

## Preconditions

- App running on TV with the build under test.

## Steps

1. Countries screen: focus a country row and SHORT-press OK; assert normal selection behavior
   (navigates to CountryServersActivity or selects the country), and NO favorite dialog appears.
2. Servers screen: focus a server row and SHORT-press OK; assert normal select/connect behavior,
   no dialog.
3. Main screen: open the TV drawer (D-pad toward drawer per existing TV pattern), navigate drawer
   items with D-pad, close drawer with BACK; assert `TvDrawerInteractionGuard` behavior unchanged
   (no false clicks, drawer focus works).
4. General D-pad sweep across main screen controls (UP/DOWN/LEFT/RIGHT) — no focus traps, no crash.

## Expected

All pre-SUB-04 TV interactions behave exactly as before; long-press is the only new entry point.
