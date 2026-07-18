---
id: SUITE-SUB-06-favorites-section-framing
title: Favorites section visual framing — execution log
spec: SPEC-SUB-06-favorites-section-framing
---

## Run 2026-07-12

- Branch: feature/sub-06-favorites-section-framing
- Commit under test: 2ad24c9
- Build: debug (`assembleDebugApp`), BUILD SUCCESSFUL in 2m 14s
- Devices:
  - Phone: Samsung Galaxy A71 (SM-A715F), serial `R58N849XQEY`, versionName 1.0.4-beta.1,
    versionCode 63, lastUpdateTime 2026-07-11 23:54:03 (adb over USB)
  - TV: MIBOX4, `192.168.1.94:5555`, versionName 1.0.4-beta.1, versionCode 1, lastUpdateTime
    2026-07-12 00:00:12 (adb over Wi-Fi)

## Results

| Case | AC | Result |
|---|---|---|
| CASE-SUB06-001 | AC1 | PASS (phone, light theme) |
| CASE-SUB06-002 | AC2 | PASS (phone, light + dark theme) |
| CASE-SUB06-003 | AC3 | PASS (phone, light + dark theme) |
| CASE-SUB06-004 | AC4 | PASS (phone + TV) |
| CASE-SUB06-005 | AC5 | PASS (phone + TV) |
| CASE-SUB06-006 | AC6 | PASS (TV, dark theme default) |

No defects found. No FATAL EXCEPTION, ANR, or app-attributable exception in logcat on either
device across the full session.

One investigation note (not a defect): a stored favorite country intermittently had no pinned
section on TV due to SSE-driven backend list churn temporarily excluding it from the loaded
country list — pre-existing, documented behavior (see spec's "Known Behavior Constraints"),
reproduced and explained, not a SUB-06 regression.
