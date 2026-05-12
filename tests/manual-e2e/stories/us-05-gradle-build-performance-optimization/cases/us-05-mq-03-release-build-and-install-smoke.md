# MQ-03: Release Build and Install Smoke Test

**Test Case ID:** us-05-mq-03  
**Objective:** Validate that release build remains stable and APK can be installed and launched.

## Preconditions
- MQ-01 and MQ-02 completed successfully
- Required Gradle properties available:
  - `-PappVersionName` (e.g., "11.0")
  - `-PappVersionCode` (e.g., "110")
  - `-PPRIMARY_SERVERS_URL`, `-PFALLBACK_SERVERS_URL`, `-PPRIMARY_SERVERS_V2_URL`
- Android device/emulator connected via ADB (if install test required)

## Test Steps

1. **Execute release build**
   - Command: `.\gradlew assembleReleaseApp -PappVersionName=11.0 -PappVersionCode=110 -PPRIMARY_SERVERS_URL=<url> -PFALLBACK_SERVERS_URL=<url> -PPRIMARY_SERVERS_V2_URL=<url>`
   - Capture:
     - Build output and duration
     - APK path (typically `src/mobile/build/outputs/apk/release/mobile-release.apk`)
     - BUILD SUCCESSFUL confirmation
   - Expected: BUILD SUCCESSFUL without errors

2. **Verify APK output**
   - Confirm APK files exist at expected paths
   - Record file sizes and checksums
   - Expected:
     - mobile-release.apk exists and is valid
     - tv-release.apk exists and is valid (if applicable)

3. **Install smoke test (if device available)**
   - Command: `adb install -r <apk-path>`
   - Verify app appears in package list: `adb shell pm list packages | findstr openvpn`
   - Expected: Package successfully installed

4. **Launch app smoke test (if device available)**
   - Command: `adb shell am start -n com.yahorzabotsin.openvpnclientgate.mobile/com.yahorzabotsin.openvpnclientgate.core.ui.splash.SplashActivityCore`
   - Wait 5 seconds
   - Verify no crash: `adb logcat | findstr "FATAL|EXCEPTION|crash"` (should be empty)
   - Expected: App launches without immediate crashes

## Acceptance Criteria
- **AC-6.2**: Release build succeeds with all required -P properties ✓
- **AC-6.3**: APK assembly succeeds ✓
- **AC-6.4**: Module wiring and integration remain intact ✓

## Expected Evidence Output
```
RELEASE BUILD SUCCESSFUL:
- Build time: [X] seconds
- Mobile APK: src/mobile/build/outputs/apk/release/mobile-release.apk ([size] bytes)
- TV APK: src/tv/build/outputs/apk/release/tv-release.apk ([size] bytes)
- Install: SUCCESS
- Launch: SUCCESS (no crashes in logcat)
```

## Pass Condition
- BUILD SUCCESSFUL for release
- APK files present and valid
- Install succeeds (if device available)
- App launches without crash (if device available)

## Failure Condition
- BUILD FAILED on release
- APK missing or invalid
- Install fails
- App crashes on launch
