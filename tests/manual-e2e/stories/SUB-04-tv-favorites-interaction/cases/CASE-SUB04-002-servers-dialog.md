---
id: CASE-SUB04-002
title: Servers-in-country screen — D-pad long-press opens favorite dialog, toggle works
surface: android-tv
suite: SUITE-SUB-04-tv-favorites-interaction
acceptance: AC1, AC4 (servers half of AC5)
---

## Preconditions

- CASE-SUB04-001 environment (fresh build installed, app running).
- A country with multiple servers and non-zero server ids (V2 source) identified.

## Steps

1. From the countries screen, focus a multi-server country and press OK to open
   CountryServersActivity.
2. D-pad to focus a server row; note its displayed name (city, or IP when city blank).
3. Long-press OK using the held-key `sendevent` sequence from CASE-SUB04-001 step 3 (do NOT use
   `input keyevent --longpress` — it delivers a short press; see
   [android-tv-dpad-qa-runbook.md](../../../environment/android-tv-dpad-qa-runbook.md)).
4. Dump UI and assert: dialog title = row name (city or IP fallback), one action item
   ("Add to favorites" for non-favorite), Cancel button, no PopupMenu.
5. Select the action item; assert pinned Favorites section appears on the servers screen with the
   row, and favorites_prefs.xml gains the server id.
6. Long-press the same server again; assert label = "Remove from favorites"; press BACK to dismiss
   without action.
7. Long-press and select "Remove from favorites"; assert section disappears and prefs entry cleared.

## Expected

Same dialog contract as countries screen; favorite id persisted/removed correctly; no PopupMenu;
no crash/WindowLeaked/BadTokenException.

## Notes

Residual risk watch: for blank-city non-V2 servers the title may show the IP — cosmetic, record
but do not fail. Servers with id == 0 must show NO dialog at all (Presentation.NONE guard).

## Cleanup

Restore favorites_prefs.xml to pre-test state.
