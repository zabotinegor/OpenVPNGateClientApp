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

Framing (now implemented as a filled card background via `FavoritesSectionCardDecoration` in SUB-09,
replacing the SUB-06 stroke-border approach) renders acceptably on the TV Leanback layout using
the same decoration and layouts as mobile (core module owns both), with D-pad navigation working
normally around the card section. The card includes a star icon on the "Favorites" header and a
second "All servers" header appears above the full list below the card when the Favorites section
is visible.

## Actual (2026-07-12, TV MIBOX4 192.168.1.94:5555, dark theme (TV default), RU locale)

PASS (SUB-09 implementation). Countries screen: after favoriting Australia, a filled card background 
(dark-theme `colorSurfaceVariant`) enclosed the "Favorites" header (with star icon) + pinned 
"Австралия" row with internal padding separating rows from card edges; the "Все страны" second 
header appeared below the card; the regular-list duplicate "Австралия" row was outside the card. 
Servers-in-country screen (Australia): after favoriting the Sydney server, the same card styling 
enclosed the "Favorites" header + pinned "Сидней" row, with "Все серверы" header below — 
visually consistent with mobile phone's dark-theme rendering (same corner radius, card color, 
and padding). D-pad navigation (DPAD_UP/DOWN to move focus, DPAD_CENTER to select/long-press) 
worked normally around the card section with no visual clipping or focus-highlight conflicts.

No FATAL EXCEPTION, ANR, or app-attributable exception in TV logcat during this session.

## Merge-gate re-check (2026-07-12, HEAD `130d6f9`, TV MIBOX4 192.168.1.94:5555)

PASS (SUB-09). Debug build fresh at commit `130d6f9` (tv-debug.apk `lastUpdateTime` 2026-07-12 09:33,
confirmed via `dumpsys package`, no rebuild needed). Re-verified the card-based visual treatment 
and new section header handling: card background rendering, star icon on "Favorites" header, 
"All servers" second header insertion, scroll/translation clipping, `pinnedSectionItemCount` edge case, 
and internal card padding.

**Card background and header**: with the "Favorites" header scrolled into view above the pinned
Belarus row (countries screen), the card's left/right edges hug the row card width with consistent
internal padding, maintaining alignment with the row content — confirmed via screenshot at multiple 
scroll positions. The star icon renders correctly next to "Favorites" text in both light/dark themes.

**Second header ("All servers") insertion**: scrolled to reveal the "Все серверы" header below the
card, positioned directly after the last pinned row, before the regular list items. Header renders
in the same text style as the "Favorites" header with proper spacing.

**Scroll/translation clipping**: favorited Australia + Belarus (2-row pinned block). Scrolled the 
list down via D-pad so the "Favorites" header + card scrolled fully behind the info card — the 
card's top edge was naturally clipped by the canvas with no spurious horizontal line drawn at clip 
boundary. Continued scrolling until the entire pinned block (both rows) scrolled off-screen — the 
card disappeared cleanly with zero residual coloring or clipping artifact on the subsequent 
regular-list rows (Belarus, Bulgaria, Brazil, UK, Venezuela, Vietnam all rendered as plain unframed/
uncolored rows). The "All servers" header was properly clipped and hidden when scrolled off.

D-pad navigation (`DPAD_UP`/`DPAD_DOWN` to move focus and scroll, `DPAD_CENTER` to select,
held-key `sendevent` on `/dev/input/event3` scancode 353 for long-press) worked normally around
the card section and "All servers" header at every scroll position tested, with no visual clipping 
or focus-highlight conflicts.

Full-session logcat scan (`adb logcat -d` plus `-b crash`): 0 hits for FATAL EXCEPTION, ANR,
WindowLeaked, or BadTokenException; crash log buffer empty. `favorites_prefs.xml` restored to
pre-test baseline (`BY` country, server `20838`).
