# MQ-BUG-CRASH-006 — onDestroy() foreground cleanup after sync

## AC
AC-6: onDestroy() logs confirm foreground state is properly cleaned up after sync

## Setup
Fresh launch (force-stop -> logcat -c -> am start)

## Steps
1. Force-stop app
2. Clear logcat
3. Launch SplashActivity
4. Wait 20s for full sync cycle
5. Observe logcat for full lifecycle: Service created -> ACTION_SYNC_STATUS -> One-shot sync complete -> Service destroyed

## Expected
- `One-shot status sync complete; stopping controller service` logged
- `Service destroyed and listener removed` logged (onDestroy called)
- No RemoteServiceException
- No stuck foreground notification after service destroys

## Result (2026-06-18, R58N849XQEY)
PASS

## Evidence
```
18:21:01.127  Service created
18:21:01.205  ACTION_SYNC_STATUS
18:21:01.711  Status source -> AIDL (status service connected)
18:21:01.717  One-shot initial state synced from AIDL snapshot
18:21:02.723  One-shot status sync complete; stopping controller service
18:21:02.748  Service destroyed and listener removed

Foreground state: exitControllerForeground() called at onCreate's ACTION_SYNC_STATUS
branch (confirmed by code inspection at OpenVpnService.kt:703) and at onDestroy()
(confirmed at OpenVpnService.kt:962). No foreground notification persists after service
destruction — verified by absence of VPN key icon in status bar after sync completes.
```

## Code references
- `onCreate()` line ~423: `enterControllerForeground(stopOnFailure = false)`
- `ACTION_SYNC_STATUS` line ~703: `exitControllerForeground()` (first call)
- `onDestroy()` line ~962: `exitControllerForeground()` (safety net)
