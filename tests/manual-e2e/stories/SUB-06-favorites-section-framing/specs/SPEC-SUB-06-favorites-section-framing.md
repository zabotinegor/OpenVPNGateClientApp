---
id: SPEC-SUB-06-favorites-section-framing
title: Favorites section visual framing manual coverage specification (phone + TV)
relatedSuite: SUITE-SUB-06-favorites-section-framing
surface: android-mobile, android-tv
story: docs/userstories/MP-20260706-favorite-countries-servers/SUB-06-favorites-section-framing.md
---

## Business Context

SUB-06 originally added a visual border/frame (`FavoritesSectionFrameDecoration`, a canvas-only
`RecyclerView.ItemDecoration`) around the pinned "Favorites" section on the countries screen
(`ServerListActivity`) and the servers-in-country screen (`CountryServersActivity`).

**Note:** SUB-06 has been superseded by SUB-09, which replaces the thin stroke-border framing with
a filled card background (`FavoritesSectionCardDecoration`), adds a star icon to the "Favorites"
header, and adds a second section header ("All countries"/"All servers") above the full list when
the Favorites section is visible.

This is a purely visual change — no click/long-press/navigation logic was touched. For current
feature details and implementation, see [src/docs/favorites-ui-patterns.md](../../../../../src/docs/favorites-ui-patterns.md).

## Acceptance Criteria Mapping

- AC1: Pinned Favorites section on the countries screen is visually enclosed by a border/frame
  distinguishing it from the regular list below. -> CASE-SUB06-001
- AC2: Same framing treatment on the servers-in-country screen. -> CASE-SUB06-002
- AC3: Framing renders correctly in both light and dark theme using `?attr/colorSecondary`.
  -> CASE-SUB06-003
- AC4: Framing (and the whole Favorites section) disappears when there are no favorites, and
  reappears when a favorite is re-added. -> CASE-SUB06-004
- AC5: No regression to tap navigation or long-press favorite actions (PopupMenu on mobile,
  FavoriteActionDialog on TV) on pinned or regular rows. -> CASE-SUB06-005
- AC6: Framing renders acceptably on TV (Leanback) layouts, consistent with mobile. -> CASE-SUB06-006

## Evidence Model

- Screenshots (`adb exec-out screencap` / `screencap -p` + pull) of the countries and
  servers-in-country screens with the pinned Favorites section visible, in both light and dark
  theme, on both devices.
- `favorites_prefs.xml` dumps (`run-as com.yahorzabotsin.openvpnclientgate cat
  shared_prefs/favorites_prefs.xml`) before/after each add/remove to correlate stored state with
  the rendered frame.
- uiautomator dumps confirming the frame encloses exactly the `section_header_title` node plus the
  pinned row(s), not the regular list below.
- Full-session logcat scan on both devices: no FATAL EXCEPTION, ANR, or app-attributable exception.
- Installed build fingerprint (versionName/versionCode/lastUpdateTime) matching the commit under
  test on both devices.

## Device Baseline

- Phone: Samsung Galaxy A71 (SM-A715F), serial `R58N849XQEY`, adb over USB. Launcher module
  `src/mobile`; launch via `android.intent.category.LAUNCHER`. RU locale.
- TV: MIBOX4, adb over Wi-Fi at `192.168.1.94:5555`. Launcher module `src/tv`; launch via
  `android.intent.category.LEANBACK_LAUNCHER`. RU locale. D-pad long-press via the `sendevent`
  held-key workaround (`/dev/input/event2`, scancode 353) per
  [android-tv-dpad-qa-runbook.md](../../../environment/android-tv-dpad-qa-runbook.md).
- Package (shared app id): `com.yahorzabotsin.openvpnclientgate`, debug build of commit `2ad24c9`
  installed via `adb install -r` on both devices (existing app data/favorites from prior test
  sessions was retained, not wiped).

## Known Behavior Constraints (do not report as defects)

- Favorites UI strings are localized on the RU-locale devices used here: header and action labels
  render as "Избранное" / "Добавить в избранное" / "Удалить из избранного"; the TV dialog Cancel
  button is framework-localized ("ОТМЕНА").
- SSE fires `servers-changed` frequently while the app is foregrounded; the visible country list
  can legitimately churn mid-test. Observed directly during this run: a favorited country (Russia)
  temporarily dropped out of the loaded country list between two navigations, which correctly
  suppressed the pinned Favorites section per existing logic (`buildItems` only pins a favorite
  that is also present in the currently-loaded `countries` list) — re-verified as expected,
  pre-existing behavior (matches SUB-05's documented AC3 hide/restore mechanism), not a SUB-06
  regression. Testers should not conclude a framing/rendering defect from an apparently "missing"
  pinned section without first confirming (via long-press dialog state or prefs dump) that the
  favorited item is still present in the currently-rendered list.
