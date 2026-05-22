# US-11 - Notification Tap Must Not Crash App When VPN Is Connected

## User story

As an OpenVPN client user,
I want tapping the active VPN notification to open or foreground the app safely,
so that I can return to the app without a crash while connected.

## Background

Manual QA bug-intake and attached logcat evidence report a reproducible crash when the user taps the connected VPN notification.

Expected behavior:
- Tapping the connected VPN notification should bring the app to foreground, or launch it, without terminating the process.

Actual behavior:
- The app crashes after notification tap while VPN is connected.
- Crash path contains RuntimeException while starting OpenVPNService with action de.blinkt.openvpn.OPEN_VPN_APP, with NullPointerException around Intent.getComponent in framework task-foreground handling.
- Remote stack includes BackgroundActivityStartController aborting background activity start during AppTask.moveToFront path.

Reproduction steps:
1. Connect VPN successfully and wait until connected state is visible.
2. Leave app to background.
3. Tap the persistent VPN notification.
4. Observe app crash instead of safe foreground/launch behavior.

Severity:
- High (core connected-state interaction causes process crash and disrupts VPN session UX).

Suspected affected area:
- Engine notification click flow and task foregrounding path in OpenVPN service.

Impacted surfaces:
- Android mobile notification interaction while VPN is connected.
- Foreground/background transition path.
- Shared engine integration path used by mobile and TV launchers.

Retained artifacts:
- Attached Manual QA logcat artifact with crash stack trace for the repro session.
- Repository code evidence in:
  - [src/external/OpenVPNEngine/main/src/main/java/de/blinkt/openvpn/core/OpenVPNService.java](src/external/OpenVPNEngine/main/src/main/java/de/blinkt/openvpn/core/OpenVPNService.java)
  - [src/external/OpenVPNEngine/main/src/test/java/de/blinkt/openvpn/core/OpenVPNServiceNotificationSyncTest.kt](src/external/OpenVPNEngine/main/src/test/java/de/blinkt/openvpn/core/OpenVPNServiceNotificationSyncTest.kt)

## Acceptance criteria

| ID | Criterion |
| --- | --- |
| AC-1 | Tapping the connected VPN notification never crashes the app process. |
| AC-2 | Notification tap opens app UI through a user-initiated safe path that is compatible with modern Android background activity start policies. |
| AC-3 | If foregrounding an existing task is denied or fails, fallback behavior is handled safely without uncaught exceptions. |
| AC-4 | OpenVPN service path for notification action handling is exception-safe and cannot propagate RuntimeException from task foreground operations. |
| AC-5 | Existing disconnect, pause, and resume notification actions remain unchanged in user-visible behavior. |
| AC-6 | Regression tests cover notification tap behavior for both normal launch and task-foreground fallback/failure cases. |
| AC-7 | Logs record notification-open decision path and guarded failure reasons at info or warning level without leaking sensitive data. |

## Out of scope

- Broad refactor of OpenVPN engine lifecycle not required for notification-open crash fix.
- Changes to backend APIs, server list sync, or connection watchdog behavior.
- Redesign of notification UI content.

## Risks and open questions

| ID | Risk or question | Current handling |
| --- | --- | --- |
| R-1 | OEM-specific Android activity-start policies can differ. | Use explicit user-initiated Activity PendingIntent path and defensive service fallback guards. |
| R-2 | Service-based legacy notification-open path may still be triggered from older notifications. | Keep handler but wrap task foreground and launch calls with robust exception handling. |
| R-3 | Incorrect PendingIntent mutability or flags can regress behavior on newer SDKs. | Preserve immutable/update-current security posture and validate on current target SDK behavior. |
| R-4 | TV launcher/task behavior may differ from phone launcher behavior. | Include TV sanity check in manual QA notes when Leanback target is available. |

## Implementation notes

Likely affected areas:
- [src/external/OpenVPNEngine/main/src/main/java/de/blinkt/openvpn/core/OpenVPNService.java](src/external/OpenVPNEngine/main/src/main/java/de/blinkt/openvpn/core/OpenVPNService.java)
- [src/external/OpenVPNEngine/main/src/test/java/de/blinkt/openvpn/core/OpenVPNServiceNotificationSyncTest.kt](src/external/OpenVPNEngine/main/src/test/java/de/blinkt/openvpn/core/OpenVPNServiceNotificationSyncTest.kt)

Guidance:
- Prefer notification content PendingIntent that opens launcher activity directly as a user action.
- Keep OPEN_VPN_APP service action path crash-safe for backward compatibility.
- Guard AppTask.moveToFront and startActivity calls against platform and policy failures.
- Do not change connection-state business logic unrelated to notification-open path.

## Test scenarios

Automated tests:

| ID | Scenario |
| --- | --- |
| TS-1 | Notification content PendingIntent targets activity-launch path and not crash-prone service-only foreground shortcut. |
| TS-2 | OPEN_VPN_APP handler launches app when no existing task is available. |
| TS-3 | Simulated task-foreground failure does not crash service and falls back safely. |
| TS-4 | Disconnect notification action behavior remains unchanged. |

Manual QA focus:

| ID | Scenario |
| --- | --- |
| MQ-1 | Connect VPN, background app, tap notification, verify app opens without crash. |
| MQ-2 | Repeat notification tap multiple times across locked/unlocked and recent-app states, verify stability. |
| MQ-3 | Validate no regression in pause/resume/disconnect notification actions. |
| MQ-4 | Capture logcat confirming no RuntimeException from OpenVPNService notification-open path. |

## Definition of done

- AC-1 through AC-7 implemented and validated.
- Notification tap crash no longer reproduces in targeted manual QA scenario.
- Regression tests for notification-open behavior are added or updated and passing.
- No regressions observed in pause, resume, and disconnect notification actions.
