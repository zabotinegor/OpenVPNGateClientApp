# SUB-03: Servers-in-country screen favorites UI (mobile touch)

## Scope boundary
On the per-country servers screen (`CountryServersActivity` / `ServerPickerAdapter`), add a pinned "Favorites" section at the top of the list showing only currently-available favorite servers, and support add/remove-favorite via long-press on a server row (mobile touch only).

## Acceptance criteria
1. When at least one favorite server is available for the currently viewed country context (or globally, per implementer's design consistent with SUB-01's filtering API), a pinned "Favorites" section appears above the regular server list.
2. When no favorite server is currently available, the pinned section is not shown and the regular list renders unchanged.
3. Long-pressing a server row (favorites section or regular list) presents an add/remove-favorite action reflecting current favorite state.
4. Tapping (short tap) a server row in the favorites section selects/connects to that server exactly like tapping it in the regular list.
5. Favoriting/unfavoriting updates the pinned section immediately without requiring a manual refresh.
6. Existing single-tap selection and non-favorites behavior of the servers screen is unchanged.

## Out of scope
- Countries screen changes (SUB-02).
- TV/D-pad interaction (SUB-04).
- Availability-filtering logic itself (implemented in SUB-01; this sub-plan only consumes it).

## dependsOn
SUB-01
