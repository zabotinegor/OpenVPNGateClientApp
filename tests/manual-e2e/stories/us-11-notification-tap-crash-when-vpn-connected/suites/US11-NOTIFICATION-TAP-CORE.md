# US-11 Notification Tap Core Suite

Suite ID: US11-NOTIFICATION-TAP-CORE
Story ID: US-11

## Included Cases
- MQ-US11-001-notification-tap-opens-app-no-crash
- MQ-US11-002-notification-tap-repeated-stability
- MQ-US11-003-notification-action-regression-pause-resume-disconnect
- MQ-US11-004-logcat-no-runtimeexception-notification-open

## Execution Order
1. MQ-US11-001
2. MQ-US11-002
3. MQ-US11-003
4. MQ-US11-004

## Exit Criteria
- All four cases pass, or defects/blockers are documented with evidence and SDLC status updated accordingly.

## Latest Execution
- Date: 2026-05-22
- Device: e26d5c2f (Android mobile)
- Result: PASS

## Evidence Index
- Run folder: manual-qa/2026-05-22-us11-notification-tap-fix/runs/20260522-205126
- MQ-1 and MQ-2 outcome snapshot: manual-qa/2026-05-22-us11-notification-tap-fix/runs/20260522-205126/notification-results.txt
- MQ-1 and MQ-2 activity traces: manual-qa/2026-05-22-us11-notification-tap-fix/runs/20260522-205126/dumpsys-mq1.txt, manual-qa/2026-05-22-us11-notification-tap-fix/runs/20260522-205126/dumpsys-mq2-locked.txt, manual-qa/2026-05-22-us11-notification-tap-fix/runs/20260522-205126/dumpsys-mq2-recents.txt, manual-qa/2026-05-22-us11-notification-tap-fix/runs/20260522-205126/dumpsys-mq2-unlocked.txt
- MQ-3 regression suite evidence: manual-qa/2026-05-22-us11-notification-tap-fix/runs/20260522-205126/pause-harness/QA-REPORT.md
- MQ-4 logcat evidence: manual-qa/2026-05-22-us11-notification-tap-fix/runs/20260522-205126/logcat-notification-window.txt
