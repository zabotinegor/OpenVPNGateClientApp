---
id: CASE-SUB06-001
title: Countries screen — pinned Favorites section is visually framed
surface: android-mobile
suite: SUITE-SUB-06-favorites-section-framing
acceptance: AC1
---

## Preconditions

- Debug build of commit `2ad24c9` installed on the phone (`adb -s R58N849XQEY install -r
  mobile-debug.apk`); fingerprint confirmed via `dumpsys package ... | grep -E
  "versionName|versionCode|lastUpdateTime"`.
- At least one favorite country present (`favorites_prefs.xml` `favorite_country_codes`
  non-empty).

## Steps

1. Launch the app, dismiss the update dialog if shown, navigate to the countries screen
   (ServerListActivity) via the country selector row.
2. Observe the top of the list.

## Expected

A visible rounded-rect border encloses exactly the "Favorites" header and the pinned favorite
country row(s) — not the "Список серверов" info card above it and not the regular alphabetical
list below it (including the same country's second, unframed appearance in the regular list).

## Actual (2026-07-12, phone R58N849XQEY, light theme, RU locale)

PASS. With favorites AU and BR, the frame enclosed the "Favorites" header plus both pinned rows
(Австралия, Бразилия) only; the regular list below (including the duplicate Австралия/Бразилия
entries) rendered outside the frame with no border.
