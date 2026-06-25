# MQ-SUB05-004: Zero FATAL exceptions after cold launch

**AC:** General stability

## Steps

1. Launch app from cold state
2. Run: `adb logcat -d | grep "FATAL EXCEPTION\|NoBeanDefFoundException\|KoinException"`

## Expected

No output — zero fatal exceptions.

## Result: PASS

No FATAL EXCEPTION, NoBeanDefFoundException, or KoinException found in logcat after cold launch on R58N849XQEY.
