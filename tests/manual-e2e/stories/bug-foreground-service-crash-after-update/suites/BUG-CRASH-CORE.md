# BUG-CRASH-CORE — Foreground Service Crash After Update — Core Suite

## Story
`docs/userstories/BUG-foreground-service-crash-after-update.md`

## PR
PR #101 targeting `dev`

## Branch
`fix/foreground-service-crash-after-update`

## Run date
2026-06-18

## Device
Samsung Galaxy A71 SM-A715F, Android 13, ADB serial R58N849XQEY

## Build
Debug APK, commit 4ef1f31 (HEAD on fix/foreground-service-crash-after-update)

## Unit tests
OpenVpnServiceNotificationTest: 4/4 PASS (tests=4, failures=0, errors=0, skipped=0)
XML: `src/core/build/test-results/testDebugUnitTest/TEST-com.yahorzabotsin.openvpnclientgate.vpn.OpenVpnServiceNotificationTest.xml`

## Cases

| ID | Title | Result |
|----|-------|--------|
| MQ-BUG-CRASH-001 | Fresh install: ACTION_SYNC_STATUS cycle without crash | PASS |
| MQ-BUG-CRASH-002 | Normal VPN connect (no update scenario) | PASS |
| MQ-BUG-CRASH-003 | VPN disconnect and reconnect | PASS |
| MQ-BUG-CRASH-004 | ServerAutoSwitcher no regression | PASS |
| MQ-BUG-CRASH-005 | No RemoteServiceException in logcat | PASS |
| MQ-BUG-CRASH-006 | onDestroy() foreground cleanup after sync | PASS |

## Overall: PASS

## Key evidence summary
- `enterControllerForeground(stopOnFailure = false)` in `onCreate()` at line 423: confirmed present in code and prevents the 5-second foreground timer from expiring before `startForeground()` is called
- `exitControllerForeground()` in `ACTION_SYNC_STATUS` at line 703: confirmed present — sync cycle completes and service self-terminates cleanly in ~1.5s
- LEVEL_CONNECTED reached on first connect after fresh install: confirmed at 18:17:33 with trafficDelta=32935
- LEVEL_CONNECTED on reconnect: confirmed at 18:19:18 with trafficDelta=22985
- `Service destroyed and listener removed` logged on every onDestroy: confirmed in all 3 sync cycles
- Zero RemoteServiceException, zero FATAL EXCEPTION across entire session
- VPN key icon clears from status bar after sync service self-terminates (no notification leak)
