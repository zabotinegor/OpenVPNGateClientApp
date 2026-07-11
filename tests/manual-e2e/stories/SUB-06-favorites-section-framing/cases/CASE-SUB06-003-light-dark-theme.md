---
id: CASE-SUB06-003
title: Frame color is theme-correct in both light and dark mode
surface: android-mobile
suite: SUITE-SUB-06-favorites-section-framing
acceptance: AC3
---

## Preconditions

- At least one favorite country and one favorite server present.
- Device theme togglable via `adb shell cmd uimode night yes|no`.

## Steps

1. With the countries screen and servers-in-country screen each showing a framed pinned section
   in light theme, capture a screenshot of each.
2. Switch to dark theme (`adb shell cmd uimode night yes`).
3. Re-open the same screens and capture screenshots again.

## Expected

The frame uses `?attr/colorSecondary` (resolved via `values`/`values-night`), so it should look
like a deliberate, theme-consistent accent border in both modes — not a hardcoded color that
clashes with either theme's background/surface colors.

## Actual (2026-07-12, phone R58N849XQEY, RU locale)

PASS. Light theme: dark blue-gray border on white cards. Dark theme
(`cmd uimode night yes`): light gray/white border on the dark surface — visually intentional and
legible in both modes, matching the `colorSecondary` intent described in the story. Verified on
both the countries screen and the servers-in-country screen.
