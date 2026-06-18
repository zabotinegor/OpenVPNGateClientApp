# MQ-BUG-CRASH-001 — Fresh install: ACTION_SYNC_STATUS cycle without crash

## AC
AC-1: Fresh APK install + update cycle: first VPN connect attempt succeeds without crash

## Setup
1. Uninstall app: `adb -s R58N849XQEY uninstall com.yahorzabotsin.openvpnclientgate`
2. Install fresh: `adb -s R58N849XQEY install mobile-debug.apk`
3. Clear logcat: `adb -s R58N849XQEY logcat -c`
4. Launch: `adb -s R58N849XQEY shell am start -n com.yahorzabotsin.openvpnclientgate/.mobile.SplashActivity`

## Steps
1. Wait for SplashActivity -> MainActivity transition (~3s)
2. Wait 15s for splash preload to complete

## Expected
- Service created log present
- ACTION_SYNC_STATUS logged
- Service destroyed and listener removed logged
- Zero RemoteServiceException in logcat

## Result (2026-06-18, R58N849XQEY)
PASS

## Evidence
```
18:10:52.324  Service created
18:10:52.347  ACTION_SYNC_STATUS
18:10:52.916  Status source -> AIDL (status service connected)
18:10:52.924  One-shot initial state synced from AIDL snapshot
18:10:53.931  One-shot status sync complete; stopping controller service
18:10:53.959  Service destroyed and listener removed
RemoteServiceException count: 0
FATAL EXCEPTION count: 0
```
