# MQ-SUB03-003: No DI-related fatal exception in logcat

## Case
Verify that Koin resolves the full DI graph (including the new `HardProbeApiClient` binding) without
throwing any `NoBeanDefFoundException`, `KoinException`, or `FATAL EXCEPTION`.

## Steps
1. After app launch (MQ-SUB03-002), capture logcat:
   ```
   adb -s R58N849XQEY logcat -d --pid <PID> | grep -iE "FATAL|NoBeanDef|KoinException|HardProbeApiClient|ProbeApi|Exception|crash"
   ```

## Expected
- Zero matching lines

## Result: PASS
- grep returned no output (zero matching lines)
- No `FATAL EXCEPTION`, `AndroidRuntime`, `NoBeanDefFoundException`, `KoinException`,
  `HardProbeApiClient`, or `ProbeApi` error lines present in logcat for PID 18259
- Executed: 2026-06-16 17:43 UTC+3
