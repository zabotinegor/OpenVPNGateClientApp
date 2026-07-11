---
id: CASE-SUB06-002
title: Servers-in-country screen — pinned Favorites section is visually framed
surface: android-mobile
suite: SUITE-SUB-06-favorites-section-framing
acceptance: AC2
---

## Preconditions

- Debug build of commit `2ad24c9` installed on the phone.
- At least one favorite server present in the target country (`favorites_prefs.xml`
  `favorite_server_ids` non-empty for a server in that country).

## Steps

1. Navigate into a country's servers screen (CountryServersActivity) that has a favorited server.
2. Observe the top of the list.

## Expected

Same framing treatment as the countries screen: border encloses exactly the "Favorites" header
plus the pinned favorite server row(s), consistent visual style (stroke width, corner radius,
color) with the countries screen frame.

## Actual (2026-07-12, phone R58N849XQEY, light + dark theme, RU locale)

PASS. After favoriting the Sydney server in Australia, the frame enclosed the "Favorites" header
plus the single pinned "Сидней" row; the regular-list duplicate "Сидней" row below rendered
outside the frame. Confirmed in both light and dark theme (see CASE-SUB06-003).
