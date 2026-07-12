---
id: SPEC-SUB-07-favorites-localization
title: Favorites strings render translated on ru/pl device locales
storyId: SUB-07
area: Locale
surfaces: [android]
---

## Scope
- Story: [SUB-07-favorites-localization.md](../../../../../docs/userstories/MP-20260706-favorite-countries-servers/SUB-07-favorites-localization.md).
- Resource-only change: `favorites_section_title`, `favorites_add_action`, `favorites_remove_action`,
  `favorites_added_toast`, `favorites_removed_toast` translated in
  `src/core/src/main/res/values-ru/strings.xml` and `src/core/src/main/res/values-pl/strings.xml`.
- Behavior under test: on a device with system language set to Russian or Polish, the favorites
  section title, long-press add/remove action labels, and add/remove toast text render in the
  device language instead of falling back to English. No favorites add/remove/toggle logic is
  in scope (already covered by SUB-02/03/04/06/08).

## Acceptance Criteria Mapping
- AC1 (ru translations present, correct tone): Covered by CASE-SUB07-001, CASE-SUB07-002.
- AC2 (pl translations present, correct tone): Covered by CASE-SUB07-003, CASE-SUB07-004.
- AC3 (no duplicate/misspelled/copy-paste keys): Verified in review/quality-gate steps (resource
  diff inspection); spot-checked visually in CASE-SUB07-001..004 (no English fallback observed).
- AC4 (default values/strings.xml unchanged): Out of manual-QA scope; verified by `git diff` in
  implementation/review/quality-gate steps.
- AC5 (app builds, no missing-translation lint warnings): Covered by build/deploy phase
  (`assembleDebugApp`) preceding case execution.

## Test Data and Environment
- Branch `feature/sub-07-favorites-localization`, HEAD commit `533e834` (533e8341df7891859a3d3792d422ca1eb78ab4c5).
- Device: Samsung Galaxy A71, ADB serial `R58N849XQEY` (real device, mobile debug build).
- Package: `com.yahorzabotsin.openvpnclientgate`.
- Preconditions: at least one country and one server favorited beforehand so the pinned Favorites
  section and add/remove toasts are exercisable.

## Risks and Out of Scope
- TV surface (MIBOX4) not exercised in this run — story scope is resource-only and shared by both
  launchers; orchestrator scoped verification to a single connected real device. TV rendering is
  the same resource-lookup mechanism validated on SUB-04/SUB-05 TV runs, so risk is low.
- No functional/behavioral change to favorites add/remove/toggle logic in this story.
