---
id: CASE-SUB05-006
title: Pinned Favorites section is absent when there are no currently-available favorites
surface: android-mobile
suite: SUITE-SUB-05-favorites-manual-e2e
acceptance: AC4
---

## Preconditions

- Fresh debug build of the commit under test installed on the phone; app launched to
  MainActivity (same preconditions as CASE-SUB05-001).
- `favorites_prefs.xml` empty or absent. On a fresh install it does not exist yet
  (`run-as ... cat shared_prefs/favorites_prefs.xml` returns "No such file or directory" — that
  is a valid empty state); after prior favorites use, empty `<set>` elements are the empty state.
  If needed, reach the empty state via UI removal of all favorites (preferred) or
  `pm clear` + relaunch.

## Steps

1. Open the countries screen (ServerListActivity). Dump UI and assert NO "Favorites" section
   header is present (`section_header_title` with text "Favorites" absent from the dump) and the
   list starts directly with country rows.
2. Open any country's servers screen (CountryServersActivity). Dump UI and assert the same: no
   "Favorites" header, list starts with server rows.
3. Transition check — empty -> non-empty -> empty again on one screen:
   - long-press a country and add it to favorites; assert the header + pinned row appear;
   - long-press and remove it; assert the header AND its row disappear entirely (no empty header
     left behind), and the regular list renders normally.
4. Stored-but-unavailable variant (complements step 3's nothing-stored variant): AC4 also holds
   when favorites exist in `favorites_prefs.xml` but none are present in the current synced list.
   This mid-state is asserted inside CASE-SUB05-005 step 3; if CASE-SUB05-005 is blocked, record
   this variant as covered-by-design only (FavoritesFilter unit tests) and not device-verified.
5. Restart check: force-stop and relaunch the app
   (`adb -s <phone> shell am force-stop com.yahorzabotsin.openvpnclientgate`, relaunch via
   monkey). Re-assert step 1 on the countries screen with empty favorites.

## Expected

With no currently-available favorites there is no "Favorites" header and no pinned rows on either
screen — both when storage is empty and (via CASE-SUB05-005) when stored favorites are all
unavailable; removing the last favorite collapses the section immediately; state survives an app
restart; no crash in logcat.

## Cleanup

Ensure `favorites_prefs.xml` is back to its recorded pre-suite state (empty for a fresh-install
session).
