# MQ-SUB03-001: APK installs cleanly

## Case
Verify the debug APK from branch `feature/SUB-03-hardprobe-retrofit-api-client` installs on the target device without error.

## Steps
1. Locate APK: `src/mobile/build/outputs/apk/debug/mobile-debug.apk`
2. Run: `adb -s R58N849XQEY install -r <path-to-apk>`
3. Observe exit message

## Expected
- Output: `Performing Streamed Install` followed by `Success`
- Exit code 0

## Result: PASS
- Command output: `Performing Streamed Install / Success`
- APK timestamp: 2026-06-16 16:42:59 (132,861,266 bytes)
- Executed: 2026-06-16 17:41 UTC+3
