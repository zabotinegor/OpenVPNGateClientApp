# SUB-09: Favorites section visual redesign

## Scope boundary
Redesign the pinned "Favorites" section on the countries screen (`ServerListActivity`) and the servers-in-country screen (`CountryServersActivity`) for better visual hierarchy and spacing, replacing the thin stroke-border framing from SUB-06 with a filled card treatment, adding a star icon to the section header, and adding a second header above the full list below it. Runs alongside SUB-08 (dialog/menu styling) to keep the whole favorites feature visually consistent.

## Acceptance criteria
1. The pinned Favorites block (header + favorite rows) is enclosed in a filled card background (colorSurface-variant tone, ~12dp corner radius, no stroke) instead of the SUB-06 stroke-border `FavoritesSectionFrameDecoration`, with internal padding so rows don't touch the card edges. Renders correctly in light and dark theme using theme attributes, not hardcoded colors.
2. The "Favorites" section header shows a small star icon next to the "Favorites" text, with spacing/typography consistent with the app's existing text styles.
3. A second header appears above the full list below the Favorites block, labeled to indicate it shows the complete list (e.g. "All countries" / "All servers" — reusing the existing full-list semantics, not "Other", since favorited items still also appear in this list). New localized string keys added for this header, translated into every locale SUB-07 already covers.
4. The second header only appears when the Favorites section is visible (i.e. there is at least one favorite); when there are no favorites, the list looks as it did before this story (no header, no card).
5. Existing row content, tap navigation, and long-press favorite actions (PopupMenu / TV `FavoriteActionDialog`) are unchanged by this story.
6. Consistent treatment applied to both the countries screen and the servers-in-country screen, and acceptable on TV (Leanback) layouts as well as mobile, since both launchers reuse the same core layouts/adapters.
7. Verified visually on a mobile device/emulator and a TV device/emulator, in both light and dark theme.
8. Each individual country/server row in the full list below (the "All countries"/"All servers" section) shows a small star indicator when that specific item is currently favorited, and shows no star when it is not. The star appears immediately when an item is added to favorites and disappears immediately when removed, on both the countries screen and the servers-in-country screen, mobile and TV. (Added after user device testing of the initial SUB-09 delivery — the section-header star alone was not sufficient; users need to see favorite status on a row-by-row basis in the full list too.)

## Out of scope
- Any change to favorites data/persistence or availability filtering (SUB-01).
- Any change to the long-press action dialog/menu styling (SUB-08 — implemented alongside this story but tracked separately).
- Any change to which countries/servers are favorited or how availability filtering works.

## dependsOn
None (runs alongside SUB-08; supersedes the `FavoritesSectionFrameDecoration` framing delivered by SUB-06).
