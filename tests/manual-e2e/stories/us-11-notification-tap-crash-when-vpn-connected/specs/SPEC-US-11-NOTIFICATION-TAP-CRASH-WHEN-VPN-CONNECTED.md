# US-11 Manual QA Specification

Story ID: US-11
Title: Notification tap must not crash app when VPN is connected
Surface: Android mobile notification interaction while connected

## Scope
- Validate MQ-1 through MQ-4 from the approved story.
- Confirm no regression in pause, resume, and disconnect actions.
- Capture logcat evidence proving no RuntimeException propagation in notification-open path.

## Acceptance Criteria Mapping
- AC-1, AC-2: MQ-US11-001
- AC-3, AC-4: MQ-US11-002 and MQ-US11-004
- AC-5: MQ-US11-003
- AC-7: MQ-US11-004

## Preconditions
- One Android mobile device connected via ADB.
- App package installed: com.yahorzabotsin.openvpnclientgate.
- Device network available for VPN connection attempt.

## Evidence Requirements
- ADB/device info snapshot.
- Per-case command output excerpts.
- Logcat capture for notification interaction window.
- Result summary with PASS/FAIL/BLOCKED per case.

## Cases
- MQ-US11-001-notification-tap-opens-app-no-crash
- MQ-US11-002-notification-tap-repeated-stability
- MQ-US11-003-notification-action-regression-pause-resume-disconnect
- MQ-US11-004-logcat-no-runtimeexception-notification-open
