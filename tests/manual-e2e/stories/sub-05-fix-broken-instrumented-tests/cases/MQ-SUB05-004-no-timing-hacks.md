# MQ-SUB05-004 — No timing hacks in test code

## Preconditions
- Source code available at `fix/sub-05-instrumented-tests` HEAD

## Steps
1. Read `src/mobile/src/androidTest/java/com/yahorzabotsin/openvpnclientgate/mobile/MainActivitySmokeTest.kt`
2. Read `src/mobile/src/androidTest/java/com/yahorzabotsin/openvpnclientgate/mobile/MainActivityUiTest.kt`
3. Search for `Thread.sleep`, `SystemClock.sleep`, or any sleep-based timing
4. Verify Espresso idling resources or lifecycle callbacks are used instead
5. Confirm `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` is absent from launch intents
6. Verify `dismissUpdatePromptIfVisible()` handles async dialog

## Expected
- Zero `Thread.sleep` or `SystemClock.sleep` calls
- No `FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK` in launch intents
- Update dialog handled via Espresso matcher, not timing

## Evidence Required
- Code review summary confirming absence of timing hacks
- Confirmation of lifecycle-aware patterns used

## Cleanup
- None required
