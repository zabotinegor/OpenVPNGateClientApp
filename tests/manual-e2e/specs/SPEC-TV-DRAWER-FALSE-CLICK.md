---
id: SPEC-TV-DRAWER-FALSE-CLICK
title: TV drawer false-click prevention specification
relatedSuite: TV-DRAWER-FALSE-CLICK-CORE
surface: android-tv
---

## Business Context
TV users can trigger an unintended connection action when pressing OK while the drawer is opening. The expected behavior is strict interaction isolation: while drawer transition is active, only drawer interactions are allowed.

## Acceptance Criteria Mapping
- AC1: During drawer opening/closing transitions, main-screen action controls are blocked.
- AC2: When drawer is open, OK applies only to drawer-focused items.
- AC3: After drawer closes, primary connection control regains interactivity and focus.
- AC4: Existing TV drawer navigation flow remains working while the guard is active.
- AC5: Manual E2E artifacts for this feature are executable against a target TV device (<TV_IP:5555>) and validated on MIBOX4.

## Evidence Model
- Instrumentation run result (:tv:connectedDebugAndroidTest) on target TV.
- Manual execution notes for TV-DRAWER-FALSE-CLICK-001.
- Optional screenshots and logs for transition moments.

## Device Baseline
- Target device: <TV_IP:5555>
- Model: MIBOX4
- Android: 9
