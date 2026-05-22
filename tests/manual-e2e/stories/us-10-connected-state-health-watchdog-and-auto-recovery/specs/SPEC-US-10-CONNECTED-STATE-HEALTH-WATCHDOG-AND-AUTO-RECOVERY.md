---
id: SPEC-US-10-CONNECTED-STATE-HEALTH-WATCHDOG-AND-AUTO-RECOVERY
title: Connected-state watchdog and bounded auto-recovery
surface: android
relatedSuite: US10-WATCHDOG-CORE
---

## Behavior Under Test
Validate runtime watchdog behavior that detects false-connected data-path degradation and performs bounded auto-recovery without reboot.

## Acceptance Criteria Mapping
- AC-1: Periodic connected-state health check combines traffic delta and trusted-endpoint probe.
- AC-2: Sustained unhealthy window crosses threshold and triggers recovery.
- AC-3: Recovery uses existing safe reconnect path without app restart/device reboot.
- AC-4: Cooldown/debounce prevents reconnect storms.
- AC-5: Recovery success resets watchdog counters.
- AC-6: Retry exhaustion leads to deterministic non-connected/fail-safe state.
- AC-7: Logs include watchdog decision context without sensitive data.
- AC-8: Pause/resume UX contract remains regression-safe.
- AC-9: Runtime scenarios align with expected regression coverage.

## Case Set
- MQ-US10-001: Historical lifecycle repro flow (control-state stability).
- MQ-US10-002: Connected foreground operability baseline during watchdog observation.
- MQ-US10-003: Watchdog telemetry verification from runtime logs.
- MQ-US10-004: No reboot required for recovery after induced degraded state.

## Execution Model (Reorganized)
- Phase A (deterministic baseline): run MQ-US10-001, MQ-US10-002, MQ-US10-003 without forcing network degradation.
- Phase B (induced degradation): run MQ-US10-004 with explicit degradation trigger and evidence capture.
- Supplementary stale-stop gate: verify stale `pending_stop_intent` remediation in the same run folder as a merge blocker.

## Evidence Requirements
- Every case must attach UI evidence (`*.xml` plus `*.png`) and `logcat` excerpts from the same run window.
- If a setup step fails (for example stale payload write), mark as setup failure and do not report app-behavior PASS/FAIL for that step.
- Historical pass artifacts remain reference-only; merge decisions must use latest run artifacts.

## Notes
- Device focus: MIUI real device variance.
- Known MIUI caveat: `uiautomator dump` prints `theme_compatibility.xml` noise even when dump succeeds.
