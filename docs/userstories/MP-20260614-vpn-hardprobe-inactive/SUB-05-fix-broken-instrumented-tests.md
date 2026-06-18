---
id: SUB-05
title: "Fix broken instrumented tests (MainActivitySmokeTest)"
masterPlanId: MP-20260614-vpn-hardprobe-inactive
dependsOn: []
---

# SUB-05: Fix broken instrumented tests (MainActivitySmokeTest)

## Scope

Fix all 7 failing instrumented tests in `MainActivitySmokeTest` that throw
`NoActivityResumedException`. These tests have been failing since at least SUB-01 and are
documented as pre-existing in `docs/runbooks/solutions.md`.

## Current Failure

All 7 tests fail with the same error:

```
androidx.test.espresso.NoActivityResumedException: No activities in stage RESUMED.
Did you forget to launch the activity. (test.getActivity() or similar)?
```

**Failing tests:**
1. `mainActivity_launches_and_toolbar_is_visible`
2. `connectionControls_are_visible_on_launch`
3. `openDrawer_drawerOpens_and_navViewVisible`
4. `openDrawer_and_clickSettings_opensSettingsScreen`
5. `openDrawer_and_clickAbout_opensAboutScreen`
6. `openDrawer_and_clickDns_opensDnsScreen`
7. `openDrawer_and_clickFilter_opensFilterScreen`

## Root Cause Analysis

The tests use `ActivityScenarioRule` or similar Espresso mechanisms that expect the activity
to be in RESUMED state. Possible causes:

1. **Splash animation delay** — The app shows a splash screen animation before reaching
   MainActivity. If the test launches the activity before the splash completes, the activity
   may not reach RESUMED state in time.

2. **Koin/DI initialization timing** — The app performs server preload and Koin initialization
   during splash. If this takes too long, Espresso times out before the activity resumes.

3. **VPN permission dialog** — On fresh installs, the app requests VPN permission which
   blocks the activity from reaching RESUMED state.

4. **Update dialog** — The app shows an update dialog on launch which may interfere with
   Espresso's activity state detection.

## Acceptance Criteria

1. All 7 `MainActivitySmokeTest` tests pass on a connected device (`connectedDebugAndroidTestApp`).
2. Tests pass on both fresh install and subsequent launches.
3. No test relies on timing hacks (e.g., `Thread.sleep`) — use proper Espresso idling
   resources or `ActivityScenario` lifecycle callbacks.
4. The fix is minimal — do not refactor unrelated test infrastructure.
5. `assembleDebugApp` and `testDebugUnitTestApp` still pass.

## Out of scope

- Adding new instrumented tests beyond fixing the existing broken ones.
- Refactoring the splash screen or DI initialization.
- Fixing the `ExampleInstrumentedTest` (placeholder test).

## Evidence Location

- Test file: `src/mobile/src/androidTest/java/com/yahorzabotsin/openvpnclientgate/mobile/MainActivitySmokeTest.kt`
- Known issue doc: `docs/runbooks/solutions.md`
- Instrumented test output: `connectedDebugAndroidTestApp` task results
