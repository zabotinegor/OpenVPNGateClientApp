---
id: CASE-favorites-section-tap-navigation
title: Tapping a country row in the favorites section navigates to that country's servers
area: Android
surface: android
---

## Preconditions
- Countries screen open with a favorited country visible in the pinned Favorites section.

## Steps
1. Short-tap the country row inside the pinned Favorites section.

## Assertions
- The app navigates to `CountryServersActivity` for that country (same target as tapping the row in the
  regular list).
- The destination screen title/header matches the tapped country name.

## Evidence Required
- `dumpsys window` focus showing `CountryServersActivity` after the tap.
- UI hierarchy dump of the destination screen showing the country name.

## Cleanup
- Navigate back to the countries screen.
