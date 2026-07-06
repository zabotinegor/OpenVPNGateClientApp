# Orchestration Context: SUB-05

## Discovered during master-plan BA (do not re-discover)
- Affected directories: `tests/manual-e2e/` (environment/stories/cases/suites structure per README).
- Stack markers found: existing manual E2E suite structure with `tests/manual-e2e/README.md` describing organization (environment, stories, cases, suites, QA knowledge index).
- Integration points: existing unit test examples for this area — `CountryServersViewModelTest.kt`, `SelectedCountryStoreTest.kt`, `CountryListAdapterTest.kt` (patterns: fake interactors, Flow assertions, `MainDispatcherRule`) — useful reference for what SUB-01 through SUB-04 already cover with automated tests, so this sub-plan should focus on real-device manual scenarios not already automatable.

## Key decisions made
- Availability hide/restore verification (AC3) is the key manual scenario proving SUB-01's absence-from-list rule end-to-end on a device, since it can't be fully proven by unit tests alone (requires real sync timing).

## Dependencies from prior sub-plans
- SUB-04 output: full favorites feature (data layer, mobile UI on both screens, TV D-pad interaction) implemented, merged, and available for manual verification on real devices.

## Skip in BA step
- Full repo scan (already done).
- Stack detection (already done).
- Manual E2E suite structure discovery (already done — see `tests/manual-e2e/README.md`).
