# SPEC-BUG-FOREGROUND-SERVICE-CRASH-AFTER-UPDATE

## Story
`docs/userstories/BUG-foreground-service-crash-after-update.md`

## PR
PR #101 targeting `dev`

## Branch
`fix/foreground-service-crash-after-update`

## Diff scope
`origin/dev..HEAD`

## Changed files (production)
- `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
  - `onCreate()`: adds `enterControllerForeground(stopOnFailure = false)` after `ensureEnginePreferences()`
  - `ACTION_SYNC_STATUS` branch: adds `exitControllerForeground()` as first call

## Changed files (test)
- `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnServiceNotificationTest.kt`
  - 4 new unit tests covering the notification/foreground lifecycle

## Test surface
Android — physical device Samsung Galaxy A71 SM-A715F Android 13 (ADB serial R58N849XQEY)

## Acceptance criteria under test
1. Fresh APK install + update cycle: first VPN connect attempt succeeds without crash
2. No regression on normal VPN connect (no update scenario)
3. No regression on VPN disconnect and reconnect
4. No regression on server switch (auto-switch via ServerAutoSwitcher)
5. Logcat shows no RemoteServiceException for OpenVpnService
6. onDestroy() logs confirm foreground state is properly cleaned up after sync

## Test cases
- `MQ-BUG-CRASH-001-fresh-install-no-crash.md`
- `MQ-BUG-CRASH-002-normal-vpn-connect.md`
- `MQ-BUG-CRASH-003-disconnect-reconnect.md`
- `MQ-BUG-CRASH-004-server-autoswitcher-no-regression.md`
- `MQ-BUG-CRASH-005-no-remoteserviceexception.md`
- `MQ-BUG-CRASH-006-ondestroy-foreground-cleanup.md`

## Suite
`BUG-CRASH-CORE.md`
