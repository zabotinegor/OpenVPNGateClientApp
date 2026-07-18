# Orchestration Context: SUB-03

## Discovered during master-plan BA (do not re-discover)
- Affected directories: `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/` (CountryServersActivity.kt, ServerPickerAdapter.kt, CountryServersViewModel.kt, CountryServersContract.kt), `src/core/src/main/res/layout/item_server_row.xml`.
- Stack markers found: ViewBinding, MVI-style contract, `Server.kt` model with `id`, `ping`, `signalStrength`, `city`, `country` fields (no built-in availability flag).
- Integration points: `ServerPickerAdapter` currently exposes `onClick: (Server) -> Unit` only; no long-click support exists yet.

## Key decisions made
- Favorites layout: pinned section at top of the same list (not a separate Activity/page), mirroring SUB-02's approach for consistency.
- Availability = presence in the current `CountryServersInteractor.getServersForCountry()` (or equivalent) result set, via SUB-01's filtering function.

## Dependencies from prior sub-plans
- SUB-01 output: favorites store (add/remove/query favorite server ids) and a filtering function that returns only currently-available favorite servers, already implemented and unit-tested. This sub-plan wires UI to that API only.
- Independent of SUB-02 (different screen, different adapter) — can run in parallel with it.

## Skip in BA step
- Full repo scan (already done).
- Stack detection (already done).
- Servers screen file discovery (already done — see paths above).
