---
id: SPEC-servers-favorites-ui
title: Servers-in-country screen favorites UI (mobile touch)
storyId: SUB-03
area: Android
surfaces: [android]
---

## Scope
- Servers-in-country screen (`CountryServersActivity` / `ServerPickerAdapter` / `CountryServersViewModel`) in `src/core`.
- Pinned "Favorites" section (additive: favorited servers appear in BOTH the pinned section and the regular list),
  long-press add/remove-favorite PopupMenu, tap selection from both sections, persistence across restart,
  and first-item focus skipping the section header.
- Out of scope: countries screen (SUB-02), TV/D-pad (SUB-04), availability filtering internals (SUB-01),
  `Server.id == 0` favoritability fix (carried-forward known limitation).

## Acceptance Criteria Mapping
- AC-1 (pinned Favorites section when >=1 favorite of current country available): CASE-favorites-section-appears-additive
- AC-2 (no pinned section when no favorite available; regular list unchanged): CASE-favorites-section-hidden-when-empty
- AC-3 (long-press PopupMenu reflecting favorite state; id==0 not favoritable): CASE-long-press-toggle-menu
- AC-4 (tap in favorites section selects like regular list): CASE-favorites-section-tap-selection
- AC-5 (toggle updates pinned section immediately): CASE-favorites-section-appears-additive, CASE-favorites-section-hidden-when-empty
- AC-6 (regular single-tap/non-favorites behavior unchanged): CASE-regular-list-tap-unchanged
- Regression (additive pattern + restart persistence + header focus skip): CASE-favorites-section-appears-additive, CASE-favorites-persist-across-restart

## Test Data and Environment
- Real Android device, ADB authorized; mobile debug variant installed via `adb install -r`.
- A country with >=2 servers (e.g. Vietnam, 3 servers) so both sections are distinguishable.
- Clean favorites state at start: `shared_prefs/favorites_prefs.xml` has empty `favorite_server_ids`
  (inspect via `adb shell run-as com.yahorzabotsin.openvpnclientgate cat shared_prefs/favorites_prefs.xml`, debug build only).
- Favorites strings are English on ru/pl locales (no localized `favorites_*` entries): "Favorites",
  "Add to favorites", "Remove from favorites".

## Risks and Notes
- `Server.id == 0` rows are not producible on-device with the v2 source (real ids assigned); the id<=0 guard
  is verified by unit tests (ViewModel + Activity + FavoritesServerStore layers).
- Header focus-skip is a keyboard/D-pad concern; on touch devices verify no mis-scroll/crash on screen open and
  rely on unit test `fix3_focus_position_is_1_when_favorites_section_present_skips_header`.
