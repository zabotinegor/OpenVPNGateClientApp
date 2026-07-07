---
id: SPEC-countries-favorites-ui
title: Countries screen favorites UI (mobile touch)
storyId: SUB-02
area: Android
surfaces: [android]
---

## Scope
- Countries list screen (`ServerListActivity` / `CountryListAdapter`) in `src/core`.
- Pinned "Favorites" section rendering, long-press add/remove-favorite action, and tap navigation for both
  the favorites section and the regular alphabetical list.
- Out of scope: servers-within-country screen (SUB-03), TV/D-pad interaction (SUB-04), and the underlying
  availability-filter/persistence logic itself (SUB-01, consumed here only).

## Acceptance Criteria Mapping
- AC-1 (pinned Favorites section shown when >=1 favorite available, reusing row rendering): CASE-favorites-section-appears
- AC-2 (no pinned section when no favorite is currently available): CASE-favorites-section-hidden-when-empty
- AC-3 (long-press presents add/remove-favorite PopupMenu reflecting current state): CASE-long-press-toggle-menu
- AC-4 (tap in favorites section navigates like regular list): CASE-favorites-section-tap-navigation
- AC-5 (favoriting/unfavoriting updates pinned section immediately, no manual refresh): CASE-favorites-section-appears, CASE-favorites-section-hidden-when-empty
- AC-6 (existing single-tap/non-favorites behavior unchanged): CASE-regular-list-tap-navigation-unchanged

## Test Data and Environment
- Device: real Android device (Samsung-class), ADB authorized.
- Build: mobile debug variant, `assembleDebugApp` from `src/`, installed via `adb install -r`.
- Requires `PRIMARY_SERVERS_URL` / `FALLBACK_SERVERS_URL` resolvable via `src/servers.local.json` (already present
  in this repo checkout) when no Gradle `-P`/env values are supplied.
- No favorites pre-seeded; test starts from a clean favorites state (verified via absence of "Favorites" header).

## Risks and Out of Scope
- Multi-user ADB shell restrictions on some devices block `pm list packages` without `--user 0`; use
  `dumpsys package <id>` with explicit package name instead (see environment/android.md).
- TV/D-pad long-press interaction is out of scope for this story (SUB-04).
