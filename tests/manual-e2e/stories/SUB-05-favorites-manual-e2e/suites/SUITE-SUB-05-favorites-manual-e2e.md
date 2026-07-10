---
id: SUITE-SUB-05-favorites-manual-e2e
title: SUB-05 favorites end-to-end suite (phone + TV)
surface: android-mobile, android-tv
spec: SPEC-SUB-05-favorites-manual-e2e
---

## Scope

Consolidated end-to-end validation of the favorites feature on a real phone (touch long-press
PopupMenu) and a real TV (D-pad long-press FavoriteActionDialog), on both the countries screen
(ServerListActivity) and the servers-in-country screen (CountryServersActivity), plus the
availability hide/restore behavior and the empty-favorites state. TV dialog internals (focus
order, BACK dismissal, no-PopupMenu assertion) were already covered in depth by SUITE-SUB-04;
here they are exercised as part of the add/remove flows, not re-verified exhaustively.

## Execution order

| # | Case | Surface | Title | AC |
|---|---|---|---|---|
| 1 | CASE-SUB05-006 | phone | Empty favorites: pinned section absent on both screens | AC4 |
| 2 | CASE-SUB05-001 | phone | Countries screen: add/remove favorite country via touch long-press | AC1 |
| 3 | CASE-SUB05-002 | phone | Servers screen: add/remove favorite server via touch long-press | AC1 |
| 4 | CASE-SUB05-005 | phone | Availability hide/restore of favorited country/server across syncs | AC3 (mid-state also evidences AC4) |
| 5 | CASE-SUB05-003 | tv | Countries screen: add/remove favorite country via D-pad long-press | AC2 |
| 6 | CASE-SUB05-004 | tv | Servers screen: add/remove favorite server via D-pad long-press | AC2 |

Rationale: CASE-SUB05-006 runs first on a fresh install while `favorites_prefs.xml` is guaranteed
empty; the phone add/remove cases then establish favorites that CASE-SUB05-005 reuses; TV cases
run last on their own device with their own state.

## Session-wide assertions

- Both devices run the same debug build of the commit under test; record
  versionName/versionCode/lastUpdateTime from `dumpsys package` on each device before starting.
- Full logcat scan at the end of each device session: no FATAL EXCEPTION, ANR, WindowLeaked,
  BadTokenException.
- `favorites_prefs.xml` restored to its pre-test state on both devices at cleanup (toggle via UI
  preferred; `pm clear` acceptable for a full reset).
- Per-case pass/fail recorded with evidence (prefs dumps, uiautomator extracts, logcat filters)
  in the Manual QA execution report (AC5).
