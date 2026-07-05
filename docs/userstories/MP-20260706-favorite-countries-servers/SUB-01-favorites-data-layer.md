# SUB-01: Favorites data layer and persistence

## Scope boundary
Add a shared `src/core` persistence and domain layer for favoriting countries and servers: storage of favorite country codes and favorite server ids, and availability-filtering logic that hides a favorite when it is absent from the latest synced countries/servers list and restores it automatically once it reappears in a future sync. No UI changes in this sub-plan.

## Acceptance criteria
1. A new store (consistent with existing `UserSettingsStore` / `SelectedCountryStore` SharedPreferences patterns) persists a set of favorite country codes and a set of favorite server ids across app restarts.
2. Public API exists to add, remove, and query favorite status for a country code and for a server id.
3. Given the current synced countries list (from `ServerListInteractor`) and current synced servers list (from `CountryServersInteractor`), a favorites-filtering function returns only favorites whose country code / server id is present in that current list; absent favorites are excluded from the result but remain persisted.
4. Once a previously-absent favorite's country/server reappears in a subsequent sync, it is included again in the filtering function's result without any user action.
5. Unit tests cover: add/remove/persist favorite, filtering with all-present, filtering with some-absent, and restoration after re-appearance.
6. No changes are made to `src/external/OpenVPNEngine` or the hardprobe/`ProbeResult` pipeline.

## Out of scope
- Any UI (pinned sections, long-press, dialogs) — covered by SUB-02, SUB-03, SUB-04.
- Tying availability to hardprobe/health-check results.
- TV-specific interaction.

## dependsOn
None (foundational sub-plan).
