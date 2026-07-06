# Orchestration Context: SUB-01

## Discovered during master-plan BA (do not re-discover)
- Affected directories: `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/`, `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/settings/`
- Stack markers found: Kotlin/Android, Koin DI (`CoreDi.kt`), SharedPreferences-based stores.
- Integration points:
  - `UserSettingsStore.kt` and `SelectedCountryStore.kt` are the existing SharedPreferences store patterns (JSON array/object serialization) to follow for a new favorites store.
  - `ServerListInteractor.getCountriesV2()` returns `List<CountryV2>`; `CountryServersInteractor.getServersForCountry()` returns `List<Server>` (filtered by country code) — these are the "current synced list" sources for availability filtering.
  - `Country.kt` fields: `name: String, code: String?`. `Server.kt` has ~23 fields including `id`, `ping`, `signalStrength`, `city`, `country`.
  - Hardprobe/`ProbeResult.kt`/`HardProbeApiClient.kt` exist but are explicitly OUT of scope per user decision — availability = presence/absence in synced list only.

## Key decisions made
- Availability rule: absence-from-current-list (not hardprobe-based). User explicitly chose this over probe integration for smaller scope.
- Favorites persisted as country codes + server ids (not full objects) so filtering against fresh sync data is trivial.

## Dependencies from prior sub-plans
- None — this is the foundational sub-plan.

## Skip in BA step
- Full repo scan (already done).
- Stack detection (already done).
- Data source discovery for countries/servers list (already done — see integration points above).
