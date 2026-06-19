# MQ-SUB05-002 — Instrumented tests pass on Xiaomi Mi 9T Pro

## Preconditions
- APK built from `fix/sub-05-instrumented-tests` HEAD
- Xiaomi Mi 9T Pro Android 11 (b6e8f6bd) connected via ADB
- App installed on device

## Steps
1. Install debug APK on Xiaomi device:
   ```
   adb -s b6e8f6bd install -r src/mobile/build/outputs/apk/debug/mobile-debug.apk
   ```
2. Run instrumented tests:
   ```
   cd src && ./gradlew connectedDebugAndroidTestApp -Pandroid.testInstrumentationRunnerArguments.class=com.yahorzabotsin.openvpnclientgate.mobile.MainActivitySmokeTest
   ```
   Or via ADB directly:
   ```
   adb -s b6e8f6bd shell am instrument -w -e class com.yahorzabotsin.openvpnclientgate.mobile.MainActivitySmokeTest com.yahorzabotsin.openvpnclientgate.test/androidx.test.runner.AndroidJUnitRunner
   ```
3. Capture test results
4. Check for `NoActivityResumedException`

## Expected
- All 7 `MainActivitySmokeTest` cases pass on MIUI
- No `NoActivityResumedException`

## Evidence Required
- Test result summary
- Logcat for runtime exceptions

## Cleanup
- App remains installed
