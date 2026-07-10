---
id: CASE-SUB05-004
title: TV servers screen — D-pad long-press adds and removes a favorite server
surface: android-tv
suite: SUITE-SUB-05-favorites-manual-e2e
acceptance: AC2 (server half)
---

## Preconditions

- Same build/device/launch/long-press-injection preconditions as CASE-SUB05-003 (including the
  mandatory `sendevent` held-key workaround from
  [android-tv-dpad-qa-runbook.md](../../../environment/android-tv-dpad-qa-runbook.md)).
- Server source is DEFAULT_V2 (server favoriting requires positive integer V2 server ids).
- Favorites state known and recorded from `favorites_prefs.xml`.
- A multi-server country chosen as target (e.g. Belarus or Vietnam).

## Steps

1. From the countries screen, focus the target country row and short-press OK to open its servers
   screen (CountryServersActivity).
2. D-pad DOWN/UP to focus a non-favorite server row; note its displayed title (city, or IP when
   city is blank — the dialog title uses the same fallback).
3. Inject the held-key long-press. Dump UI and assert a FavoriteActionDialog is present:
   `id/alertTitle` = the row title, action item `android:id/text1` = "Add to favorites", Cancel
   button present; no PopupWindow container in the hierarchy.
4. Press OK directly (dialog list already focused). Assert:
   - a pinned "Favorites" section header + the server row appear at the top of the servers list;
   - `favorites_prefs.xml` `favorite_server_ids` contains the server's positive integer id;
   - focus recovery: focus jumps to the toolbar back button after the toggle — DPAD_DOWN
     re-enters the list.
5. D-pad across the section boundary and assert the section header is skipped by focus (exactly
   one data row per DPAD press).
6. Inject the long-press on the favorited row (pinned or regular position) and assert the action
   item reads "Remove from favorites".
7. Press BACK; assert the dialog is dismissed with no toggle (prefs unchanged, section present).
8. Inject the long-press again and press OK to activate "Remove from favorites". Assert:
   - the pinned Favorites section disappears (if this was the only favorite server of this
     country — the section is scoped to the current country);
   - `favorite_server_ids` no longer contains the id.
9. Regression spot-check: short-press OK on a regular server row and assert normal selection
   behavior (server selected, no dialog); BACK returns to the countries screen.

## Expected

Server-row D-pad long-press mirrors the country flow: state-reflecting FavoriteActionDialog
(title = city or IP fallback), OK toggles pinned section + `favorite_server_ids` immediately,
BACK dismisses without action, no PopupMenu on TV, short-press selection unchanged, no
crash/WindowLeaked/BadTokenException in logcat.

## Cleanup

Restore `favorites_prefs.xml` to the recorded pre-test state on the TV via UI toggles (or
`pm clear` for a full reset, then restore any pre-existing favorites/selection). Run the
end-of-session logcat scan required by the suite.
