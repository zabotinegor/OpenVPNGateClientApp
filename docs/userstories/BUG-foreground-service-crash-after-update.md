---
title: "BUG-FIX: App crashes on first VPN connection attempt after APK update"
description: |
  As a user, I want the app to survive the first VPN connection attempt after installing an update
  without crashing, so that I can connect to the VPN reliably immediately after upgrading.

## Context
- One-time crash on first VPN connect attempt after update, with full recovery on the second attempt.
- Crash type: `android.app.RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground()`
- Affected service: `com.yahorzabotsin.openvpnclientgate/.vpn.OpenVpnService`
- Source file: `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
- Evidence: `D:\Apps\OpenVPNClient\OpenVPNClientClientReports\crash after update\logcat_20260617_135358\logcat_20260617_135358.txt`
- Crash process 5758, build 95 (first launch after update from build 94)
- Build 95 process 6960 (restart after crash) — works fine

## Reproduction Steps
1. Install APK update (fresh ART compilation means `onCreate()` is measurably slower on first run)
2. Launch app — observe splash + server preload (~23 s)
3. Tap "Server" to browse and select a server
4. Return to main screen (`MainActivityCore.onStart()` triggers `VpnManager.syncStatus()` → `startService()`)
5. Wait ≤ 1 second, then tap the Connect button
6. **App crashes** with `RemoteServiceException`

## Expected Behavior
VPN connection starts normally. No crash.

## Actual Behavior
App crashes once. Second attempt (after auto-restart) works fine.

## Root Cause
Race condition between `stopAfterOneShotSyncRunnable` (which calls `stopSelf()`) and
`VpnManager.startVpn()` calling `ContextCompat.startForegroundService()`.

**Exact sequence:**
1. `MainActivityCore.onStart()` → `VpnManager.syncStatus()` → `context.startService()` creates
   `OpenVpnService` (no Android 5-second timer started — this is a regular `startService()`)
2. AIDL connects (~200 ms); `onOneShotInitialStateSynced()` → `stopAfterOneShotSyncRunnable`
   posted with 1000 ms delay
3. User taps Connect within ≤ 1 second of returning to main screen →
   `VpnManager.startVpn()` → `ContextCompat.startForegroundService()` — **5-second timer starts in ActivityManagerService**
4. `stopAfterOneShotSyncRunnable` fires (1 s after AIDL connect) → `stopSelf()` called
5. `stopSelf()` is processed by Android's ActivityManagerService while `ACTION_START` delivery
   is in flight; the service may be briefly in a dead/restarting state
6. Android's 5-second foreground timer fires before `startForeground()` is registered to
   the timer slot → `RemoteServiceException` crash

**Why only after update:** ART recompiles class files at first launch after an APK update.
`onCreate()` and surrounding class initialization runs measurably slower, widening the race
window between `stopSelf()` and `startForeground()`. Second launch uses already-compiled DEX,
`onCreate()` is faster, and the window no longer triggers.

## Fix Approach
Call `enterControllerForeground()` (which calls `startForeground()`) immediately inside
`OpenVpnService.onCreate()`, after notification channels are created
(`ensureEngineNotificationChannels()`). This satisfies Android's 5-second requirement within
`onCreate()` itself, before any intent is delivered. The race window is eliminated entirely.

For `ACTION_SYNC_STATUS` (a background status sync, not a VPN start), call
`exitControllerForeground()` immediately in `onStartCommand()` to remove the transient
foreground notification — sync does not need a persistent user-visible notification.

**State machine stays balanced**: `onDestroy()` already calls `exitControllerForeground()` as
a safety net. For `ACTION_START`, `enterControllerForeground()` in `onStartCommand()` is a
no-op (`controllerForegroundActive` guard). All other actions that call `exitControllerForeground()`
(`ACTION_STOP`, `ACTION_STOP_IF_IDLE`) remain unchanged.

## Regression Risk Areas
1. **VPN connection flow** — `ACTION_START`: `enterControllerForeground()` in `onCreate()` runs
   before intent delivery; second call in `onStartCommand()` is idempotent. No behavior change.
2. **Sync-status flow** — `ACTION_SYNC_STATUS`: brief foreground notification appears and is
   immediately removed via `exitControllerForeground()`. Notification should be invisible to users
   (sub-millisecond window before `onStartCommand()` executes).
3. **Stop flow** — `ACTION_STOP`, `ACTION_STOP_IF_IDLE`: both already call `exitControllerForeground()`;
   no change.
4. **`onDestroy()` safety net** — `exitControllerForeground()` is already called; no change.
5. **`ServerAutoSwitcher` reconnect** — starts via `ACTION_START`; same as #1.

## Acceptance Criteria
- [ ] Fresh APK install + update cycle: first VPN connect attempt succeeds without crash
- [ ] No regression on normal VPN connect (no update scenario)
- [ ] No regression on VPN disconnect and reconnect
- [ ] No regression on server switch (auto-switch via `ServerAutoSwitcher`)
- [ ] Logcat shows no `RemoteServiceException` for `OpenVpnService`
- [ ] `onDestroy()` logs confirm foreground state is properly cleaned up after sync

## Out of Scope
- Changes to `stopAfterOneShotSyncRunnable` delay (`ONE_SHOT_STOP_DELAY_MS`)
- Changes to `VpnManager.syncStatus()` call site in `MainActivityCore.onStart()`
- Engine submodule changes

## Implementation Handoff
- Branch: `fix/foreground-service-crash-after-update`
- Story path: `docs/userstories/BUG-foreground-service-crash-after-update.md`
- File to change: `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/vpn/OpenVpnService.kt`
  - `onCreate()` line ~418: add `enterControllerForeground()` after `ensureEnginePreferences()`
  - `onStartCommand()` `ACTION_SYNC_STATUS` branch line ~697: add `exitControllerForeground()` as first call

## Post-Implementation Status (2026-06-18)
- Status: **PASSED**
- Validated device: Samsung Galaxy A71 SM-A715F Android 13 (ADB serial R58N849XQEY)
- Build: debug, commit e9ad3ab
- Manual QA date: 2026-06-18
- Acceptance summary:
  - [x] First VPN connect attempt after APK update succeeds without `RemoteServiceException` crash
  - [x] No regression on normal VPN connect (no-update scenario)
  - [x] No regression on VPN disconnect and reconnect
  - [x] No regression on server switch (auto-switch via `ServerAutoSwitcher`)
  - [x] Logcat shows no `RemoteServiceException` for `OpenVpnService`
  - [x] `onDestroy()` logs confirm foreground state is properly cleaned up after sync
