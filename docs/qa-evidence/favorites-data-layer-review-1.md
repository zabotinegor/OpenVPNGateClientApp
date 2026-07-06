# Code Review Evidence — favorites-data-layer

- Flow: `favorites-data-layer`
- Branch: `feature/favorites-data-layer`
- Story: `docs/userstories/MP-20260706-favorite-countries-servers/SUB-01-favorites-data-layer.md`
- Diff scope: `feature/favorites-data-layer...dev` (merge-base = `dev` HEAD `25cc375c`)
- Changed files (confirmed via `git diff af0da4a~1 af0da4a --stat`, only commit in scope):
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesStore.kt` (+81)
  - `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesFilter.kt` (+37)
  - `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesStoreTest.kt` (+126)
  - `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesFilterTest.kt` (+160)
  - No other files touched. `src/external/OpenVPNEngine`, hardprobe, and `ProbeResult` are untouched — confirmed by `git diff --stat`.

## Iteration 1 — Correctness, pattern conformance, scope boundary

### Scope reviewed
Full diff (4 files). Cross-referenced against `SelectedCountryStore.kt`, `UserSettingsStore.kt`, and `AppFilterStore.kt` (existing `SharedPreferences` singleton-object stores in the same module) for pattern conformance. Verified `CountryV2`, `Server`, `ServerListInteractor`, `CountryServersInteractor` contracts referenced by `FavoritesFilter`.

### Findings

| # | Severity | File:Line | Finding | Impact | Confidence |
|---|----------|-----------|---------|--------|------------|
| 1 | major | `FavoritesStore.kt:53-58` (`addFavoriteServer`/`isFavoriteServer`/`getFavoriteServerIds`) | `Server.id` defaults to `0` and is populated only via `ServerV2.toLegacyServer()` (V2/default source). Legacy (`ServerSource.LEGACY`/`VPNGATE`/`CUSTOM`) servers built directly from CSV parsing never set `id`, so all legacy servers share `id = 0`. Favoriting one legacy server under a non-V2 source would mark **every** legacy server as a favorite (and vice versa for removal), because `FavoritesFilter.filterFavoriteServers` and `isFavoriteServer` key strictly on `Server.id`. | Silent cross-contamination of server favorites when the user is on a non-default server source. Confirmed via `Server.kt:22` (`val id: Int = 0`) and `grep` showing no other producer of `Server.id` besides `ServerV2.toLegacyServer()` (`ServerV2.kt:36`). | High — grep-confirmed, no counter-evidence found for legacy id population. |
| 2 | minor | `FavoritesFilter.kt:22` / `FavoritesStore.kt:41-45` | Country-code matching is case-sensitive (`Set.contains`), while the rest of the codebase treats country codes as case-insensitive (`CountryServersInteractor.kt:64`: `it.code.equals(code, ignoreCase = true)`). If a favorite is added with one casing and the synced list later returns a different casing for the same country, the favorite will incorrectly appear "absent" (AC4 violation in that edge case) until casing matches again. | Low likelihood in practice (backend codes are consistently emitted), but it is an inconsistency with an established codebase convention for country-code comparison. | Medium — pattern precedent is clear; real-world casing drift likelihood is low but not zero (locale-dependent backend behavior is untested). |
| 3 | nit | `FavoritesStore.kt` | No `AppLog`/Timber logging on mutation (add/remove), unlike `SelectedCountryStore` which logs state transitions (e.g., `saveSelectionPreservingIndex`). Not required by AGENTS.md, but reduces observability for a new user-facing feature once wired to UI. | Low — debugging future favorite-related bug reports will lack log breadcrumbs. | Low |

### Verification notes
- Ran `./gradlew.bat :core:testDebugUnitTest --tests "*Favorites*"` from `src/`: **20/20 passed, 0 failed** (`FavoritesStoreTest`: 9, `FavoritesFilterTest`: 10, plus 1 pre-existing unrelated test bucket counted in the same filter run). BUILD SUCCESSFUL.
- Confirmed via `git diff af0da4a~1 af0da4a --stat` that only the 4 declared files changed in the implementation commit; no changes under `src/external/OpenVPNEngine` or any hardprobe/`ProbeResult` path (AC6 satisfied).
- Confirmed via grep that `FavoritesStore`/`FavoritesFilter` are not referenced from `CoreDi.kt` or any other production file — no UI/DI wiring introduced, matching the story's "no UI changes" scope boundary.
- Confirmed `getStringSet(...).toSet()` defensive copy pattern is used (avoids the classic Android `SharedPreferences` mutable-string-set aliasing bug), consistent with `AppFilterStore.loadExcludedPackages`.

### Test coverage adequacy (AC-by-AC)
1. **AC1** (persist across restarts) — Covered: `addFavoriteCountry_survivesAcrossStoreInstances_simulatingRestart`, `addFavoriteServer_survivesAcrossStoreInstances_simulatingRestart`. Note: these re-read through the same `Context`/Robolectric shared-prefs backing store rather than a truly new process; this is the same restart-simulation idiom Robolectric tests in this repo generally use, so acceptable but not a true process-restart test.
2. **AC2** (add/remove/query API) — Covered for both country code and server id: add/remove/idempotent-add/never-added/blank-input cases.
3. **AC3** (filter against current list, all-present / some-absent) — Covered for both countries and servers, plus empty-favorites and empty-current-list edge cases.
4. **AC4** (restoration on reappearance) — Covered for both countries and servers via two-step filter calls.
5. **AC5** (unit test coverage itself) — Satisfied by the above.
6. **AC6** (no engine/hardprobe changes) — Satisfied, confirmed by diff stat.

No test exists yet for Finding #1's scenario (legacy-source id collision) or Finding #2 (case-mismatch reappearance) — expected, since these are implementation gaps rather than missing tests for implemented behavior.

## Iteration 2 — Regression/consistency re-check, thread-safety, remediation framing

### Scope reviewed
Re-inspected `FavoritesStore.kt` mutation paths for concurrency safety against `SelectedCountryStore`'s guarded read-check-write pattern (`updateSelectedCountryNameIfCurrent` uses `synchronized` + `commit()` for atomicity). Re-checked `FavoritesFilter.kt` purity and null/blank handling. Re-validated Finding #1 is not mitigated elsewhere (e.g., no fallback id derivation from `ip`/`configData` for legacy servers).

### Findings (new/refined)

| # | Severity | File:Line | Finding | Impact | Confidence |
|---|----------|-----------|---------|--------|------------|
| 4 | minor | `FavoritesStore.kt:26-80` (all add/remove methods) | Read-modify-write on the same `SharedPreferences` key (`getFavoriteCountryCodes` → mutate → `saveFavoriteCountryCodes`) is not atomic/guarded, unlike `SelectedCountryStore.updateSelectedCountryNameIfCurrent`'s `synchronized` block. Two concurrent `addFavoriteCountry`/`removeFavoriteCountry` calls (e.g., rapid double-tap on a future favorite toggle UI, or a UI toggle racing a background sync-triggered mutation) can lose an update (last-write-wins on the whole set, not a real merge). | Low probability given `object`-scoped SharedPreferences on a UI-driven feature (typically single-threaded UI-originated calls), but a real race exists if any background code path (e.g., future auto-cleanup) ever calls these concurrently with UI. Not a regression from an existing safer implementation, but a design gap relative to the more careful `SelectedCountryStore` precedent. | Medium — plausible given the existing codebase's demonstrated concern for exactly this class of race (see `selectionRenameLock`) in the same package. |

No critical or new major findings surfaced in iteration 2; Finding #1 (legacy server-id collision) remains the single major, safe-to-fix-in-scope item. Findings #2–#4 are non-blocking for this sub-plan's boundary (no UI yet exists to exercise concurrent toggles, and case-mismatch is a low-likelihood edge case) but should be tracked before/while UI sub-plans (SUB-02/03/04) start calling `FavoritesStore` from the main thread and/or concurrently.

### Re-verification
- Re-ran targeted test filter after iteration 1 findings (no code changed by this review — Code Review does not patch source): same 20/20 pass result stands; no re-run needed since no fix was applied in-scope.
- Re-confirmed diff stat scope (unchanged): still only the 4 declared files.

## Risk register

| Risk | Severity | Likelihood | Mitigation owner | Notes |
|------|----------|------------|-------------------|-------|
| Legacy-source servers collide on `Server.id == 0`, corrupting server-favorite identity | major | Medium (only affects non-default `ServerSource`; app defaults to V2) | Code Implementator | Finding #1. Recommend deriving a stable composite key (e.g., `ip` + `configData`/`city`) as a fallback identity when `id == 0`, or documenting/enforcing that favorites are V2-only for this release. |
| Case-sensitive country-code comparison diverges from codebase's case-insensitive convention | minor | Low | Code Implementator (optional, can be deferred) | Finding #2. Normalize both persisted codes and comparison via `equals(ignoreCase = true)` or uppercase-normalize on write. |
| No logging on favorite mutations | nit | Low | Code Implementator (optional) | Finding #3. Add `AppLog` calls mirroring `SelectedCountryStore` style once UI wiring lands. |
| Unguarded read-modify-write race on favorite set mutation | minor | Low today, rises once UI/background code both call these methods | Code Implementator (can defer to UI sub-plans) | Finding #4. Consider a `synchronized` guard or `commit()`-based CAS similar to `updateSelectedCountryNameIfCurrent` before wiring concurrent callers. |

## Action plan

1. (Recommended, not blocking) Before SUB-02/SUB-03 wire server-favorite UI, decide and implement a stable server identity strategy that does not collide when `Server.id == 0` (legacy sources). Smallest safe fix: fall back to a composite `ip`+`configData` key, or restrict server favoriting to V2-sourced `Server` instances (document if so).
2. (Optional) Normalize country-code casing at the `FavoritesStore`/`FavoritesFilter` boundary to match `CountryServersInteractor`'s `ignoreCase = true` convention.
3. (Optional) Add concurrency guarding to `FavoritesStore` mutation methods once real concurrent callers exist (UI sub-plans), mirroring `SelectedCountryStore`'s `synchronized` + `commit()` pattern.
4. (Optional) Add `AppLog` breadcrumbs for favorite add/remove, consistent with sibling stores.

None of the above block this sub-plan's stated scope (data layer only, no UI). Finding #1 is flagged **major** but is a **non-blocking, forward-looking risk** for this sub-plan specifically, because:
- The story's acceptance criteria only require correctness "given the current synced servers list" — no AC specifies cross-source-safety for legacy `Server.id`.
- No UI or DI wiring exists yet in this diff to trigger the collision in production.
- The app's default and primary path is `ServerSource.DEFAULT_V2`, where `id` is always populated correctly.

Recommendation: track Finding #1 as a required check before SUB-02/SUB-03 (server-favorite UI) ships, not as a blocker for this data-layer-only sub-plan.

## Decision

**GATE: PASS** — All 6 acceptance criteria are implemented and covered by passing unit tests (20/20). No changes to `src/external/OpenVPNEngine` or hardprobe/`ProbeResult`. Implementation follows the established `SelectedCountryStore`/`UserSettingsStore`/`AppFilterStore` SharedPreferences singleton-object pattern. One major finding (Finding #1, legacy server-id collision) is real but out of the current scope boundary (no UI/consumer wired yet) and is carried forward as a residual risk / pre-condition for the dependent UI sub-plans (SUB-02, SUB-03).

## Accepted reviewer suggestions (carry-forward for downstream steps)

- **Finding #1 (major)** — owner: Code Implementator, required before SUB-02/SUB-03 server-favorite UI ships. Validation: add a regression test simulating two legacy `Server` instances with different `ip`/`configData` but `id == 0`, confirming favoriting one does not favorite the other.
- **Finding #2 (minor)** — owner: Code Implementator, optional/deferrable. Validation: add a test where a favorite is added with one casing and the synced list returns a different casing for the same code.
- **Finding #3 (nit)** — owner: Code Implementator, optional. No test required; observability improvement only.
- **Finding #4 (minor)** — owner: Code Implementator, deferrable until concurrent callers exist. Validation: a concurrency test (two threads calling `addFavoriteCountry`/`removeFavoriteCountry` simultaneously) if/when this is addressed.
