# SUB-03: Servers-in-country screen favorites UI (mobile touch)

## Scope boundary
On the per-country servers screen (`CountryServersActivity` / `ServerPickerAdapter`), add a pinned "Favorites" section at the top of the list showing only currently-available favorite servers, and support add/remove-favorite via long-press on a server row (mobile touch only).

## Acceptance criteria
1. When at least one favorite server (per `FavoritesStore.getFavoriteServerIds()`) is present among the servers of the currently viewed country, a pinned "Favorites" section appears above the regular server list, containing only that country's favorited servers.
2. When no favorite server from this country is currently available, the pinned section is not shown and the regular list renders unchanged.
3. Long-pressing a server row (favorites section or regular list), on mobile only, presents an add/remove-favorite action reflecting current favorite state. Servers with `id == 0` (legacy/un-synced, a known pre-existing limitation carried from SUB-01) are not favoritable; their long-press either omits the favorite action or shows it disabled.
4. Tapping (short tap) a server row in the favorites section selects/connects to that server exactly like tapping it in the regular list.
5. Favoriting/unfavoriting updates the pinned section immediately without requiring a manual refresh.
6. Existing single-tap selection and non-favorites behavior of the servers screen is unchanged.

## Design notes (resolved during BA)
- **Scope**: Favorites section is scoped to the currently viewed country only (this screen never shows other countries' servers), not a global favorites list.
- **id=0 servers**: Accepted as a known limitation for this sub-plan, consistent with the non-blocking risk already carried forward from SUB-01/SUB-02. No id-assignment fix included here.
- **Pattern to mirror**: `CountryListAdapter`/`ServerListActivity` pinned-section + long-press PopupMenu pattern from SUB-02, adapted to `ServerPickerAdapter`/`CountryServersActivity`/`CountryServersViewModel`/`CountryServersContract`. TV gating (`TvUtils.isTvDevice`) applies exactly as in SUB-02, since D-pad interaction is SUB-04's scope.

## Out of scope
- Countries screen changes (SUB-02).
- TV/D-pad interaction (SUB-04).
- Availability-filtering logic itself (implemented in SUB-01; this sub-plan only consumes it).
- Fixing `Server.id == 0` favoriting limitation (carried-forward known risk, not in scope here).

## dependsOn
SUB-01
