# SUB-06: Favorites section visual framing

## Scope boundary
On both the countries screen (`ServerListActivity`) and the servers-in-country screen (`CountryServersActivity`), add a border/frame around the pinned "Favorites" section so it is visually separated from the regular list below it. No change to which items are shown or to interaction behavior.

## Acceptance criteria
1. The pinned "Favorites" section (header + favorite rows) on the countries screen is visually enclosed by a border/frame that distinguishes it from the regular country list below.
2. The pinned "Favorites" section on the servers-in-country screen has the same visual framing treatment, consistent with the countries screen.
3. The framing renders correctly in both light and dark theme (`values` / `values-night`), using existing app color/style attributes rather than hardcoded colors.
4. The framing does not appear when the Favorites section is hidden (no available favorites), matching existing show/hide behavior from SUB-02/SUB-03.
5. No regression to existing row content, tap navigation, or long-press favorite actions on either screen.
6. Framing renders acceptably on TV (Leanback) layouts as well as mobile, since both launchers reuse the same core layouts/adapters.

## Out of scope
- Any change to the long-press action dialog styling (SUB-08).
- Any change to which countries/servers are favorited or how availability filtering works (SUB-01).
- New localized strings (SUB-07).

## dependsOn
None (independent of SUB-07 and SUB-08; can run in parallel with them).
