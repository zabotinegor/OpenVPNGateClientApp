# SUB-05 Docs Evidence

**Date:** 2026-06-18
**Branch:** fix/sub-05-instrumented-tests
**Step:** docs

## Files Updated

### `docs/runbooks/solutions.md`

- Replaced the stale "MainActivitySmokeTest failures: NoActivityResumedException on real device"
  entry with an updated entry marked **RESOLVED in SUB-05**.
- Corrected the root cause: `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` flags conflicting
  with `ActivityScenario` lifecycle management (not an OkHttp IdlingResource issue — no
  `IdlingRegistry` usage exists in the codebase).
- Noted the fix: removed those flags; added `dismissUpdatePromptIfVisible()`.
- Removed the outdated advice "Do not rely on MainActivitySmokeTest as a CI gate".
- Added MIUI Android 11 limitation sub-section: Xiaomi Mi 9T Pro (b6e8f6bd) blocks
  `ActivityScenario.launch()` indefinitely; idle whitelist does not help on MIUI; workaround is
  to use Samsung or stock Android devices.

### `docs/runbooks/android-qa.md`

- Updated SUB-02 "Known Issues" section: marked `MainActivitySmokeTest` failure as RESOLVED in
  SUB-05, corrected the root-cause description, removed the stale OkHttp IdlingResource
  explanation.
- Updated SUB-05 "Known Samsung device limitation" subsection: confirmed the Samsung whitelist
  workaround works — after `adb shell cmd deviceidle whitelist +com.yahorzabotsin.openvpnclientgate`
  and the SUB-05 fix, all 21 tests pass on Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY).
- Added new "Mi 9T Pro (MIUI / Android 11) limitation" subsection documenting the indefinite hang,
  non-effectiveness of the idle whitelist on MIUI, and the workaround (use Samsung/stock Android).

## Test Results Referenced

- Samsung Galaxy A71 SM-A715F, Android 13, ADB serial R58N849XQEY
- 21/21 tests pass, 0 failures (all 7 MainActivitySmokeTest cases now PASS)
- `testDebugUnitTestApp`: PASS
- `assembleDebugApp`: PASS

## Gate Result

GATE: PASS
STEP: docs
DOCS_UPDATED: docs/runbooks/solutions.md, docs/runbooks/android-qa.md
SUMMARY: Updated both runbooks to reflect SUB-05 resolution — corrected root cause, removed stale advice, added MIUI Android 11 limitation, confirmed Samsung whitelist workaround.
