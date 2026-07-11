---
id: CASE-SUB06-005
title: Tap navigation and long-press favorite actions unchanged on pinned and regular rows
surface: android-mobile, android-tv
suite: SUITE-SUB-06-favorites-section-framing
acceptance: AC5
---

## Preconditions

- At least one favorite country and one favorite server present.

## Steps

1. Short-tap (mobile) / D-pad select (TV) a pinned favorite country row; confirm navigation to
   CountryServersActivity.
2. Short-tap / select a pinned favorite server row; confirm it is selected and the screen returns
   with that server chosen.
3. Short-tap / select a regular (non-pinned, non-favorite) row; confirm unchanged navigation
   behavior.
4. Long-press (mobile PopupMenu) / D-pad long-press OK/center (TV FavoriteActionDialog) a pinned
   row and a regular row; confirm the action label reflects current favorite state ("Add to
   favorites" vs "Remove from favorites").

## Expected

No regression versus pre-SUB-06 behavior: tap/select navigation and long-press favorite toggling
work identically on pinned rows, their regular-list duplicates, and non-favorite rows.

## Actual (2026-07-12, phone R58N849XQEY + TV MIBOX4 192.168.1.94:5555)

PASS on both devices.

Phone: tapping the country selector on the main screen correctly opened ServerListActivity
(`mCurrentFocus` verified via `dumpsys window`); short-tap on the pinned Australia country row
opened CountryServersActivity; short-tap on a server row (regular-list duplicate) selected the
server and returned to MainActivity showing "Австралия" / "Сидней" as selected. Long-press on a
non-favorite server showed "Add to favorites"; after adding, long-press on the same (now pinned)
row showed "Remove from favorites"; dismissing without selecting made no state change.

TV: D-pad select on the country selector opened ServerListActivity; D-pad select on the pinned
Australia row opened CountryServersActivity; D-pad select on the pinned Sydney server row selected
it and returned up the stack with no crash. D-pad long-press (`sendevent` held-key workaround) on
Россия opened `FavoriteActionDialog` showing "Remove from favorites" (matching its stored favorite
state); on a non-favorite server (Sydney) showed "Add to favorites". No PopupMenu class appeared in
any TV uiautomator dump (TV correctly uses the dialog presentation only).

Full-session logcat scan on both devices (filtered for the app package): no FATAL EXCEPTION, ANR,
or app-attributable exception across either testing session.
