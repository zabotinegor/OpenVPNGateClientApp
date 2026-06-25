# MQ-SUB05-003: WorkManager periodic refresh remains active

**AC:** AC-6 (WorkManager periodic refresh not removed)

## Steps

1. With app installed, run: `adb shell dumpsys jobscheduler | grep openvpnclientgate`

## Expected

- `SystemJobService` registered for `com.yahorzabotsin.openvpnclientgate`
- Job status: RUNNABLE

## Result: PASS

`JOB #u0a803/1: com.yahorzabotsin.openvpnclientgate/androidx.work.impl.background.systemjob.SystemJobService`  
Status: RUNNABLE — WorkManager periodic refresh remains intact.
