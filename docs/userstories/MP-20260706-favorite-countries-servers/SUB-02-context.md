# Orchestration Context: SUB-02

## Discovered during master-plan BA (do not re-discover)
- Affected directories: `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/` (ServerListActivity.kt, CountryListAdapter.kt, ServerListViewModel.kt, ServerListContract.kt), `src/core/src/main/res/layout/content_server_list.xml`, `item_country_row.xml`.
- Stack markers found: ViewBinding, MVI-style contract (`ServerListContract.kt` with Action/State/Effect), `CountryWithServers` model (`country: Country`, `serverCount: Int`).
- Integration points: `CountryListAdapter` currently exposes `onClick: (Country) -> Unit` only; no long-click support exists anywhere in the codebase yet — this sub-plan introduces the first long-press pattern (mobile only).

## Key decisions made
- Favorites layout: pinned section at top of the same list (not a separate Activity/page). This IS the "dedicated favorites surface" for this screen per user decision.
- Long-press → dialog/PopupMenu shows add/remove based on current state (single toggle-style entry point, not two separate menu items necessarily — implementer's choice during its own BA).

## Dependencies from prior sub-plans
- SUB-01 output: favorites store (add/remove/query favorite country codes) and a filtering function that returns only currently-available favorite countries, already implemented and unit-tested in `src/core`. This sub-plan wires UI to that API; it does not re-implement persistence or filtering.

## Skip in BA step
- Full repo scan (already done).
- Stack detection (already done).
- Countries screen file discovery (already done — see paths above).
