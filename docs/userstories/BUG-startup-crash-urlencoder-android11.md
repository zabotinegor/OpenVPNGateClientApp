---
title: "Fix: Startup crash on Android 11+ due to URLEncoder.encode incompatibility"
description: |
  As a user, I want the app to launch and work on all supported Android versions (API 24+), including Android 11, 12, 13, 14+, without crashing due to Java API incompatibility, so that the VPN client is reliable and available for all users.

## Context
- Confirmed crash on startup on Android 11 (API 30) and potentially higher, caused by NoSuchMethodError: URLEncoder.encode(String, Charset) is not available in Android's Java runtime.
- Source: src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/AppConstants.kt (lines 218, 221).
- Evidence: tests/manual-e2e/stories/bug-startup-crash-urlencoder-android11/cases/logcat-20260513.txt, tests/manual-e2e/stories/bug-startup-crash-urlencoder-android11/cases/bug-startup-crash-urlencoder-android11-mq-03-logcat-analysis.md.

## Acceptance Criteria
- [ ] App launches and works on Android 11, 12, 13, 14+ (API 30, 31, 32, 33+) and all supported API levels (minSdk 24+).
- [ ] No crash on startup or during URL encoding on any supported Android version.
- [ ] URL encoding logic is compatible with all supported Android API levels and future-proofs against further Java API changes.
- [ ] Automated and manual tests cover at least Android 11 and the latest available API version.
- [ ] Manual QA evidence: app launches and main screen is shown on Android 11+ device/emulator.

## Out of Scope
- Refactoring unrelated to URL encoding or startup flow.
- Support for Android versions below minSdk 24.

## Risks & Assumptions
- Future Android versions may further restrict or change java.net APIs; solution must be robust to such changes.
- Some test devices/emulators may not be available for all API levels; at least one real or emulated device per major version must be tested.

## Validation
- Confirmed crash and root cause via logcat and code review.
- Manual QA and log evidence attached.

## Implementation Handoff
- Branch: feature/bug-startup-crash-urlencoder-android11
- Story path: docs/userstories/BUG-startup-crash-urlencoder-android11.md
- After implementation, validate on Android 11+ and latest API, then proceed to review.

## Post-Implementation Status (2026-05-13)
- SDLC progression: implementation ready, review passed, quality gate passed, manual QA passed.
- Validated device: Xiaomi Mi 9T Pro (Android 11, API 30), debug build from commit 26fb288.
- Acceptance summary:
  - [x] Startup no longer crashes on Android 11 during URL encoding initialization.
  - [x] URL encoding uses Android-compatible `URLEncoder.encode(String, String)` signature.
  - [x] App passes splash and reaches main screen on validated Android 11 device.
  - [x] Log validation confirms no `URLEncoder`/`StandardCharsets`/`NoSuchMethodError` startup failures.
- Evidence paths:
  - tests/manual-e2e/stories/bug-startup-crash-urlencoder-android11/specs/bug-startup-crash-urlencoder-android11-mq-spec.md
  - tests/manual-e2e/stories/bug-startup-crash-urlencoder-android11/suites/bug-startup-crash-urlencoder-android11-suite-full.md
  - tests/manual-e2e/stories/bug-startup-crash-urlencoder-android11/cases/bug-startup-crash-urlencoder-android11-mq-01-android-11-app-launch.md
  - tests/manual-e2e/stories/bug-startup-crash-urlencoder-android11/cases/bug-startup-crash-urlencoder-android11-mq-03-logcat-analysis.md
  - tests/manual-e2e/stories/bug-startup-crash-urlencoder-android11/cases/logcat-20260513.txt
  - tests/manual-e2e/stories/bug-startup-crash-urlencoder-android11/cases/screenshot-app-main.png

---

# Fix: Startup crash on Android 11+ due to URLEncoder.encode incompatibility

## Problem
App crashes on startup on Android 11+ due to use of URLEncoder.encode(String, Charset), which is not available in Android's Java runtime. This prevents users from launching the app on affected devices.

## Solution
- Replace or polyfill the URL encoding logic to ensure compatibility with all supported Android API levels (24+), including Android 11, 12, 13, 14+.
- Add/adjust tests to cover encoding on all supported versions.
- Validate fix on at least Android 11 and the latest available API.

## Acceptance Criteria
- See above.

## Evidence
- See above.

