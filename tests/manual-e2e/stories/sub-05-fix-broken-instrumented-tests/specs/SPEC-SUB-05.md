# SPEC-SUB-05: Fix Broken Instrumented Tests — Manual QA Spec

## Story reference
- Story ID: SUB-05
- Story path: docs/userstories/MP-20260614-vpn-hardprobe-inactive/SUB-05-fix-broken-instrumented-tests.md
- Branch: fix/sub-05-instrumented-tests
- Devices: Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY), Xiaomi Mi 9T Pro Android 11 (b6e8f6bd)

## What was changed
- Removed `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` from `ActivityScenario.launch()` intents in both `MainActivitySmokeTest` and `MainActivityUiTest`
- Added `dismissUpdatePromptIfVisible()` calls in test helpers to handle async update dialog
- Documented Samsung Freecess/GameSDK limitation in `android-qa.md` runbook

## QA scope
This story fixes pre-existing broken instrumented tests. The Android QA surface covers:
1. **Instrumented test execution** — all 7 `MainActivitySmokeTest` cases must pass via `connectedDebugAndroidTestApp`
2. **Regression smoke** — app install, launch, navigation, and VPN flow must remain functional
3. **Multi-device coverage** — tests validated on both Samsung (One UI) and Xiaomi (MIUI) devices

## Acceptance criteria mapping
- AC-1: All 7 `MainActivitySmokeTest` tests pass on connected device → covered by MQ-SUB05-001, MQ-SUB05-002
- AC-2: Tests pass on fresh install and subsequent launches → covered by MQ-SUB05-001 (fresh), MQ-SUB05-003 (subsequent)
- AC-3: No test relies on timing hacks → covered by code review in MQ-SUB05-004
- AC-4: Fix is minimal → covered by code review in MQ-SUB05-004
- AC-5: `assembleDebugApp` and `testDebugUnitTestApp` still pass → covered by MQ-SUB05-005

## Out of scope
- Adding new instrumented tests
- Refactoring splash screen or DI initialization
- Fixing `ExampleInstrumentedTest` (placeholder)
- TV module instrumented tests

## Test cases
- MQ-SUB05-001: Instrumented tests pass on Samsung A71 (fresh install)
- MQ-SUB05-002: Instrumented tests pass on Xiaomi Mi 9T Pro
- MQ-SUB05-003: Manual smoke — app launch and navigation regression
- MQ-SUB05-004: No timing hacks in test code
- MQ-SUB05-005: Unit tests and debug build still pass
