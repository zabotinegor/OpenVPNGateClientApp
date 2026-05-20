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
- MQ-US10-001: Historical lifecycle repro flow.
- MQ-US10-002: Long-running no-internet soak with self-recovery.
- MQ-US10-003: Watchdog log verification.
- MQ-US10-004: No reboot required to restore connectivity.

## Notes
- Device focus: MIUI real device variance.
- Known MIUI caveat: `uiautomator dump` prints `theme_compatibility.xml` noise even when dump succeeds.
