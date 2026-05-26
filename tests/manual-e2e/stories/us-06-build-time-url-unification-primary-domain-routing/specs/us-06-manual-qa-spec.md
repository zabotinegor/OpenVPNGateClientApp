---
id: US-06-MQ-SPEC
title: US-06 build-time URL unification and primary-domain routing manual QA
storyId: US-06
area: Android
surfaces: [android, android-tv]
---

## Scope
- Validate startup/source-switch/refresh user-visible behavior for DEFAULT_V2, LEGACY, VPNGATE, CUSTOM.
- Validate fallback chain behavior evidence for trusted sources.
- Validate metadata host independence for What's New and Get Update.
- Validate mobile and TV parity for shared flows.

## Acceptance Criteria Mapping
- AC-1.1..AC-1.4: Covered by us-06-mq-01-mobile-shared-flows.md
- AC-2.1..AC-2.4: Covered by us-06-mq-01-mobile-shared-flows.md
- AC-4.1..AC-4.5: Covered by us-06-mq-03-mobile-server-source-fetch-regression.md
- AC-5.1..AC-5.5: Covered by us-06-mq-01-mobile-shared-flows.md
- AC-6 (TV parity slice): Covered by us-06-mq-02-tv-shared-flows.md

## Test Data and Environment
- Device: Mi 9 SE (adb id: e26d5c2f).
- TV target: required Leanback-capable device (not available in this run).
- Build under validation: commit d9004f308b91061ddc3e00bc39b65d065a8608e7.
- Evidence paths:
  - manual-qa/2026-05-13-us06-build-time-url-unification/
  - manual-qa/2026-05-13-us06-server-source-regression/

## Risks and Out of Scope
- **PIVOTED TO MANUAL E2E:** connectedDebugAndroidTest runner stalls at Gradle configuration phase after 4 retries with cache cleanup. Instance-level pattern confirms infrastructure blocker unrelated to product code. Switched to manual E2E smoke for AC validation.
