---
id: CASE-SUB06-004
title: Frame and section disappear at zero favorites, reappear when re-added
surface: android-mobile, android-tv
suite: SUITE-SUB-06-favorites-section-framing
acceptance: AC4
---

## Preconditions

- At least one favorite country and one favorite server present, both currently visible/available
  in the loaded list (not hidden by unrelated backend list churn — verify via long-press dialog
  state or `favorites_prefs.xml` dump beforehand).

## Steps

1. Long-press each pinned favorite country row and select "Remove from favorites" until
   `favorite_country_codes` is empty. Observe the countries screen after each removal.
2. Repeat for the pinned favorite server row(s) in a country's servers screen until
   `favorite_server_ids` is empty.
3. Re-add a favorite (country and/or server) via long-press "Add to favorites".

## Expected

- The pinned Favorites section (header + frame) shrinks as individual favorites are removed and
  disappears entirely (no header, no frame, no empty box) once zero favorites remain for that
  screen.
- Re-adding a favorite makes the section (with frame) reappear immediately, no manual refresh
  needed.

## Actual (2026-07-12, phone R58N849XQEY + TV MIBOX4 192.168.1.94:5555)

PASS on both devices.

Phone: removed AU then BR country favorites one at a time — frame shrank from 2 rows to 1 row to
fully gone; toast "Removed from favorites" shown on each. Same for the single favorite server
(Sydney) — frame disappeared entirely at 0 servers, toast "Removed from favorites" shown. Re-added
the Sydney server favorite — frame reappeared immediately with the correct row.

TV: removed the AU country favorite via D-pad long-press (`sendevent` held-key on
`/dev/input/event2`, scancode 353) + `FavoriteActionDialog` — Favorites section and frame
disappeared from the countries screen. Re-added AU as favorite — section and frame reappeared
immediately, "Added to favorites" toast shown. Also verified the reverse direction (add updates
were driven from the FavoriteActionDialog "Remove from favorites"/"Add to favorites" action label
reflecting current state correctly each time).

Note: during testing, a stored favorite country (Russia, `RU`) appeared to have no pinned section
even though `favorites_prefs.xml` and the long-press dialog both showed it as favorited — this was
traced to the backend server list temporarily not including Russia in the currently-loaded
`countries` list (SSE-driven content churn, documented pre-existing behavior — see spec's "Known
Behavior Constraints"), not a framing defect. Confirmed non-issue by favoriting a country
(Australia) that was stably present in the loaded list at the time — the frame appeared
immediately as expected.

## Merge-gate re-check (2026-07-12, HEAD `130d6f9`, TV MIBOX4 192.168.1.94:5555 only)

PASS. Re-verified after 5 rounds of PR review fixes landed since the pass above (at `2ad24c9`),
specifically the scroll/translation clipping and off-screen false-closing-edge fixes.

Countries screen: with Australia + Belarus both favorited (2-row pinned block), removed Australia
via D-pad long-press (`sendevent` held-key on `/dev/input/event3`, scancode 353) —
frame shrank from 2 rows to 1 row (Belarus only); removed Belarus — section and frame disappeared
entirely (no header, no frame, no empty box), toast "Removed from favorites" shown. Re-added
Belarus via long-press "Add to favorites" — section and frame reappeared immediately with correct
row, toast "Added to favorites" shown.

Servers screen (Australia): removed the favorited Sydney server via long-press — frame disappeared
entirely (0 favorites), toast "Removed from favorites" shown, plain unframed list remained.
Re-added Sydney — frame reappeared immediately, toast "Added to favorites" shown.

Full-session logcat scan: 0 hits for FATAL EXCEPTION, ANR, WindowLeaked, or BadTokenException;
crash log buffer empty. `favorites_prefs.xml` restored to pre-test baseline (`BY` country, server
`20838`) via UI toggles (add/remove pairs canceled out).
