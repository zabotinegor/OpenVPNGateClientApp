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
1. MQ-US10-001
2. MQ-US10-002
3. MQ-US10-003
4. MQ-US10-004

## Exit Criteria
- All critical watchdog and lifecycle acceptance criteria pass.
- Any regression in pause/resume or false-connected handling is captured as a defect with evidence.
