---
id: SUITE-SUB-04-tv-favorites-interaction
title: SUB-04 TV D-pad favorites interaction suite
surface: android-tv
spec: SPEC-SUB-04-tv-favorites-interaction
---

## Scope

TV-only validation of the D-pad long-press FavoriteActionDialog on the countries screen
(ServerListActivity) and servers-in-country screen (CountryServersActivity). Mobile PopupMenu
path is out of scope (unchanged; broader phone+TV E2E is SUB-05).

## Execution order

| # | Case | Title | AC |
|---|---|---|---|
| 1 | CASE-SUB04-001 | Countries screen dialog open/toggle/BACK | AC1, AC4, AC5 |
| 2 | CASE-SUB04-002 | Servers screen dialog open/toggle | AC1, AC4, AC5 |
| 3 | CASE-SUB04-004 | Pinned-section row long-press shows Remove dialog | AC1, AC4 |
| 4 | CASE-SUB04-003 | Favorites section focusable, headers skipped | AC2 |
| 5 | CASE-SUB04-005 | Regression: short-press, D-pad nav, drawer unchanged | AC3 |

## Session-wide assertions

- Full logcat scan at end of session: no FATAL EXCEPTION, ANR, WindowLeaked, BadTokenException.
- favorites_prefs.xml restored to pre-test state at cleanup.
