# SUB-08: Themed favorite add/remove action component

## Scope boundary
Restyle the add-to-favorites/remove-from-favorites interaction on both mobile (`PopupMenu` in `ServerListActivity`/`CountryServersActivity`) and TV (`AlertDialog` via `FavoriteActionDialog`) to match the app's own visual design system instead of the stock Android widget appearance, respecting light and dark theme.

## Acceptance criteria
1. On mobile, the long-press favorite action menu uses app-styled visuals (colors, typography, corner radius/elevation consistent with existing app components) instead of the default `PopupMenu` appearance, in both light and dark theme.
2. On TV, the D-pad long-press favorite `AlertDialog` (`FavoriteActionDialog`) uses app-styled visuals consistent with the app's other dialogs (e.g. `MainActivityCore.showUpdateDialog`) instead of the stock AlertDialog appearance, in both light and dark theme.
3. The action label still correctly reflects current favorite state ("Add to favorites" / "Remove from favorites") per existing `FavoriteActionDialog.resolvePresentation` state logic — unchanged behavior, styling only.
4. Cancel/dismiss behavior, toast feedback after toggling, and the `TV_DIALOG` / `POPUP_MENU` / `NONE` presentation gate are unchanged.
5. No regression to window-leak guard behavior (dialog/menu tracked and dismissed on `onDestroy`, previous instance dismissed before showing a new one).
6. Verified visually on both a mobile device/emulator and a TV device/emulator, in both light and dark theme.

## Out of scope
- Any change to favorites data/persistence or availability filtering (SUB-01).
- Pinned-section border/framing (SUB-06).
- New or changed localized strings beyond what SUB-07 already covers (SUB-08 reuses existing `favorites_add_action`/`favorites_remove_action` keys as-is).

## dependsOn
None (independent of SUB-06 and SUB-07; can run in parallel with them).
