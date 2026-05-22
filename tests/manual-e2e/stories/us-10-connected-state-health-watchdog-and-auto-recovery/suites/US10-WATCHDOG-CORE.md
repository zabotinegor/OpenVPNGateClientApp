---
id: US10-WATCHDOG-CORE
title: US-10 watchdog manual validation suite
surface: android
includes:
  - MQ-US10-001
  - MQ-US10-002
  - MQ-US10-003
  - MQ-US10-004
---

## Objective
Execute US-10 manual scenarios for false-connected detection, bounded recovery, fail-safe behavior, and pause/resume regression safety.

## Execution Order
1. Phase A deterministic baseline:
  - MQ-US10-001
  - MQ-US10-002
  - MQ-US10-003
2. Phase B induced degradation:
  - MQ-US10-004
3. Supplementary stale-stop remediation gate:
  - Verify stale `pending_stop_intent` injection and fresh-start clearing in the same run folder.

## Preconditions
- Exactly one ADB device is connected and selected for the run.
- App launches to `MainActivity` through splash using MIUI-safe readiness checks.
- Evidence folder captures per-step UI dumps/screenshots and per-phase logcat files.

## Exit Criteria
- All critical watchdog and lifecycle acceptance criteria pass.
- Any regression in pause/resume or false-connected handling is captured as a defect with evidence.
- Merge gate is blocked when stale-stop setup injection fails or stale-stop clearing is not verified after fresh start.
