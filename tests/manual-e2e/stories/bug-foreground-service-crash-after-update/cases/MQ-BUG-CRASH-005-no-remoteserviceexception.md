# MQ-BUG-CRASH-005 — No RemoteServiceException in logcat

## AC
AC-5: Logcat shows no RemoteServiceException for OpenVpnService

## Setup
Full QA session executed (fresh install, connect, disconnect, reconnect)

## Steps
1. After all QA cases executed, run:
   `adb -s R58N849XQEY logcat -d 2>&1 | grep -c "RemoteServiceException"`
   `adb -s R58N849XQEY logcat -d 2>&1 | grep -c "FATAL EXCEPTION"`

## Expected
Both counts = 0

## Result (2026-06-18, R58N849XQEY)
PASS

## Evidence
```
RemoteServiceException count in current session: 0
FATAL EXCEPTION count in current session: 0
Session covered:
  - Fresh uninstall+install (simulated update)
  - ACTION_SYNC_STATUS cycle x2
  - ACTION_START (first VPN connect) -> LEVEL_CONNECTED
  - ACTION_STOP -> LEVEL_NOTCONNECTED
  - ACTION_START (reconnect) -> LEVEL_CONNECTED
  - ACTION_STOP -> LEVEL_NOTCONNECTED
```
