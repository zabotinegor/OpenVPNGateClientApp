# Orchestration Context: SUB-06

## Discovered during master-plan BA (do not re-discover)
- Affected files: `src/core/src/main/res/layout/item_country_section_header.xml` (plain `TextView`, no container/border today), the layouts that host the flattened favorites+regular list in `ServerListActivity`/`CountryServersActivity`, and `item_country_row.xml`/`item_server_row.xml` (each row is already its own `MaterialCardView` with `app:cardBackgroundColor="?attr/colorSurface"` and `app:cardCornerRadius="@dimen/item_corner_radius"` — no shared enclosing frame around the section as a whole).
- Stack markers found: `values-night/` exists (dark theme via day/night qualifiers) alongside default `values/` — theme-aware styling should use `?attr/...` theme attributes, not hardcoded colors, consistent with the row cards.
- Integration points: per `src/docs/favorites-ui-patterns.md` "Pinned Favorites Section", the section is a flattened header + rows, not a separate container view today — framing needs to wrap that header+rows region (e.g. an outer container with a stroke/background) without breaking the existing single-RecyclerView flattened-list approach.

## Key decisions made
- Framing must work on both the countries screen and the servers screen (same visual treatment) and must respect light/dark theme via existing theme attributes.
- Must not affect show/hide logic (already correct in SUB-02/SUB-03/SUB-01) — this is styling only.

## Dependencies from prior sub-plans
- SUB-02/SUB-03 output: pinned Favorites section (header + rows) already implemented and merged on both screens; this sub-plan only adds visual framing around the existing structure.
- Independent of SUB-07 (localization) and SUB-08 (dialog theming) — different files, can run in parallel.

## Skip in BA step
- Full repo scan (already done).
- Row/section layout discovery (already done — see affected files above).
