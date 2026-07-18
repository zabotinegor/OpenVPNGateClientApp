---
id: CASE-favorites-section-appears
title: Pinned Favorites section appears immediately after favoriting a country
area: Android
surface: android
---

## Preconditions
- App installed (mobile debug variant), VPN not connected.
- Countries screen open (`ServerListActivity`) with no favorites currently set.

## Steps
1. Long-press a country row in the regular list (e.g. "Australia").
2. Confirm the PopupMenu shows "Add to favorites" (not-yet-favorite state).
3. Tap "Add to favorites".
4. Observe the list without any manual pull-to-refresh or navigation.

## Assertions
- A "Favorites" section header appears above the regular alphabetical list.
- The favorited country appears in the pinned section with its correct server count, using the same
  row rendering (flag, name, server count, chevron) as the regular list.
- The country still also appears in its normal alphabetical position in the regular list.
- The section appears immediately (same render pass), without requiring a manual refresh action.

## Evidence Required
- UI hierarchy dump (uiautomator) before and after favoriting showing "Favorites" header absent then present.

## Cleanup
- None required; the favorited state is removed by the follow-up unfavorite case.
