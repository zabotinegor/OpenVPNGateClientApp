# Orchestration Context: SUB-08

## Discovered during master-plan BA (do not re-discover)
- Affected files: `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/FavoriteActionDialog.kt` (presentation-gate seam: `resolvePresentation(isTvDevice, canFavorite)` → `NONE` / `TV_DIALOG` / `POPUP_MENU`), `ServerListActivity.showFavoriteActionMenu`/`showFavoriteMenu`, `CountryServersActivity` equivalent, `FavoriteActionDialogTest.kt`.
- Stack markers found: TV dialog is a plain AppCompat `AlertDialog`, explicitly documented as "same widget family as `MainActivityCore.showUpdateDialog`" — that existing dialog is the closest in-app precedent for themed dialog styling to match. Mobile menu is a plain `PopupMenu`, anchored to the long-pressed row's touch coordinates (not screen-centered) — any restyle must preserve that anchoring.
- Confirmed via the user's screenshots: the reported "looks default Android" component is the TV `AlertDialog` (screenshot titled "MIBOX4", showing a plain gray dialog with default Material dialog chrome and an unstyled "ОТМЕНА" cancel button) — this is the primary visual complaint, but the mobile `PopupMenu` has the same stock-widget problem and is included in scope for consistency.
- `values-night/` exists for dark theme; row cards elsewhere use `?attr/colorSurface` etc. (theme attributes, not hardcoded colors) — follow that pattern.

## Key decisions made
- Both mobile `PopupMenu` and TV `AlertDialog` are in scope (not just the TV one shown in the screenshot), since they're the same logical component (`FavoriteActionDialog` presentation gate) and should be visually consistent with each other and with the rest of the app.
- `FavoriteActionDialogTest.kt` notes the themed dialog "cannot be built in core unit tests (legacy Robolectric resources mode)" — on-device manual verification is required, consistent with prior sub-plans' testing approach for this file.

## Dependencies from prior sub-plans
- SUB-04 output: `FavoriteActionDialog.kt` and its presentation-gate logic already implemented and merged; this sub-plan only restyles the visual presentation, not the gate/state logic.
- Independent of SUB-06 (framing) and SUB-07 (localization) — different files, can run in parallel.

## Skip in BA step
- Full repo scan (already done).
- `FavoriteActionDialog` / dialog-widget discovery (already done — see affected files above).
