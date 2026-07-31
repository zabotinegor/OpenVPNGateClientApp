# Favorites UI Patterns and Implementation Guide

Data layer, case-normalization boundary, and the mobile and TV interaction patterns for favouriting
countries and servers. QA evidence for this area lives in ClickUp.

## Index

Read this list first and jump to the one relevant heading — do not read the whole file.

- [Overview](#overview)
- [Case Normalization Boundary](#case-normalization-boundary)
- [Long-Press PopupMenu Pattern](#long-press-popupmenu-pattern)
- [Pinned Favorites Section](#pinned-favorites-section)
- [Testing Strategy](#testing-strategy)
- [TV D-pad Dialog Pattern (SUB-04)](#tv-d-pad-dialog-pattern-sub-04)
- [Logging Considerations](#logging-considerations)
- [Localization](#localization)
- [Related Documents](#related-documents)

---

## Overview

The favorites feature allows users to mark and quickly access their preferred countries and servers. Implementation is split across multiple sub-plans and surfaces:

- **SUB-01**: Data layer and persistence (`FavoritesStore`, `FavoritesCountryStore`, `FavoritesFilter`)
- **SUB-02**: Countries screen UI (`ServerListActivity`, `CountryListAdapter`)
- **SUB-03**: Servers-in-country screen UI (`CountryServersActivity`, `CountryServersAdapter`)
- **SUB-04**: TV D-pad interaction (both screens)

All surfaces use a consistent case-insensitive casing normalization strategy and long-press / D-pad interaction pattern.

## Case Normalization Boundary

### Strategy

All favorite country codes are normalized to uppercase (Locale.ROOT) at the `FavoritesStore` boundary. This is the **single source of truth** for casing.

**Key file:** `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesStore.kt`

**Implementation detail:**
```kotlin
private fun normalizeCountryCode(countryCode: String): String =
    countryCode.uppercase(Locale.ROOT)
```

Every public method in `FavoritesStore` that deals with country codes normalizes the input:
- `addFavoriteCountry(ctx, countryCode)` — normalizes before storage
- `removeFavoriteCountry(ctx, countryCode)` — normalizes before lookup
- `isFavoriteCountry(ctx, countryCode)` — normalizes for comparison
- `getFavoriteCountryCodes(ctx)` — returns already-normalized codes

### Why This Approach

1. **Prevents duplicate favorites**: Different casing variants of the same country code (e.g., "AU" vs "au") are treated as identical.
2. **Centralizes logic**: Callers (ViewModels, Activities, filters) do not need to normalize case independently.
3. **Reduces test fragility**: Unit tests for UI layers don't need to verify casing edge cases; the store boundary is already covered by `FavoritesStoreTest`.
4. **Matches real-world data**: VPN Gate and OpenVPN Gate APIs may return country codes in different casings across different data sources or API versions. Normalization at the store boundary absorbs these variations.

### DI Facade Pattern

`FavoritesCountryStore` is an interface that hides the underlying `FavoritesStore` implementation:

```kotlin
interface FavoritesCountryStore {
    fun getFavoriteCountryCodes(): Set<String>
    fun isFavoriteCountry(countryCode: String): Boolean
    fun addFavoriteCountry(countryCode: String)
    fun removeFavoriteCountry(countryCode: String)
}

class DefaultFavoritesCountryStore(private val appContext: Context) : FavoritesCountryStore {
    // Delegates to FavoritesStore (which handles normalization)
}
```

**Wiring** (in `CoreDi.kt`):
```kotlin
single<FavoritesCountryStore> { DefaultFavoritesCountryStore(get()) }
```

ViewModels and Activities depend on the interface, allowing for easy mocking in unit tests. The interface methods accept any casing and return normalized results.

### Testing Pattern

**Unit test example** (from `ServerListViewModelTest`):

```kotlin
@Test
fun testToggleFavoriteCaseInsensitive() {
    // API returns lowercase; user passes any casing
    val country = Country(code = "au", name = "Australia")
    viewModel.onAction(ServerListAction.ToggleFavorite(country))
    
    // Store normalizes to "AU" internally
    assertTrue(fakeFavoritesStore.isFavoriteCountry("AU"))
    assertTrue(fakeFavoritesStore.isFavoriteCountry("au"))  // Both casings work
    assertTrue(fakeFavoritesStore.isFavoriteCountry("Au"))
}
```

**Integration test pattern** (for SUB-03/SUB-04):
When testing servers or TV interaction, rely on the store's casing handling. Do not add extra case-normalization logic to the ViewModel or Activity.

### Precondition: Legacy Server.id Collision

**See:** ClickUp QA evidence — "Legacy Server.id=0 collision (major)"

The `FavoritesStore` treats `serverId <= 0` as invalid and silently drops them:

```kotlin
fun addFavoriteServer(ctx: Context, serverId: Int) {
    if (serverId <= 0) return  // Guard: ignore invalid IDs
}
```

This is safe because:
1. The V2 API (DEFAULT_V2) always returns positive integer server IDs.
2. CSV servers from VPN Gate (VPNGATE source) do not have integer IDs and are intentionally not favoritable.

If a future migration allows CSV server favorites, this guard will need revision. Document this assumption in any change that relaxes the `serverId > 0` constraint.

## Long-Press PopupMenu Pattern

### PopupMenu overview

When a user long-presses a country or server row on mobile, a `PopupMenu` appears with an action to add or remove the item from favorites.

**Implemented in SUB-02**: `ServerListActivity.showFavoriteActionMenu(country)`

**To be replicated in SUB-03**: `CountryServersActivity.showFavoriteActionMenu(server)`

**Adapted for SUB-04**: TV D-pad uses a different presentation (self-contained `AlertDialog`, see "TV D-pad Dialog Pattern" below) but the state machine (favorite / not favorite) and the ViewModel `ToggleFavorite` action remain identical.

### Menu Item Labeling

The menu item text reflects the current favorite state:

- If the country is currently a favorite: menu shows **"Remove from favorites"**
- If the country is not a favorite: menu shows **"Add to favorites"**

**Code pattern** (SUB-02):
```kotlin
private fun showFavoriteActionMenu(country: Country) {
    val isFavorite = favoritesStore.isFavoriteCountry(country.code)
    val actionLabel = if (isFavorite) {
        "Remove from favorites"
    } else {
        "Add to favorites"
    }
    popupMenu.menu.add(actionLabel).setOnMenuItemClickListener {
        viewModel.onAction(ServerListAction.ToggleFavorite(country))
        true
    }
    popupMenu.show()
}
```

This pattern ensures users always see the action they can take, not the state.

### Themed styling — mobile PopupMenu

`android:popupMenuStyle` in the base theme (`Widget.OpenVPNClientGate.PopupMenu`, backed by
`drawable/bg_popup_menu.xml`) styles the popup app-wide without touching `PopupMenu`
construction/leak-guard code in the Activities. First delivery of SUB-08 wired this correctly
but the popup still visually read as stock on real devices: the background drawable filled with
`?attr/colorSurface`, which is the **exact same color** the row `MaterialCardView`s behind it
also use (`app:cardBackgroundColor="?attr/colorSurface"`), so the popup blended into the list
instead of reading as a raised, distinct surface — technically styled, visually invisible. Fix:
a dedicated `ovpnPopupMenuBackground`/`ovpnPopupMenuStroke` token pair (concrete values in
`values/colors.xml` / `values-night/colors.xml`, distinct from both `app_background` and
`nav_bar_background`/`colorSurface`), a 1dp stroke, bumped `android:popupElevation`, and an
explicit `TextAppearance.OpenVPNClientGate.PopupMenuItem` wired via
`android:textAppearanceLargePopupMenu`/`SmallPopupMenu` so the item label reads as app-styled
text. **Lesson**: when a themed widget still "looks stock", check whether its background token
resolves to the *same* value as the surface behind it — passing color-attribute review does not
guarantee visible contrast.

### Toast Feedback

After toggling, a toast message confirms the action:

- **Added**: Toast displays string resource `@string/favorites_added_toast` (e.g., "Added to favorites")
- **Removed**: Toast displays string resource `@string/favorites_removed_toast` (e.g., "Removed from favorites")

**In ViewModel**:
```kotlin
private fun toggleFavorite(country: Country) {
    val currentlyFavorite = favoritesStore.isFavoriteCountry(country.code)
    if (currentlyFavorite) {
        favoritesStore.removeFavoriteCountry(country.code)
    } else {
        favoritesStore.addFavoriteCountry(country.code)
    }
    viewModelScope.launch {
        val text = if (currentlyFavorite) {
            UiText.Res(R.string.favorites_removed_toast)
        } else {
            UiText.Res(R.string.favorites_added_toast)
        }
        _effects.emit(ServerListEffect.ShowToast(text))
    }
    updateState { it.copy(favoriteCountryCodes = favoritesStore.getFavoriteCountryCodes()) }
}
```

### State Synchronization

When a favorite is toggled:
1. `FavoritesStore.add/removeFavoriteCountry()` updates SharedPreferences synchronously.
2. ViewModel fetches the updated favorite set: `favoritesStore.getFavoriteCountryCodes()`.
3. State is updated immediately: `updateState { it.copy(favoriteCountryCodes = ...) }`.
4. UI re-renders (pinned section appears/disappears, menu label changes on next long-press).

**No manual refresh is required.** The pattern is synchronous and self-contained.

## Pinned Favorites Section

### Rendering Logic

The "Favorites" section appears at the top of the country list when at least one favorite country is currently available.

**In `ServerListActivity` (SUB-02)**:

The list is constructed as a single flattened sequence where:
1. If there are any favorite countries, a "Favorites" section header is prepended first.
2. The favorite countries are listed immediately after that header (same rows as in the regular list).
3. **All countries are appended below — no second section header — including favorites**, so a favorited country appears in **both the pinned "Favorites" section AND in its normal alphabetical position** in the regular (unheaded) list below.

This design avoids duplication in the code (reusing the same row component) while giving users quick access to their favorites at the top.

### Section Header Rendering

**Layout file:** `src/core/src/main/res/layout/item_country_section_header.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<TextView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/section_header_title"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginStart="16dp"
    android:layout_marginTop="16dp"
    android:layout_marginEnd="16dp"
    android:layout_marginBottom="4dp"
    android:textAppearance="@style/TextAppearance.OpenVPNClientGate.Subtitle"
    android:textStyle="bold" />
```

### Country Row Rendering

Favorite countries in the pinned section use **the same row component** as the regular list. No special styling or behavior is applied; they are identical rows, just positioned at the top.

**Rationale**: Code reuse + visual consistency.

### Visual Framing and Card Treatment (SUB-06/SUB-09)

The pinned "Favorites" section is visually distinguished from the rest of the list by a **filled card background** drawn around the header and pinned rows. This card treatment is implemented as a `RecyclerView.ItemDecoration` (`FavoritesSectionCardDecoration`) that renders a colored, rounded-rectangle background at the section boundaries.

**Implementation approach**:

- **ItemDecoration pattern**: The card background is rendered by a custom `ItemDecoration` attached to the RecyclerView, not within individual row layouts. This keeps row components unchanged and allows the card to span multiple rows seamlessly.
- **Shared between mobile and TV**: Both `ServerListActivity` (countries) and `CountryServersActivity` (servers-in-country) use the same `FavoritesSectionCardDecoration` for both mobile and TV surfaces, since both reuse the same core layouts and adapters.
- **Theme-aware coloring**: The card background color resolves `?attr/colorSurfaceVariant` via theme attributes, ensuring correct rendering in both light and night themes without hardcoding colors.
- **Internal padding**: The card includes internal padding so pinned rows do not touch the card edges, creating visual separation and breathing room.
- **Adapter support method**: The adapter exposes `pinnedSectionItemCount()` to inform the decoration how many consecutive rows belong to the pinned section (header + pinned rows).

**Visual dimensions** (dimen resources):

```xml
<!-- Card background corner radius (~12dp target) -->
<dimen name="favorites_section_card_corner_radius">12dp</dimen>

<!-- Internal padding of the card (space between card edges and rows) -->
<dimen name="favorites_section_card_padding">8dp</dimen>

<!-- Horizontal inset of the card from the recyclerview edges -->
<dimen name="favorites_section_card_inset">2dp</dimen>
```

**Code pattern** (from adapter):

```kotlin
fun pinnedSectionItemCount(): Int =
    if (currentState.favoriteCountryCodes.isNotEmpty()) {
        1 + currentState.favoritedCountries.size  // header + pinned rows
    } else {
        0
    }
```

Then in the Activity:

```kotlin
val decoration = FavoritesSectionCardDecoration(
    pinnedSectionItemCount = { adapter.pinnedSectionItemCount() },
    context = this
)
recyclerView.addItemDecoration(decoration)
```

The decoration queries `pinnedSectionItemCount()` to determine the exact range of positions to enclose in the card, and renders the background only for that range, leaving the rest of the list (regular countries/servers) without card styling.

### Favorites Section Header with Star Icon (SUB-09)

The "Favorites" section header includes a **small star icon** displayed next to the section title text. This visual indicator reinforces the favorites concept and improves the section's visual identity.

- **Icon placement**: The star appears immediately to the left of the "Favorites" text.
- **Styling**: The star inherits the section header's text color and size, creating visual cohesion with existing section headers in the list.
- **Rendering**: Implemented via a drawable resource (e.g., `ic_star.xml` or similar), composited with the header TextView via `setCompoundDrawablesWithIntrinsicBounds()` or equivalent.

### "All Countries" / "All Servers" Section Header (SUB-09)

When the Favorites section is visible (i.e., there is at least one favorited item), a **second section header** appears above the full list, labeled "All countries" (on the countries screen) or "All servers" (on the servers-in-country screen). This header clarifies the section below and maintains consistent visual hierarchy.

- **Header positioning**: Appears immediately below the pinned Favorites card (after the last pinned row).
- **Visibility**: Only shown when the Favorites section is visible; hidden when there are no favorites, so the list defaults back to the original unlabeled full-list appearance.
- **String keys**: `all_countries_section_title` and `all_servers_section_title`, translated into every locale SUB-07 covers (English, Russian, Polish).
- **Styling**: Uses the same text style and appearance as the "Favorites" header, maintaining visual consistency.

**Adapter pattern** (returns sections in order):

```kotlin
if (favoriteCountryCodes.isNotEmpty()) {
    // Pinned Favorites card section:
    // - Item 0: "Favorites" header (background drawn by FavoritesSectionCardDecoration)
    // - Items 1..(N-1): pinned favorite country rows
    
    // All-list section:
    // - Item N: "All countries" header
    // - Items N+1..(end): all countries in alphabetical order (including duplicates of pinned items)
} else {
    // No Favorites card; just the full list with no header
    // - Items 0..(end): all countries in alphabetical order
}
```

**Localization strings** (SUB-07):

- `all_countries_section_title` — "All countries" (English), "Все страны" (Russian), "Wszystkie kraje" (Polish)
- `all_servers_section_title` — "All servers" (English), "Все серверы" (Russian), "Wszystkie serwery" (Polish)

### Hidden When Empty

If all favorite countries are removed, or if none are currently available in the synced list, the "Favorites" section header and its rows are not displayed. The list defaults to the original appearance with no header (or just the unlabeled full list). The card decoration automatically hides as well (when `pinnedSectionItemCount()` returns 0, no range is enclosed in the card).

**Trigger**: When `FavoritesFilter.filterFavoriteCountries(...)` (or `filterFavoriteServers(...)` on the servers screen) returns an empty list, the pinned section is hidden entirely.

### Availability Filtering

The pinned section shows only **currently available** favorite countries. If a user's favorite country is not present in the latest server sync (e.g., the VPN Gate server list for that country is temporarily offline), the favorite is hidden from the pinned section but **remains in storage**.

Once the country reappears in a future sync, it automatically reappears in the pinned section without requiring the user to re-favorite it.

**Code pattern** (from `FavoritesFilter`):
```kotlin
fun filterFavoriteCountries(
    favoriteCountryCodes: Set<String>,
    countries: List<CountryV2>
): List<CountryV2> {
    if (favoriteCountryCodes.isEmpty() || countries.isEmpty()) return emptyList()
    val upperCaseFavorites = favoriteCountryCodes.map { it.uppercase(Locale.ROOT) }.toSet()
    return countries.filter { countryV2 -> countryV2.code.uppercase(Locale.ROOT) in upperCaseFavorites }
}
```

The server-side equivalent is `filterFavoriteServers(favoriteServerIds: Set<Int>, servers: List<Server>)`, matching by `Server.id`.

## Testing Strategy

### Unit Tests (ViewModel Layer)

**File:** `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/ServerListViewModelTest.kt`

Test patterns:

1. **Toggling favorite**: Verify that `favoritesStore.add/removeFavoriteCountry()` is called with the correct country code.
2. **State update**: Verify that the ViewModel state includes the updated `favoriteCountryCodes` set.
3. **Toast emission**: Verify that the correct toast message is emitted as an effect.
4. **Case insensitivity**: Verify that the ViewModel handles any casing of country codes (the store handles normalization).

**Example test case**:
```kotlin
@Test
fun testToggleFavoriteCausesToastAndStateUpdate() {
    val country = Country(code = "AU", name = "Australia")
    
    viewModel.onAction(ServerListAction.ToggleFavorite(country))
    
    assertThat(fakeFavoritesStore.isFavoriteCountry("AU")).isTrue()
    assertThat(viewModel.state.value.favoriteCountryCodes).contains("AU")
    val toast = viewModel.effects.first() as ServerListEffect.ShowToast
    assertThat(toast.text).isEqualTo(UiText.Res(R.string.favorites_added_toast))
}
```

### Unit Tests (Store Layer)

**File:** `src/core/src/test/java/com/yahorzabotsin/openvpnclientgate/core/servers/FavoritesStoreTest.kt`

Store tests verify:

1. **Casing normalization**: `addFavoriteCountry("au")` followed by `isFavoriteCountry("AU")` returns true.
2. **Persistence**: Favorites survive a simulated process kill (SharedPreferences save/restore).
3. **Idempotency**: Adding the same favorite twice doesn't create duplicates.
4. **Removal**: Removing a favorite that doesn't exist is a no-op.

### Manual E2E Test Cases

**Reference:** ClickUp QA evidence

Test on a real device:

1. **AC1 (Pinned section appearance)**: Long-press a country, add to favorites, verify "Favorites" header appears at top.
2. **AC2 (Section hidden when empty)**: Remove the last favorite, verify "Favorites" section disappears.
3. **AC3 (Menu state reflection)**: Verify menu shows "Add to favorites" or "Remove from favorites" correctly.
4. **AC4 (Tap navigation)**: Tap a favorite in the pinned section, verify navigation to that country's servers.
5. **AC5 (Immediate update)**: Verify no manual refresh is needed; state updates synchronously.
6. **AC6 (Regression)**: Verify regular (non-favorite) country rows behave unchanged.

**Device used in SUB-02 QA**: Samsung Galaxy A71, Android 13.

**Commands**:
```bash
adb -s <your-device-serial> install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <your-device-serial> shell monkey -p com.yahorzabotsin.openvpnclientgate -c android.intent.category.LAUNCHER 1
adb -s <your-device-serial> shell uiautomator dump /sdcard/ui.xml && cat /sdcard/ui.xml | grep "Favorites"
```

## TV D-pad Dialog Pattern (SUB-04)

**Key file:** `src/core/src/main/java/com/yahorzabotsin/openvpnclientgate/core/ui/serverlist/FavoriteActionDialog.kt`

### Interaction model

- **Trigger**: holding the D-pad OK/center (or ENTER) button on a focused country/server row.
  No key handling was added — Android's `View` natively converts a held confirm key on a
  focused, long-clickable view into `performLongClick()`, so the same adapter
  `setOnLongClickListener` used for mobile long-press fires on TV. Rows are focusable via the
  `WidgetOpenVPNClientGateFocusableSurface` style; the section header is not focusable, so
  D-pad navigation naturally skips it.
- **Presentation**: a plain AppCompat `AlertDialog` (same widget family as
  `MainActivityCore.showUpdateDialog`) with the row's display name as title, a single action
  item ("Add to favorites" / "Remove from favorites") and a Cancel button. `PopupMenu` is NOT
  used on TV — it anchors to touch coordinates and does not participate in D-pad focus order.
- **Short-press select/connect is unchanged**: the dialog only opens on long-press;
  `TvDrawerInteractionGuard` (main screen drawer) is untouched — it never applied to the list
  Activities.

### Themed styling — TV dialog

First attempt set `colorSurface`/`colorOnSurface`/`android:textColorPrimary` items on
`ThemeOverlay.OpenVPNClientGate.AlertDialog` (`values/themes.xml`), reasoning by analogy with
Material's `colorSurface`-driven surfaces elsewhere in the app. On-device screenshots (with pixel
color sampling via `System.Drawing` — visual inspection alone was not enough to catch this) showed
it had **no effect**: `FavoriteActionDialog.show()` uses a plain
`androidx.appcompat.app.AlertDialog.Builder`, not `com.google.android.material.dialog.MaterialAlertDialogBuilder`.
Inspecting the `material-1.13.0.aar` source directly showed `ThemeOverlay.MaterialComponents.Dialog.Alert`
resolves, for this construction path, to plain `ThemeOverlay.AppCompat.Dialog.Alert` — the
`colorSurface` → `android:windowBackground` indirection (elevation-overlay-aware `MaterialShapeDrawable`)
is wired up in Java code that only runs for `MaterialAlertDialogBuilder`. What looked like a fix in
dark theme was actually Material's default elevation-overlay tint on the *unmodified* `colorSurface`
(coincidentally close to our intended color); light theme has no elevation overlay, so the dialog
rendered as plain white, exposing the failure. The title (`alertTitle`, styled via
`?android:attr/windowTitleStyle` per `abc_alert_dialog_title_material.xml`) was separately invisible
in light theme — `android:textColorPrimary` did not reach it either.

**Fix (SUB-08 initial)**: set `android:windowBackground` directly to a shape drawable
(`drawable/bg_alert_dialog.xml`, filled with a dedicated `ovpnDialogSurfaceBackground` token) —
the same reliable pattern already used for the PopupMenu's `android:popupBackground` — and
`android:windowTitleStyle` directly to `Widget.OpenVPNClientGate.AlertDialogTitle` with an
explicit `android:textColor`, instead of relying on Material color-attribute indirection that
doesn't apply to this dialog construction path.

**DEF-5 refinement**: The color-only fill was insufficient on real TV screens — with a window
dim/scrim behind the dialog, the fill-only difference did not read as "themed" to users testing
on actual hardware. Added a 1dp stroke (`ovpnDialogSurfaceStroke`) to `bg_alert_dialog.xml`,
mirroring the PopupMenu's proven border pattern (`bg_popup_menu.xml`). The stroke uses the same
token pair already validated for mobile PopupMenu (light `#B9C4D1` / dark `#3F3F3F`), providing
visible contrast from both the TV window scrim and the surrounding page background. Verified
on-device via pixel sampling: dark theme border `#3F3F3F` vs fill `#2E2E2E` vs scrim background,
light theme border `#B9C4D1` vs fill `#DBE3EC` vs scrim background — both clearly distinct.

**Lesson**: a styled widget being technically wired up does not guarantee it is *visually distinguishable*
on real hardware. Stock AppCompat `AlertDialog` (non-Material) with a color-only fill may be
insufficient for visible differentiation at typical TV viewing distances or under specific lighting
conditions. Always verify styled widgets with actual on-device screenshots and pixel-level color
sampling, especially across themes and surfaces.

### Presentation gate

`FavoriteActionDialog.resolvePresentation(isTvDevice, canFavorite)` is the single seam that
decides `NONE` (invalid target: blank country code, `server.id <= 0`), `TV_DIALOG` (TV), or
`POPUP_MENU` (touch). Both Activities route `showFavoriteMenu` through it, replacing the
early-return TV gates left by SUB-02/SUB-03.

### Dialog title for server rows

`CountryServersActivity.tvFavoriteDialogTitle(city, ip)` — trimmed city when present,
otherwise IP — mirrors the row-title fallback in `ServerPickerAdapter`.

### Window-leak guard

The Activity tracks the dialog in `activeTvFavoriteDialog`, dismisses any previous instance
before showing a new one, and dismisses it in `onDestroy()` — mirroring the `activePopupMenu`
guard from SUB-02/SUB-03.

### Testing

`FavoriteActionDialogTest` covers the presentation gate, the state-reflecting action label,
and the server dialog-title fallback. The themed dialog itself cannot be built in core unit
tests (legacy Robolectric resources mode); on-device verification is done manually. SUB-04
Manual QA passed all 5 cases (SUITE-SUB-04) on a MIBOX4 Android 9 TV; consolidated phone+TV
coverage was completed in SUB-05 Manual E2E (SUITE-SUB-05 executed and passed on a real phone
and TV, including availability hide/restore verified via a controlled local mock backend — see
the ClickUp QA suite). For device setup, `sendevent`-based D-pad long-press
injection (`input keyevent --longpress` delivers a short press on this hardware), and dialog
focus gotchas, see `docs/operations/device-qa-tv.md`.

## Logging Considerations

**Reference:** `docs/features/logging.md`

When logging favorites state changes:

- **Do NOT log raw user selections** (e.g., country codes derived from user input).
- **Do log actions and results**: e.g., "toggled_favorite action=add_success" or "toggled_favorite action=remove_success".
- **Do NOT log user data**: Avoid logging selected country name or server IP in production builds.

**Pattern** (from `ServerListViewModel`):
```kotlin
private fun toggleFavorite(country: Country) {
    val currentlyFavorite = favoritesStore.isFavoriteCountry(country.code)
    val action = if (currentlyFavorite) "remove" else "add"
    // Action is logged, but country code is not
    logInfo("toggled_favorite action=$action")
    ...
}
```

## Localization

**String resource coverage (SUB-07):**

The following 7 favorites-related string keys have been translated to Russian (ru) and Polish (pl):

**Original SUB-07 strings (5 keys):**
- `favorites_section_title` — "Избранное" (ru), "Ulubione" (pl)
- `favorites_add_action` — "Добавить в избранное" (ru), "Dodaj do ulubionych" (pl)
- `favorites_remove_action` — "Удалить из избранного" (ru), "Usuń z ulubionych" (pl)
- `favorites_added_toast` — "Добавлено в избранное" (ru), "Dodano do ulubionych" (pl)
- `favorites_removed_toast` — "Удалено из избранного" (ru), "Usunięto z ulubionych" (pl)

**New SUB-09 strings (2 additional keys):**
- `all_countries_section_title` — "All countries" (English), "Все страны" (ru), "Wszystkie kraje" (pl)
- `all_servers_section_title` — "All servers" (English), "Все серверы" (ru), "Wszystkie serwery" (pl)

Manual testing confirms correct rendering in both locales across countries and servers surfaces (CASE-SUB07-001 through 004, all PASS). SUB-09 visual redesign verified on real devices with localized "All countries"/"All servers" headers appearing correctly in light/dark theme and all three locales. See the ClickUp QA suite (ClickUp QA evidence is gitignored and not tracked in the repo).

## Related Documents

- `docs/features/server-sync.md` — How server list syncs trigger re-filtering of favorites
- `docs/features/logging.md` — Privacy and logging guidelines
- `CLAUDE.md` — Architecture overview and entry points
- ClickUp QA evidence — Data-layer preconditions and residual risks
- ClickUp QA evidence — SUB-02 manual QA evidence and workarounds
- ClickUp QA evidence — SUB-07 localization manual QA evidence and per-app locale override technique
- `docs/operations/device-qa-tv.md` — Android TV D-pad QA runbook (Leanback launch, long-press injection, dialog focus)

---

*Last verified against: SUB-09 visual redesign + per-row star indicator (2026-07-22).*
