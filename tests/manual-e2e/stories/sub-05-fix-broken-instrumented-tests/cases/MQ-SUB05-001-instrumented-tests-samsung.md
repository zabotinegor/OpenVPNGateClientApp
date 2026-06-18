# MQ-SUB05-001 — Instrumented tests pass on Samsung A71 (fresh install)

## Preconditions
- APK built from `fix/sub-05-instrumented-tests` HEAD
- Samsung Galaxy A71 SM-A715F Android 13 (R58N849XQEY) connected via ADB
- App is NOT installed (fresh install scenario)

## Steps
1. Uninstall existing app:
   ```
   adb -s R58N849XQEY shell pm uninstall --user 0 com.yahorzabotsin.openvpnclientgate
   ```
2. Build and install debug APK and test APK via Gradle:
   ```
   cd src && ./gradlew connectedDebugAndroidTestApp -Pandroid.testInstrumentationRunnerArguments.class=com.yahorzabotsin.openvpnclientgate.mobile.MainActivitySmokeTest
   ```
3. Capture test results from XML output
4. Check for `NoActivityResumedException` in test output
5. Verify all 7 cases pass

## Expected
- All 7 `MainActivitySmokeTest` cases pass (0 failures, 0 errors)
- No `NoActivityResumedException` in output
- Test APK installs and runs successfully

## Evidence Required
- Gradle test output (pass/fail counts)
- Test result XML summary
- Logcat for any runtime exceptions during test execution

## Cleanup
- App remains installed for subsequent test runs
