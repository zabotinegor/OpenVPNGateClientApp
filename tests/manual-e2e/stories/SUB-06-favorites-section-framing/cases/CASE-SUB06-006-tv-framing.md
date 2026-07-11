---
id: CASE-SUB06-006
title: Frame renders consistently on TV (Leanback) layouts
surface: android-tv
suite: SUITE-SUB-06-favorites-section-framing
acceptance: AC6
---

## Preconditions

- Debug build of commit `2ad24c9` installed on the TV (`adb -s 192.168.1.94:5555 install -r
  tv-debug.apk`); fingerprint confirmed.
- TV app launched via `android.intent.category.LEANBACK_LAUNCHER`, `tv.SplashActivity` ->
  `tv.MainActivity` confirmed via `dumpsys window`.

## Steps

1. Navigate to the countries screen with D-pad; favorite a country present in the currently loaded
   list via D-pad long-press (`sendevent` held-key on `/dev/input/event2`, scancode 353) +
   `FavoriteActionDialog`.
2. Observe the pinned Favorites section and frame.
3. Navigate into that country's servers screen; favorite a server the same way.
4. Observe the pinned Favorites section and frame there too.

## Expected

Framing renders acceptably on the TV Leanback layout, using the same `FavoritesSectionFrameDecoration`
and layouts as mobile (core module owns both), with D-pad navigation working normally around the
framed section.

## Actual (2026-07-12, TV MIBOX4 192.168.1.94:5555, dark theme (TV default), RU locale)

PASS. Countries screen: after favoriting Australia, a white/light border (dark-theme
`colorSecondary`) enclosed the "Favorites" header + pinned "Австралия" row; the regular-list
duplicate "Австралия" row below was outside the frame. Servers-in-country screen (Australia):
after favoriting the Sydney server, the same framing enclosed the "Favorites" header + pinned
"Сидней" row, with the regular-list duplicate outside the frame — visually consistent with the
mobile phone's dark-theme rendering (same stroke weight, corner radius, and border color).
D-pad navigation (DPAD_UP/DOWN to move focus, DPAD_CENTER to select/long-press) worked normally
around the framed rows with no visual clipping or focus-highlight conflicts with the frame.

No FATAL EXCEPTION, ANR, or app-attributable exception in TV logcat during this session.
