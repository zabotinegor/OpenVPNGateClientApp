# Quality Gate Evidence — favorites-data-layer

- Flow: `favorites-data-layer`
- Branch: `feature/favorites-data-layer`
- Story: `docs/userstories/MP-20260706-favorite-countries-servers/SUB-01-favorites-data-layer.md`
- Diff scope: `feature/favorites-data-layer...dev` (story-relevant delta isolated to commit `af0da4a`)
- Commit under gate: `af0da4a` (HEAD `1dc669c` is a no-op "agent sync" commit on top, no source changes)
- Prior step: `steps.review.status=passed` (confirmed in `.sdlc/status.json`, evidence `docs/qa-evidence/favorites-data-layer-review-1.md`, 2 review iterations, 0 blocking findings)
- Changed files in scope (confirmed via `git diff af0da4a~1 af0da4a --stat`):
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesStore.kt` (+81)
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesFilter.kt` (+37)
  - `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesStoreTest.kt` (+126)
  - `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesFilterTest.kt` (+160)
  - `feature/favorites-data-layer...dev` also shows unrelated tooling/docs/agent-sync churn (`.github/scripts/*`, `.claude/commands/*`, master-plan story docs) inherited from `dev` divergence — not part of this sub-plan's implementation and out of gate scope.

## Iteration 1 — Coverage adequacy, edge cases, performance, security surface

### Validation run
- `./gradlew.bat :core:testDebugUnitTest --tests "*Favorites*" --rerun` from `src/`: **20/20 passed, 0 failed** (`FavoritesStoreTest`: 9, `FavoritesFilterTest`: 11). BUILD SUCCESSFUL in 45s.
- `./gradlew.bat testDebugUnitTestApp` (full aggregate unit suite, mobile+tv+core): BUILD SUCCESSFUL, all tasks up-to-date/cached green, consistent with the 677-total/0-failed baseline recorded at `implementation` step.
- `git diff af0da4a~1 af0da4a --stat`: confirmed only the 4 declared files changed; no touch to `src/external/OpenVPNEngine` or any hardprobe/`ProbeResult` path (AC6 re-confirmed).

### Test coverage adequacy by layer

| Layer | Coverage | Assessment |
|---|---|---|
| Unit — `FavoritesStore` | 9 tests: add/remove/query for both country and server favorites, restart-simulation persistence, idempotent duplicate add, blank-country-code guard, never-added query | Adequate for AC1/AC2. |
| Unit — `FavoritesFilter` | 11 tests: all-present, some-absent (with persistence-untouched assertion), empty-favorites, empty-current-list, reappearance-restoration — each duplicated for countries and servers | Adequate for AC3/AC4. |
| Component/Integration | None (no DI wiring exists yet — story is explicitly data-layer-only, no consumer wired to `CoreDi.kt`) | Expected gap, matches story scope boundary; confirmed via grep that `FavoritesStore`/`FavoritesFilter` are unreferenced elsewhere. |
| UI / E2E | None | Correctly out of scope per story ("No UI changes in this sub-plan") and per master-plan (`SUB-02`..`SUB-05` own UI/E2E). |

### Edge-case checklist (explicit verification against source, not just existing tests)

| Case | Behavior | Verified |
|---|---|---|
| Empty favorite sets | `filterFavoriteCountries`/`filterFavoriteServers` short-circuit to `emptyList()` when `favoriteXxxIds.isEmpty()` | Yes — covered by `filterFavoriteCountries_noFavorites_returnsEmpty` / server equivalent, and by direct source read (`FavoritesFilter.kt:21`, `:34`). |
| Empty current list | Same short-circuit on `countries.isEmpty()` / `servers.isEmpty()` | Yes — covered by `..._emptyCurrentList_returnsEmpty` tests. |
| Blank/empty country code on add | `addFavoriteCountry` guards `if (countryCode.isBlank()) return` | Yes — covered by `addFavoriteCountry_blankCodeIgnored`. |
| Blank code on remove | `removeFavoriteCountry` has **no** blank-code guard, but is a no-op by construction: `current.remove("")` on a set that never contains `""` (since add always rejects blank) returns `false`, so `saveFavoriteCountryCodes` is skipped. Functionally safe, just asymmetric with `addFavoriteCountry`'s explicit guard. | Not explicitly tested, but verified by code inspection (`FavoritesStore.kt:34-39`); low-risk since the `Set.remove` return-value check already prevents a wasted write. Non-blocking observation, not a defect. |
| Duplicate add | `current.add(x)` returns `false` on duplicate, `saveFavoriteXxx` is skipped entirely — avoids a redundant write | Yes — covered by `addFavoriteServer_duplicateAddIsIdempotent`; country side has equivalent logic (untested by name but same code path, low risk). |
| Server id default (`0`) collision for non-V2 sources | `Server.id` defaults to `0` (`Server.kt:22`) and is populated only by `ServerV2.toLegacyServer()` (`ServerV2.kt:12,17`); legacy/VPNGate/custom CSV-parsed servers never set `id`, so they all collide on `0` | Confirmed by source inspection, not newly tested in this iteration (already flagged as review Finding #1). See assessment below — accepted as non-blocking for this sub-plan. |
| Case sensitivity of country codes | `FavoritesFilter.filterFavoriteCountries` and `FavoritesStore.isFavoriteCountry` use plain `Set.contains`/`equals` (case-sensitive), while `CountryServersInteractor.kt:64` uses `it.code.equals(code, ignoreCase = true)` | Confirmed by source inspection (`FavoritesFilter.kt:22`, `CountryServersInteractor.kt:64`). See assessment below. |
| Negative/out-of-range server id | `addFavoriteServer`/`isFavoriteServer` accept any `Int` including negative — no domain validation, but no defect either, since a negative id simply never matches any real `Server.id` | Not explicitly tested; low-risk, no crash path, `toIntOrNull()` round-trip in `getFavoriteServerIds` handles negative ints correctly (`"-1".toIntOrNull() == -1`). |
| Corrupt/malformed stored string in `getFavoriteServerIds` | `mapNotNull { it.toIntOrNull() }` silently drops any non-numeric entry rather than crashing | Defensive by construction; not explicitly tested but low risk given `SharedPreferences` string set is only ever written by `saveFavoriteServerIds` using `Int.toString()`. |

### Performance impact — SharedPreferences read/write pattern

- Each `addFavoriteXxx`/`removeFavoriteXxx` call does one `getStringSet` read + (conditionally) one `putStringSet`+`apply()` write. `apply()` is asynchronous (queued to a background thread by the Android framework), consistent with every other store in this package (`SelectedCountryStore`, `UserSettingsStore`, `AppFilterStore`) — no new performance pattern introduced.
- Read-before-write is O(n) copy of the favorite set (`toMutableSet()`), which is bounded by the number of favorites a user realistically sets (expected to be small, tens at most) — no performance concern at this scale.
- No favorites-related code is on the SSE/sync hot path in this diff (`ServerSelectionSyncCoordinator`, `SseServerEventsClient` are untouched) — filtering is only ever invoked with in-memory lists already fetched by existing interactors, so no additional I/O is introduced by this story.
- Unguarded read-modify-write (Finding #4 from code review) is a correctness/race concern under concurrent callers, not a throughput/performance concern; no consumer exists yet to trigger concurrent calls (story scope is data-layer only). Confirmed non-blocking for this gate — tracked as a residual risk for UI sub-plans.

### Security surface

- No new attack surface: `FavoritesStore` only reads/writes a process-private `SharedPreferences` file (`Context.MODE_PRIVATE`), same visibility model as every other store in the package. No network calls, no external input parsing beyond `Int.toIntOrNull()` (safe, non-throwing). No secrets or PII involved (country codes and internal server ids are not sensitive). Confirmed: no new manifest permissions, no new endpoints, no logging of favorite identifiers to any external sink (there is no logging at all yet — see Finding #3 from review, observability-only, not a security gap). **No security findings.**

## Assessment of the two carried-forward review items

### 1. Legacy `Server.id` defaults to 0 for non-V2 server source

- **Confirmed root cause**: `Server.kt:22` declares `val id: Int = 0`; the only producer that populates a non-zero id is `ServerV2.toLegacyServer()` (`ServerV2.kt:12,17`), which is exercised by `ServersV2SyncCoordinator.kt:129` and `CountryServersInteractor.kt:79` — both gated behind `ServerSource.DEFAULT_V2` (`CountryServersInteractor.kt:39,94`). Legacy/VPNGate/custom CSV-parsed servers never assign `id`, so `FavoritesFilter.filterFavoriteServers` and `FavoritesStore.isFavoriteServer` would treat all of them as sharing identity `0` if favorited.
- **Acceptable to defer**: Yes. This story's diff introduces **no consumer** of `FavoritesStore`/`FavoritesFilter` — confirmed by grep, nothing in `CoreDi.kt` or any UI/ViewModel references these two objects. The acceptance criteria (AC1–AC6) describe persistence and filtering mechanics only, given "the current synced servers list" — none of the 6 ACs assert cross-`ServerSource` identity safety, and the app's default/primary path (`ServerSource.DEFAULT_V2`) is unaffected because `id` is always correctly populated there. The collision can only manifest once a UI sub-plan (SUB-02/SUB-03) wires an actual favorite-toggle affordance for servers under a non-default source. **This is correctly a pre-condition for SUB-02/SUB-03, not a gate-blocking gap for SUB-01.** Recommendation carried forward unchanged from code review: derive a stable composite key (`ip`+`configData`) as a fallback identity when `id == 0`, or explicitly restrict server favoriting to V2-sourced servers, before SUB-02/SUB-03 ship.

### 2. Case-sensitive country-code comparison vs. `CountryServersInteractor`'s case-insensitive convention

- **Confirmed inconsistency**: `FavoritesFilter.kt:22` (`countries.filter { favoriteCountryCodes.contains(it.code) }`) and `FavoritesStore.kt:42` (`getFavoriteCountryCodes(ctx).contains(countryCode)`) both use exact `Set`/`String` equality, while `CountryServersInteractor.kt:64` explicitly does `it.code.equals(code, ignoreCase = true)` when resolving a country by code.
- **Is this a real coverage/correctness gap for this story's ACs?** Narrowly, no. AC3 and AC4 require the filter to correctly include/exclude/restore favorites "given the current synced countries list" — they do not specify a casing contract for `CountryV2.code`, and every existing test (and every real backend-driven `CountryV2` instance observed via `ServerListInteractor`) uses a single consistent casing per code (backend emits stable uppercase ISO codes; there is no evidence in this codebase of the backend ever emitting mixed casing for the same code across syncs). The case-insensitive comparison in `CountryServersInteractor` exists for a different purpose — resolving a country by a code or **name** that may originate from user-facing/relocalized input — not for matching two backend-supplied codes against each other. `FavoritesFilter` only ever compares backend-code-to-backend-code, so the risk surface is narrower than the reviewer's original framing suggested.
- **Verdict**: Confirmed **non-blocking** for this story's ACs. No test demonstrates an actual failure mode within this story's scope (backend casing is stable in practice), and fixing it now would be a speculative hardening change against a hypothetical backend behavior change, not a defect against the stated ACs. Recommend keeping this as a tracked, optional hardening item (normalize to uppercase at the `FavoritesStore` read/write boundary) to pick up opportunistically during SUB-02/SUB-03 implementation, but it does not block this gate.

## Residual risks (carried forward, non-blocking)

| Risk | Severity | Owner / when to address |
|---|---|---|
| Legacy-source `Server.id == 0` collision corrupts server-favorite identity | Major | Code Implementator, required before SUB-02/SUB-03 server-favorite UI ships |
| Case-sensitive country-code comparison diverges from `CountryServersInteractor` convention | Minor | Optional; can be picked up opportunistically in SUB-02/SUB-03 |
| Unguarded read-modify-write race on favorite set mutation (no consumer yet, so dormant) | Minor | Code Implementator, before UI sub-plans introduce concurrent callers |
| No `AppLog` breadcrumbs on favorite mutation | Nit | Optional observability improvement once UI wiring lands |
| `removeFavoriteCountry`/`removeFavoriteServer` lack an explicit blank/invalid-input guard (functionally safe today via `Set.remove` returning `false`) | Nit | Optional symmetry cleanup with `addFavoriteCountry`'s explicit guard; no defect |

## Decision

**GATE: PASS** — Test coverage is adequate for the story's stated scope (unit-only, data-layer-only; component/UI/E2E absence is correct given no consumer exists yet). All edge cases relevant to the implemented ACs (empty sets, blank/duplicate add, restart persistence, all-present/some-absent/reappearance filtering) are covered by 20/20 passing tests, re-verified in this gate run. Performance pattern matches existing sibling stores with no new I/O or hot-path impact. No security surface introduced (private prefs only, no network/PII). Both carried-forward review items (legacy `Server.id` collision, case-sensitive country-code comparison) are confirmed real but correctly scoped as pre-conditions/optional hardening for the downstream UI sub-plans (SUB-02/SUB-03), not defects against this story's acceptance criteria — no AC asserts cross-source identity safety or codes-casing-drift tolerance.
