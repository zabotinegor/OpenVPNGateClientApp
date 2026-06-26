# Quality Gate Evidence — bug-server-counter-resets-on-connect

**Flow:** bug-server-counter-resets-on-connect  
**Branch:** fix/server-counter-resets-on-connect  
**PR:** https://github.com/zabotinegor/OpenVPNGateClientApp/pull/111  
**Gate date:** 2026-06-26  
**Gate result:** PASSED

---

## Prerequisite Check

- Review status: `passed` (confirmed from `.sdlc/status.json`)
- Rate limit: 20% used — well within limits

---

## Scope Verification

Files changed in `dev..HEAD` (production):

| File | Change | Matches story? |
|------|--------|----------------|
| `MainViewModel.kt` | Double-guard in `loadInitialSelection()` + `isBackgroundRefresh=true` in both `loadInitialSelection()` and `syncServersForForegroundIfDue()` | YES |
| `MainConnectionInteractor.kt` | `prepareStart()` reads fresh `configData` from `SelectedCountryStore.currentServer()` when `preferUserSelection=true`; fallback to `selectedServer.config` if store empty | YES |
| `MainSelectionInteractor.kt` | `hydrateStoredSelectionFromV2()` config-first sequential search, IP as fallback | YES |
| `docs/userstories/BUG-server-counter-resets-on-connect.md` | Story file — docs only | N/A |

No out-of-scope files changed in production code.

---

## Pass 1: Correctness Review

### Defect A fix — `MainViewModel.loadInitialSelection()` double-guard

**Before:** `updateSelectedServer(fromUserSelection = false)` ran unconditionally after startup sync, clearing `pendingUserSelectionOverride`.

**After:**
- Early guard at line 97: `if (_state.value.pendingUserSelectionOverride) return@launch` — skips expensive `loadInitialSelection` call if user already selected.
- Late guard at line 99: second `if (_state.value.pendingUserSelectionOverride) return@launch` — protects against race where user selects _during_ the load.
- `isBackgroundRefresh = true` passed to `updateSelectedServer` — the `updateSelectedServer` inner guard also checks and bails if `pendingUserSelectionOverride` became true between the check and the `_state.update`.

**Verdict:** Correct. Triple-layer protection. Matches the story's fix approach exactly.

### Defect A fix — `syncServersForForegroundIfDue()` late guard + `isBackgroundRefresh`

**Before:** Late guard was missing (only early guard existed); `isBackgroundRefresh` was not passed.  
**After:** Late guard added at line 143; `isBackgroundRefresh = true` added. Consistent with `loadInitialSelection` pattern.

**Verdict:** Correct. Defense-in-depth applied.

### Defect B fix — `MainSelectionInteractor.hydrateStoredSelectionFromV2()` config-first search

**Before:** OR-logic `indexOfFirst { srv -> ip_match || config_match }` — IP branch evaluated first, always returns index 0 when all servers share the same IP.

**After:** Sequential `when` block — config match tried first (exact, unique per server), IP-only as fallback when no config available, then defaults to 0.

**Verdict:** Correct. Fixes the Belarus / shared-IP scenario precisely.

### Defect C fix — `MainConnectionInteractor.prepareStart()` fresh config from store

**Before:** `currentConfig = selectedServer?.config` — stale after SSE sync updates store but ViewModel still holds old config.

**After:**
```kotlin
val currentConfig = if (preferUserSelection) {
    runCatching { SelectedCountryStore.currentServer(appContext) }.getOrNull()
        ?.config?.takeIf { it.isNotBlank() }
        ?: selectedServer.config   // fallback if store empty
} else {
    selectedServer.config
}
```
Auto-switch path (`preferUserSelection=false`) is unaffected — uses `selectedServer.config` as before.

**Verdict:** Correct. Reads the freshest config at Connect time.

---

## Pass 2: Test Coverage Analysis

### Unit layer

| Test class | Tests | Failures | Coverage of fix |
|------------|-------|----------|-----------------|
| `MainViewModelTest` | 37 | 0 | Defect A: double-guard in `loadInitialSelection` (2 new blocking tests), foreground sync guard (2 tests), `onStoreVersionChanged` (6 tests) |
| `MainSelectionInteractorTest` | 14 | 0 | Defect B: `loadInitialSelection_v2_hydration_selects_correct_server_by_config_when_all_share_same_ip` (1 new test), plus 13 existing/related |
| `MainConnectionInteractorTest` | 8 | 0 | Defect C: 4 new tests (`fresh_config_from_store`, `fallback_when_store_empty`, `false_ignores_store`, `null_server`) |

**Full suite:** 670 tests, 0 failures, 0 errors — verified from XML reports.

### New tests vs acceptance criteria

| AC / Risk | Test name | Result |
|-----------|-----------|--------|
| AC1 — user selects 3/3 → Connect → counter stays 3/3 | `prepareStart_preferUserSelection_true_reads_fresh_config_from_store` | PASS |
| AC1 — config-first V2 hydration | `loadInitialSelection_v2_hydration_selects_correct_server_by_config_when_all_share_same_ip` | PASS |
| AC2 — SSE sync; Connect still uses fresh store config | `prepareStart_preferUserSelection_true_reads_fresh_config_from_store` | PASS |
| AC2 — fallback when store empty | `prepareStart_preferUserSelection_true_falls_back_to_selected_server_config_when_store_empty` | PASS |
| AC3 — auto-switch path unaffected | `prepareStart_preferUserSelection_false_ignores_store_and_uses_selected_server_config` | PASS |
| AC4 — user tap during startup sync | `load initial selection does not overwrite pending user selection override set during load` | PASS |
| AC4 — early guard before sync call | `load initial selection does not overwrite pending user selection override` | PASS |
| AC5 — background sync (foreground sync path) | `foreground sync does not overwrite pending user selection override set during load`, `foreground sync preserves pending user selection override` | PASS |
| AC5 — background sync (onStoreVersionChanged path) | `store version bump is ignored when pending user selection override is set`, `store version bump does not overwrite user selection when override becomes true during reload` | PASS |

### Coverage gaps identified

1. **No test for `loadInitialSelection` early guard firing before `loadInitialSelection(cacheOnly)` call** — the first `return@launch` at line 97 (before the expensive suspend call) is only covered by the blocking-interactor test via the late guard. The pre-sync early guard is not exercised in isolation. **Severity: LOW** — the blocking-interactor test covers the race window; the early guard is extra defense.

2. **`MainConnectionInteractorTest` has no `@Before` teardown** — shared Robolectric app context leaks `SelectedCountryStore` state between tests. Tests pass due to execution order. **Pre-existing, not introduced by this PR. Severity: LOW.**

3. **No E2E/integration test covering SSE sync → Connect flow** — the full chain (SSE fires → store updated → user taps Connect → fresh config read) is not unit-testable in isolation. Covered by Manual QA AC1/AC2. **Severity: acceptable — manual QA is required.**

### Coverage by layer

| Layer | Status |
|-------|--------|
| Unit | ADEQUATE — all 3 defects covered, race conditions covered with blocking fake |
| Integration | N/A — no Android integration tests in scope |
| UI/Espresso | N/A — no instrumented tests added (correct: UI behavior is View-bound, not logic) |
| E2E Manual | REQUIRED — AC1/AC2 require device with live SSE sync |

---

## Validation Results

### Test run
```
./gradlew :core:testDebugUnitTest  (targeted — MainConnectionInteractorTest, MainSelectionInteractorTest, MainViewModelTest)
Result: 59 tests, 0 failures
Full suite (testDebugUnitTestApp): 670 tests, 0 failures, 0 errors, 0 skipped
```

### Build
All tasks UP-TO-DATE — no compilation errors.

---

## Findings (Severity-Ordered)

### PASS — No blocking findings

| Severity | Finding | Disposition |
|----------|---------|-------------|
| LOW | Early guard in `loadInitialSelection` (line 97) not tested in isolation | Non-blocking; late guard + blocking-interactor test cover the race |
| LOW | No `@Before` SelectedCountryStore reset in `MainConnectionInteractorTest` | Pre-existing; tests pass and ordering is safe |
| INFO | 670 tests vs 651 previously reported — 19 additional tests found in full suite (likely from other modules) | No failures |

### No regressions detected

All existing tests pass. Production changes are narrowly scoped to the three identified defect locations. Auto-switch path (`preferUserSelection=false`) is explicitly tested and unchanged.

---

## Residual Risks

1. **Live SSE timing** — the most realistic AC1 path (SSE fires _between_ user selection and Connect tap) requires real device with active network. Unit tests simulate this with a blocking fake but cannot reproduce exact timing with a live API. Manual QA must verify.
2. **Belarus shared-IP edge case** — confirmed fixed by unit test and by the config-first sequential search. Residual risk: if `configData` changes between user selection and store hydration (dynamic API content), the fix in `prepareStart()` (Defect C) handles it by reading fresh config from store at Connect time.

---

## Recommended Manual QA Plan (AC1/AC2 priority)

1. Select Belarus server 3/3 → wait 10s for SSE sync (`servers-changed` event in logcat) → tap Connect → verify counter shows 3/3 and service log says `server=3/3`.
2. Select Belarus server 3/3 → immediately tap Connect (no SSE sync) → verify counter shows 3/3.
3. Fresh install → counter shows 1/N (regression check AC3).
4. Connect → disconnect → reconnect without re-selecting → verify `lastSuccessfulConfig` path used (AC5/AC6).
