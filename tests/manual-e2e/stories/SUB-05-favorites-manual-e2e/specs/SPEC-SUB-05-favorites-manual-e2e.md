---
id: SPEC-SUB-05-favorites-manual-e2e
title: Favorites end-to-end manual coverage specification (phone + TV)
relatedSuite: SUITE-SUB-05-favorites-manual-e2e
surface: android-mobile, android-tv
story: docs/userstories/MP-20260706-favorite-countries-servers/SUB-05-favorites-manual-e2e.md
---

## Business Context

SUB-01..SUB-04 delivered the complete favorites feature: a persistence layer
(`FavoritesStore` / `favorites_prefs.xml`), pinned "Favorites" sections on the countries screen
(`ServerListActivity`) and the servers-in-country screen (`CountryServersActivity`), a touch
long-press `PopupMenu` on mobile, and a D-pad long-press `FavoriteActionDialog` on TV. SUB-05 is
verification-only: consolidated end-to-end manual coverage on a real phone AND a real TV device,
including the availability hide/restore behavior that unit tests cannot prove (it depends on real
server-list sync timing and backend content changes).

Feature reference: [src/docs/favorites-ui-patterns.md](../../../../../src/docs/favorites-ui-patterns.md).
Sync trigger reference: [src/docs/server-sync-flow.md](../../../../../src/docs/server-sync-flow.md).

## Acceptance Criteria Mapping

- AC1: Add favorite country, remove favorite country, add favorite server, remove favorite server
  on a phone via touch long-press PopupMenu, with pinned Favorites section updating immediately.
  -> CASE-SUB05-001 (countries), CASE-SUB05-002 (servers)
- AC2: The same add/remove flows on TV via D-pad long-press `FavoriteActionDialog`.
  -> CASE-SUB05-003 (countries), CASE-SUB05-004 (servers)
- AC3: A favorited country/server disappears from the pinned Favorites section when absent from a
  server-list sync and reappears automatically once present again in a later sync, with no manual
  re-favoriting (the stored favorite survives in `favorites_prefs.xml` throughout).
  -> CASE-SUB05-005
- AC4: The pinned Favorites section (header and rows) is absent when there are no
  currently-available favorites — both when nothing is favorited and when favorites exist in
  storage but none are available in the current list. -> CASE-SUB05-006 (plus the mid-state
  assertion inside CASE-SUB05-005)
- AC5: Cases follow the documented suite structure and are executed against a real phone and a
  real TV device with pass/fail evidence recorded. -> the suite execution itself
  (SUITE-SUB-05-favorites-manual-e2e run log).

## Evidence Model

- `favorites_prefs.xml` dumps (`run-as com.yahorzabotsin.openvpnclientgate cat
  shared_prefs/favorites_prefs.xml`) before/after every add/remove and across the AC3
  hide/restore window (`favorite_country_codes` as uppercase ISO codes, `favorite_server_ids`
  as positive integer ids).
- uiautomator dumps proving pinned-section presence/absence (`section_header_title` = "Favorites")
  and, on TV, dialog presence (`id/alertTitle`, `android:id/text1` action label,
  `android:id/button2` Cancel) with no PopupMenu container in the hierarchy.
- Full-session logcat scan: no FATAL EXCEPTION, ANR, WindowLeaked, BadTokenException.
- Installed build fingerprint (versionName/versionCode/lastUpdateTime) matching the commit under
  test on BOTH devices.

## Device Baseline

- Phone: Samsung Galaxy A71, Android 13, adb over USB (serial recorded locally in
  AGENTS.local.md; do not store real serials in repo files). Launcher module `src/mobile`;
  launch via `android.intent.category.LAUNCHER`.
- TV: MIBOX4, Android 9, adb over Wi-Fi at `<TV_IP:5555>` (recorded locally in AGENTS.local.md),
  Leanback launcher, RU locale. Launcher module `src/tv`; launch via
  `android.intent.category.LEANBACK_LAUNCHER`.
- Package (shared app id): `com.yahorzabotsin.openvpnclientgate`, debug build of the commit under
  test installed fresh on both devices (`adb install -r`).
- Server source: DEFAULT_V2 (default for new installs). Server favoriting requires V2 positive
  integer server ids — legacy CSV servers have `id=0` and are intentionally non-favoritable.
- Environment runbooks:
  [android-adb-vpn-qa-runbook.md](../../../environment/android-adb-vpn-qa-runbook.md) (phone),
  [android-tv-dpad-qa-runbook.md](../../../environment/android-tv-dpad-qa-runbook.md) (TV,
  including the mandatory `sendevent` held-key long-press workaround).

## Known Behavior Constraints (do not report as defects)

- Favorites UI strings are localized on ru/pl device locales: header and action labels render as
  "Избранное" / "Добавить в избранное" / "Удалить из избранного" (RU) or "Ulubione" / "Dodaj do
  ulubionych" / "Usuń z ulubionych" (PL); the TV dialog Cancel button is framework-localized ("ОТМЕНА" on RU).
- A favorited country/server appears BOTH in the pinned Favorites section and at its normal
  position in the regular list below (intentional design, see favorites-ui-patterns.md).
- On TV, after a favorite toggle the list refreshes and focus jumps to the toolbar back button;
  D-pad DOWN re-enters the list.
- `input keyevent --longpress` injects a SHORT press on MIBOX4 — the `sendevent` held-key
  workaround is mandatory for TV long-press injection.
- SSE fires `servers-changed` frequently while the app is foregrounded; the visible list can
  legitimately churn mid-test (this is the mechanism AC3 relies on).
- TV dialog title for server rows falls back to IP when city is blank (cosmetic, non-V2 sources).

## Residual Risks Watched During Execution

- Backend content churn during AC1/AC2 add/remove steps may hide the chosen row mid-case; pick a
  stable country (e.g. one with many servers) and re-anchor if a sync removes it.
- AC3 depends on inducing a content change (see CASE-SUB05-005 trigger options); if no trigger is
  available in-session, the case blocks rather than passes vacuously.
- Pinned-section scroll position on open (DEF-sub03-header-misscroll-on-open, fixed): watch that
  the Favorites header is visible at the top when entering CountryServersActivity.
