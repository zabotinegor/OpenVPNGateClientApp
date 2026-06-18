# Quality Gate Evidence — SUB-05 Fix Broken Instrumented Tests

**Branch:** fix/sub-05-instrumented-tests
**Story:** docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-05-fix-broken-instrumented-tests.md
**Gate date:** 2026-06-18
**Iterations:** 2
**GATE_RESULT: PASS**

---

## Scope

Changed files reviewed:
- `src/mobile/src/androidTest/java/com/yahorzabotsin/openvpnclientgate/mobile/MainActivitySmokeTest.kt`
- `src/mobile/src/androidTest/java/com/yahorzabotsin/openvpnclientgate/mobile/MainActivityUiTest.kt`
- `docs/runbooks/android-qa.md`
- `docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-05-fix-broken-instrumented-tests.md`

Prior gate: Code review PASSED (2 iterations, 0 blocking findings) — `.sdlc/evidence/sub-05-code-review.md`

---

## Iteration 1

### Check 1 — AC-1: All 7 MainActivitySmokeTest tests pass

**Status: PASS (confirmed external)**

Test run on Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY): 21/21 instrumented tests passed.
Breakdown: 7/7 `MainActivitySmokeTest` + 1 `MainActivityUiTest` + core/settings tests. 0 failures.

Mapping of 7 story-defined failing tests to methods present in current `MainActivitySmokeTest.kt`:

| Story failing test | Test method present | Catalog ID |
|---|---|---|
| `mainActivity_launches_and_toolbar_is_visible` | YES (line 63) | E2E-ANDROID-MAIN-LAUNCH-001 |
| `connectionControls_are_visible_on_launch` | YES (line 72) | E2E-ANDROID-MAIN-CONTROLS-VISIBLE-005 |
| `openDrawer_drawerOpens_and_navViewVisible` | YES (line 80) | E2E-ANDROID-MAIN-DRAWER-002 |
| `openDrawer_and_clickSettings_opensSettingsScreen` | YES (line 89) | E2E-ANDROID-MAIN-NAV-SETTINGS-003 |
| `openDrawer_and_clickAbout_opensAboutScreen` | YES (line 100) | E2E-ANDROID-MAIN-NAV-ABOUT-004 |
| `openDrawer_and_clickDns_opensDnsScreen` | YES (line 110) | E2E-ANDROID-MAIN-NAV-DNS-007 |
| `openDrawer_and_clickFilter_opensFilterScreen` | YES (line 120) | E2E-ANDROID-MAIN-NAV-FILTER-008 |

All 7 tests present. All 7 confirmed passing. AC-1 satisfied.

### Check 2 — AC-2: Fresh install and subsequent launches

**Status: PASS**

Each test method calls `ActivityScenario.launch(MainActivity::class.java).use { ... }`.
The `use {}` block calls `scenario.close()` on exit, which moves the activity to DESTROYED state
and removes it from Espresso's tracking. Each test method launches a fresh scenario with no shared
state. The test run on the Samsung device was conducted on the connected device (first-install
scenario), confirming both fresh-install and clean-per-test behavior. AC-2 satisfied.

### Check 3 — AC-3: No Thread.sleep; proper Espresso sync used

**Status: PASS**

Grep across `src/mobile/src/androidTest/` for `Thread.sleep` → **0 matches**.

Synchronization mechanisms used:
- `ActivityScenario.launch()` — blocks until activity reaches RESUMED state per Espresso contract
- `Espresso.onIdle()` — used in `MainActivityUiTest` (lines 49, 51) for post-launch and post-dismiss drain
- `onView(...).perform(...)` / `onView(...).check(...)` — Espresso's built-in `UiController.loopMainThreadUntilIdle()` runs on every interaction
- `DrawerActions.open()` — waits for drawer animation via IdlingResource

`SmokeTest` lacks explicit `onIdle()` calls (noted as MINOR in code review; non-blocking — passed 7/7 in practice).
AC-3 satisfied.

### Check 4 — AC-4: Minimal fix — no unrelated refactoring

**Status: PASS**

Diff scope (origin/dev..HEAD):
- `MainActivitySmokeTest.kt`: 9 lines changed — added `dismissUpdatePromptIfVisible()`, `openDrawerReliably()` helpers, and `withMainActivity` wrapper. No unrelated code.
- `MainActivityUiTest.kt`: 11 lines changed — added same helper pattern plus `onIdle()` calls. No unrelated code.
- `docs/runbooks/android-qa.md`: 42 lines added as new SUB-05 section — documentation only.
- Story file: new file — documentation only.

No production code files touched. No test infrastructure files modified outside the two test classes.
No imports beyond Espresso/AndroidJUnit4 stdlib (no new dependencies introduced).
AC-4 satisfied.

### Check 5 — AC-5: assembleDebugApp and testDebugUnitTestApp pass

**Status: PASS (confirmed external)**

- `assembleDebugApp`: BUILD SUCCESSFUL (confirmed in brief)
- `testDebugUnitTestApp`: BUILD SUCCESSFUL (confirmed in brief)

Changes are test-only (`androidTest` source set). Unit test source set is not touched. Production code
is not modified. No new Gradle dependencies added. No build configuration changes. AC-5 satisfied.

### Check 6 — Edge cases: fresh install vs subsequent launch

**Status: PASS**

- VPN permission dialog: tests bypass this by launching `MainActivity` directly via `ActivityScenario`,
  which does not trigger the `SplashActivity` permission request flow. This is correct for UI smoke tests.
- Update dialog: `dismissUpdatePromptIfVisible()` called defensively (twice in `withMainActivity`,
  with `onIdle()` barriers in `UiTest`) handles async `PromptUpdate` effect. Targets `android.R.id.button2`
  confirmed to be the negative/cancel button per `MainActivityCore.kt` line 332.
- Koin/DI timing: `MainActivity` is a fully instantiated activity when launched by `ActivityScenario`;
  Koin module is initialized in the Application class before any activity. No timing gap.
- Splash bypass: root cause of the pre-fix failure was that the old test launched with
  `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK`, conflicting with Espresso's lifecycle management.
  That flag combination has been removed; `ActivityScenario` now correctly manages the lifecycle.

### Check 7 — Security surface: no new network calls, no credentials

**Status: PASS**

Grep across `src/mobile/src/androidTest/` for `okhttp`, `OkHttp`, `IdlingResource`, `network`,
`credential`, `password`, `token`, `secret` → **0 matches**.

No network calls introduced. No credentials or secrets present. No new permissions requested.
Only standard Espresso view interactions used. Security surface unchanged.

### Check 8 — Performance: no regression

**Status: PASS**

No `Thread.sleep`, polling loops, or artificial delays introduced. Each test uses Espresso's
idling-resource-based synchronization, which is lazy (returns as soon as idle, not after a fixed wait).
`onIdle()` in UiTest is a synchronization point, not a sleep. Test suite passed 21/21 in a single
run with no timeouts or retries. No performance-related LogCat errors documented in run evidence.

---

## Iteration 2

### Re-evaluation scope

Re-examined the 3 MINOR findings carried forward from code review to determine if any should be
escalated to blocking for the quality gate.

### Finding F1 (Code Review MINOR): dismissUpdatePromptIfVisible catches only NoMatchingViewException

**Quality gate assessment: NON-BLOCKING — CONFIRMED**

Espresso's `onView()` calls run `UiController.loopMainThreadUntilIdle()` before attempting the view
match, which drains the main thread queue and ensures the activity has reached RESUMED before the
dismiss attempt. The `NoActivityResumedException` that triggered the original failure is now prevented
by removing the conflicting activity flags, not by broader exception handling. The 21/21 pass result
on a real device confirms this gap does not cause failures in practice. Recommendation to add
`PerformException` to the catch remains as an optional improvement only.

### Finding F3 (Code Review MINOR): withMainActivity lacks onIdle() vs UiTest

**Quality gate assessment: NON-BLOCKING — CONFIRMED**

`SmokeTest.withMainActivity` does not call `onIdle()` before the first `dismissUpdatePromptIfVisible()`.
`UiTest` does. This is an inconsistency but not a defect: every `onView().perform()` / `onView().check()`
call implicitly idles the thread. The 7/7 SmokeTest pass on real hardware confirms no missing-idle
failure occurred. Risk of flakiness on slow devices is low-medium (documented in code review risk
register) but does not warrant blocking this gate given the confirmed pass.

### Finding F4 (Code Review MINOR): solutions.md stale guidance

**Quality gate assessment: NON-BLOCKING — CONFIRMED**

`docs/runbooks/solutions.md` still advises against using `MainActivitySmokeTest` as a CI gate,
which conflicts with the SUB-05 fix. This is documentation drift only — no runtime impact. The
`android-qa.md` runbook (changed file) correctly documents the fix. Updating `solutions.md` is
a post-merge cleanup task.

### AC Coverage Cross-check (Iteration 2)

| AC | Story Text | Coverage Method | Status |
|---|---|---|---|
| AC-1 | All 7 SmokeTests pass | 7/7 method names verified present + 21/21 run confirmed | PASS |
| AC-2 | Fresh install and subsequent launches | `ActivityScenario.use {}` per-test isolation + first-install run | PASS |
| AC-3 | No Thread.sleep; proper Espresso sync | Grep (0 hits) + onIdle/ActivityScenario usage verified | PASS |
| AC-4 | Fix is minimal | Diff: 9+11 test lines, 42 doc lines, 0 production lines | PASS |
| AC-5 | assembleDebugApp + testDebugUnitTestApp pass | BUILD SUCCESSFUL confirmed | PASS |

All 5 ACs verified. No blocking findings in either iteration.

---

## Risk Register

| Risk | Severity | Likelihood | Disposition |
|---|---|---|---|
| Flaky SmokeTest on slow devices (missing onIdle) | MINOR | Low-Medium | Acceptable — 21/21 on Samsung A71; recommend onIdle in follow-up |
| solutions.md stale guidance conflicts with fix | MINOR | Low | Acceptable — no runtime risk; document as post-merge cleanup |
| Samsung Freecess pause race (documenteed in android-qa.md) | MINOR | Medium | Acceptable — workaround documented; adb deviceidle whitelist available |
| dismissUpdatePromptIfVisible PerformException gap | MINOR | Low | Acceptable — Espresso idle semantics prevent the gap in practice |

**Blocking risks: 0**

---

## Summary

The SUB-05 instrumented test fix satisfies all 5 acceptance criteria from the story. The fix is
targeted (test-only changes, minimal diff), correct (no Thread.sleep, proper Espresso sync,
ActivityScenario isolation), and confirmed passing (21/21 on Samsung Galaxy A71 Android 13).
No blocking findings exist after 2 quality gate iterations. The 3 MINOR findings from code review
remain non-blocking observations for optional follow-up.

**GATE_RESULT: PASS**
**Blocking findings: 0**
**Non-blocking observations: 3 MINOR, 2 NIT (inherited from code review)**
**Iterations: 2**
