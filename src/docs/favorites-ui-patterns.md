# Favorites UI Patterns and Implementation Guide

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

**See:** `docs/qa-evidence/favorites-data-layer-gate-1.md` — "Legacy Server.id=0 collision (major)"

The `FavoritesStore` treats `serverId <= 0` as invalid and silently drops them:

```kotlin
fun addFavoriteServer(ctx: Context, serverId: Int) {
    if (serverId <= 0) return  // Guard: ignore invalid IDs
}
```

This is safe because:
1. The V2 API always returns positive integer server IDs.
2. Legacy CSV servers from VPN Gate do not have integer IDs and should not be favoritable (not scope for this feature).

However, if a future migration allows legacy CSV favorites, this guard will need revision. Document this assumption in any change that relaxes the `serverId > 0` constraint.

## Long-Press PopupMenu Pattern

### Overview

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

### Visual Framing (SUB-06)

The pinned "Favorites" section is visually distinguished from the rest of the list by a **frame border** drawn around the header and pinned rows. This framing is implemented as a `RecyclerView.ItemDecoration` (`FavoritesSectionFrameDecoration`) that draws a rounded rectangle border at the section boundaries.

**Implementation approach**:

- **ItemDecoration pattern**: The border is rendered by a custom `ItemDecoration` attached to the RecyclerView, not within individual row layouts. This keeps row components unchanged and allows the frame to span multiple rows.
- **Shared between mobile and TV**: Both `ServerListActivity` (countries) and `CountryServersActivity` (servers-in-country) use the same `FavoritesSectionFrameDecoration` for both mobile and TV surfaces, since both reuse the same core layouts and adapters.
- **Theme-aware coloring**: The frame stroke color resolves `?attr/colorSecondary` via `MaterialColors.getColor()`, ensuring correct rendering in both light and night themes without hardcoding colors.
- **Adapter support method**: The adapter exposes `pinnedSectionItemCount()` to inform the decoration how many consecutive rows belong to the pinned section (header + pinned rows).

**Visual dimensions** (new dimen resources):

```xml
<!-- Frame stroke width -->
<dimen name="favorites_section_frame_stroke_width">1.5dp</dimen>

<!-- Corner radius for the frame border -->
<dimen name="favorites_section_frame_corner_radius">8dp</dimen>

<!-- Horizontal padding/inset of the frame from the recyclerview edges -->
<dimen name="favorites_section_frame_inset">2dp</dimen>
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
val decoration = FavoritesSectionFrameDecoration(
    pinnedSectionItemCount = { adapter.pinnedSectionItemCount() },
    context = this
)
recyclerView.addItemDecoration(decoration)
```

The decoration queries `pinnedSectionItemCount()` to determine the exact range of positions to frame, and applies the border only to that range, leaving the rest of the list (regular countries/servers) unframed.

### Hidden When Empty

If all favorite countries are removed, or if none are currently available in the synced list, the "Favorites" section header and its rows are not displayed. The list defaults to the "All Countries" section. The frame decoration automatically hides as well (when `pinnedSectionItemCount()` returns 0, no range is framed).

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

**Reference:** `docs/qa-evidence/countries-favorites-ui-mobile-manualqa-1.md`

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

## Implementation Checklist for SUB-03 and SUB-04

### For SUB-03 (Servers-in-country screen)

- [ ] Add `FavoritesServerStore` interface (parallel to `FavoritesCountryStore`, wrapping `FavoritesStore.addFavoriteServer/etc`)
- [ ] Wire `FavoritesServerStore` in `CoreDi.kt`
- [ ] Update `CountryServersViewModel` to call `favoritesStore.add/removeFavoriteServer()`
- [ ] Add long-press menu handler in `CountryServersActivity`
- [ ] Replicate pinned "Favorites servers" section at top of servers list
- [ ] Add unit tests following the `ServerListViewModelTest` pattern
- [ ] Add manual E2E test cases parallel to SUB-02 evidence
- [ ] Update `src/docs/favorites-ui-patterns.md` with SUB-03-specific notes if needed

### For SUB-04 (TV D-pad)

- [x] Adapt long-press pattern to D-pad navigation (hold OK/center on a focused row)
- [x] Use a remote-navigable dialog (not a `PopupMenu`) on TV — see "TV D-pad Dialog Pattern"
- [x] Test on an Android TV device (SUB-04 Manual QA on MIBOX4/Android 9 passed all 5 cases; consolidated coverage completed in SUB-05 Manual E2E — SUITE-SUB-05 executed and passed on a real phone + TV)
- [x] Reuse pinned-section logic and filtering from SUB-02/SUB-03 (unchanged; rows already focusable)
- [x] Update `src/docs/favorites-ui-patterns.md` with TV-specific guidance

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
`tests/manual-e2e/stories/SUB-05-favorites-manual-e2e/`). For device setup, `sendevent`-based D-pad long-press
injection (`input keyevent --longpress` delivers a short press on this hardware), and dialog
focus gotchas, see `tests/manual-e2e/environment/android-tv-dpad-qa-runbook.md`.

## Implementation Checklist for SUB-06 (Visual Framing)

- [x] Create `FavoritesSectionFrameDecoration` class extending `RecyclerView.ItemDecoration`
- [x] Implement frame drawing logic using `?attr/colorSecondary` for stroke color (theme-aware)
- [x] Add `pinnedSectionItemCount()` method to `CountryListAdapter` and `ServerPickerAdapter`
- [x] Attach decoration to RecyclerView in `ServerListActivity` (countries screen)
- [x] Attach decoration to RecyclerView in `CountryServersActivity` (servers-in-country screen)
- [x] Add dimen resources: `favorites_section_frame_stroke_width`, `favorites_section_frame_corner_radius`, `favorites_section_frame_inset`
- [x] Test frame visibility on both mobile and TV (shares same code)
- [x] Verify frame hides when pinned section is empty
- [x] Verify frame renders correctly in light and night themes
- [x] Update `src/docs/favorites-ui-patterns.md` with ItemDecoration pattern and theme-aware coloring approach

## Logging Considerations

**Reference:** `src/docs/logging-policy.md`

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

## Related Documents

- `src/docs/server-sync-flow.md` — How server list syncs trigger re-filtering of favorites
- `src/docs/logging-policy.md` — Privacy and logging guidelines
- `CLAUDE.md` — Architecture overview and entry points
- `docs/qa-evidence/favorites-data-layer-gate-1.md` — Data-layer preconditions and residual risks
- `docs/qa-evidence/countries-favorites-ui-mobile-manualqa-1.md` — SUB-02 manual QA evidence and workarounds
- `tests/manual-e2e/environment/android-tv-dpad-qa-runbook.md` — Android TV D-pad QA runbook (Leanback launch, long-press injection, dialog focus)
