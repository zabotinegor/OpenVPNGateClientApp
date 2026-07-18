# SUB-02: Countries screen favorites UI (mobile touch)

## Scope boundary
On the countries list screen (`ServerListActivity` / `CountryListAdapter`), add a pinned "Favorites" section at the top of the list showing only currently-available favorite countries, and support add/remove-favorite via long-press on a country row (mobile touch only).

## Acceptance criteria
1. When at least one favorite country is available, a pinned "Favorites" section appears above the regular alphabetical country list, listing favorite countries with their server counts (reusing existing row rendering).
2. When no favorite country is currently available (none set, or all absent from sync), the pinned section is not shown and the regular list renders unchanged.
3. Long-pressing a country row (in either the favorites section or the regular list) presents an add/remove-favorite action (e.g. `PopupMenu` or dialog) reflecting current favorite state.
4. Tapping (short tap) a country row in the favorites section navigates to that country's servers exactly like tapping it in the regular list.
5. Favoriting/unfavoriting updates the pinned section immediately without requiring a manual refresh.
6. Existing single-tap navigation and non-favorites behavior of the countries screen is unchanged.

## Out of scope
- Servers-within-country screen changes (SUB-03).
- TV/D-pad interaction (SUB-04).
- Availability-filtering logic itself (implemented in SUB-01; this sub-plan only consumes it).

## dependsOn
SUB-01
