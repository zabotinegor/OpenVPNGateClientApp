---
id: CASE-SUB05-002
title: Phone servers screen — touch long-press adds and removes a favorite server
surface: android-mobile
suite: SUITE-SUB-05-favorites-manual-e2e
acceptance: AC1 (server half)
---

## Preconditions

- Same build/device/launch preconditions as CASE-SUB05-001.
- Server source is DEFAULT_V2 — server favoriting requires positive integer V2 server ids
  (legacy CSV servers have `id=0` and are intentionally non-favoritable; long-press on such rows
  must show no favorites action).
- Favorites state known and recorded from `favorites_prefs.xml`.
- A country with several servers chosen as target (stable across syncs; e.g. a multi-server
  country like Vietnam or Belarus).

## Steps

1. From the countries screen, tap the target country row to open its servers screen
   (CountryServersActivity). Assert the list opens scrolled to the top (regression guard for
   DEF-sub03-header-misscroll-on-open: if a pinned Favorites section exists, its header must be
   visible on open).
2. Dump UI and pick a non-favorite server row; note its displayed title (city, or IP when city is
   blank) and row bounds.
3. Long-press the row (manual hold ~1 s, or
   `adb -s <phone> shell input swipe <x> <y> <x> <y> 1000`).
4. Assert an anchored PopupMenu appears near the row with a single item "Add to favorites".
5. Tap "Add to favorites". Assert:
   - toast "Added to favorites";
   - a pinned "Favorites" section header appears at the top of the servers list with the server
     row beneath it, immediately, no manual refresh;
   - the same server also remains at its normal position in the regular list below;
   - `favorites_prefs.xml` `favorite_server_ids` now contains the server's positive integer id
     (correlate the id with the row via the V2 API/backend data if exactness is required;
     otherwise assert exactly one new id was added).
6. Long-press the favorited server row (pinned or regular position) and assert the menu item
   reads "Remove from favorites".
7. Dismiss without selecting; assert prefs unchanged and pinned section still present.
8. Long-press again and tap "Remove from favorites". Assert:
   - toast "Removed from favorites";
   - the pinned Favorites section disappears immediately (if this was the only favorite server of
     this country — the section is scoped to the current country);
   - `favorite_server_ids` no longer contains the id.
9. Regression spot-check: short-tap a regular server row and assert normal selection behavior
   (server selected, counter/details update) with no menu or dialog.

## Expected

Server favoriting mirrors the country flow: state-reflecting anchored PopupMenu, immediate pinned
section add/remove scoped to the current country, positive integer id persisted/removed in
`favorite_server_ids`, short-tap selection unchanged, no crash/ANR/WindowLeaked in logcat.

## Cleanup

Restore `favorites_prefs.xml` to its recorded pre-test state via UI toggles, unless carrying a
favorite server forward into CASE-SUB05-005 per suite order — record the carried state
explicitly.
