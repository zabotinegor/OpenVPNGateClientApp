---
id: CASE-regular-list-tap-navigation-unchanged
title: Existing single-tap navigation for non-favorite countries is unchanged
area: Android
surface: android
---

## Preconditions
- Countries screen open with a pinned Favorites section present (regression check while the feature is active).

## Steps
1. Short-tap a country row in the regular alphabetical list that is not in the favorites section.

## Assertions
- The app navigates to `CountryServersActivity` for that country, same as pre-feature behavior.
- No unintended favorite toggle occurs from a short tap.

## Evidence Required
- `dumpsys window` focus showing `CountryServersActivity` after the tap.
- UI hierarchy dump of the destination screen showing the tapped country name.

## Cleanup
- Navigate back to the countries screen.
