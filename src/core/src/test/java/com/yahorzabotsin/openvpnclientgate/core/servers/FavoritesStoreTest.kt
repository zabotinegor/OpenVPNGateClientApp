package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class FavoritesStoreTest {

    private fun freshContext(): Context {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        return ctx
    }

    // --- Country favorites: add/remove/persist ---

    @Test
    fun addFavoriteCountry_persistsAndIsQueryable() {
        val ctx = freshContext()

        FavoritesStore.addFavoriteCountry(ctx, "US")

        assertTrue(FavoritesStore.isFavoriteCountry(ctx, "US"))
        assertEquals(setOf("US"), FavoritesStore.getFavoriteCountryCodes(ctx))
    }

    @Test
    fun addFavoriteCountry_survivesAcrossStoreInstances_simulatingRestart() {
        val ctx = freshContext()
        FavoritesStore.addFavoriteCountry(ctx, "DE")

        // Re-read from a "new" prefs handle backed by the same Context store, simulating restart.
        val stillFavorite = FavoritesStore.isFavoriteCountry(ctx, "DE")

        assertTrue(stillFavorite)
    }

    @Test
    fun removeFavoriteCountry_removesFromPersistence() {
        val ctx = freshContext()
        FavoritesStore.addFavoriteCountry(ctx, "US")
        FavoritesStore.addFavoriteCountry(ctx, "DE")

        FavoritesStore.removeFavoriteCountry(ctx, "US")

        assertFalse(FavoritesStore.isFavoriteCountry(ctx, "US"))
        assertTrue(FavoritesStore.isFavoriteCountry(ctx, "DE"))
        assertEquals(setOf("DE"), FavoritesStore.getFavoriteCountryCodes(ctx))
    }

    @Test
    fun isFavoriteCountry_falseWhenNeverAdded() {
        val ctx = freshContext()

        assertFalse(FavoritesStore.isFavoriteCountry(ctx, "JP"))
    }

    @Test
    fun addFavoriteCountry_blankCodeIgnored() {
        val ctx = freshContext()

        FavoritesStore.addFavoriteCountry(ctx, "")

        assertTrue(FavoritesStore.getFavoriteCountryCodes(ctx).isEmpty())
    }

    // --- Country favorites: case-insensitivity regression ---
    //
    // ServerListViewModel.buildItems() matches favorites case-insensitively when rendering
    // the pinned favorites section, but toggleFavorite() decides add-vs-remove via
    // FavoritesStore.isFavoriteCountry. If the store compared case-sensitively while the
    // display filter was case-insensitive, a country shown as favorited (uppercase match)
    // could be evaluated as "not currently favorite" on long-press (exact-casing miss),
    // causing it to be re-added under a different casing instead of removed. FavoritesStore
    // now normalizes country codes to uppercase at the add/remove/query boundary so all
    // operations agree on favorite state regardless of the casing passed in.

    @Test
    fun isFavoriteCountry_matchesRegardlessOfCasingUsedToAdd() {
        val ctx = freshContext()
        FavoritesStore.addFavoriteCountry(ctx, "us")

        assertTrue(FavoritesStore.isFavoriteCountry(ctx, "US"))
        assertTrue(FavoritesStore.isFavoriteCountry(ctx, "Us"))
        assertTrue(FavoritesStore.isFavoriteCountry(ctx, "us"))
    }

    @Test
    fun addFavoriteCountry_differentCasingDoesNotCreateDuplicateEntries() {
        val ctx = freshContext()
        FavoritesStore.addFavoriteCountry(ctx, "us")

        // A later sync surfaces the same logical country with different casing (e.g. "US").
        // Adding it again (simulating a mismatched toggle) must not create a second entry.
        FavoritesStore.addFavoriteCountry(ctx, "US")

        assertEquals(setOf("US"), FavoritesStore.getFavoriteCountryCodes(ctx))
    }

    @Test
    fun removeFavoriteCountry_removesEntryAddedWithDifferentCasing() {
        val ctx = freshContext()
        FavoritesStore.addFavoriteCountry(ctx, "us")

        // Toggle-to-remove arrives with the casing supplied by a fresh sync ("US"), not the
        // original casing used when the favorite was added ("us"). This must still remove it,
        // reproducing the exact mismatched-casing toggle scenario from the review finding.
        FavoritesStore.removeFavoriteCountry(ctx, "US")

        assertFalse(FavoritesStore.isFavoriteCountry(ctx, "us"))
        assertFalse(FavoritesStore.isFavoriteCountry(ctx, "US"))
        assertTrue(FavoritesStore.getFavoriteCountryCodes(ctx).isEmpty())
    }

    @Test
    fun getFavoriteCountryCodes_alwaysReturnsUppercaseNormalizedCodes() {
        val ctx = freshContext()
        FavoritesStore.addFavoriteCountry(ctx, "de")
        FavoritesStore.addFavoriteCountry(ctx, "fr")

        assertEquals(setOf("DE", "FR"), FavoritesStore.getFavoriteCountryCodes(ctx))
    }

    @Test
    fun getFavoriteCountryCodes_normalizesLegacyLowercaseEntriesOnRead() {
        val ctx = freshContext()
        // Simulate a legacy lowercase entry that was stored before normalization was enforced.
        val prefs = ctx.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorite_country_codes", setOf("us", "de")).apply()

        // Reading should return the normalized uppercase set.
        assertEquals(setOf("US", "DE"), FavoritesStore.getFavoriteCountryCodes(ctx))

        // The raw prefs should have been migrated and persisted back.
        assertEquals(setOf("US", "DE"), FavoritesStore.getFavoriteCountryCodes(ctx))
    }

    @Test
    fun isFavoriteCountry_matchesLegacyLowercaseStoredEntry() {
        val ctx = freshContext()
        // Simulate a legacy lowercase entry.
        val prefs = ctx.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorite_country_codes", setOf("us")).apply()

        // Query with uppercase should find the legacy lowercase entry (after normalization on read).
        assertTrue(FavoritesStore.isFavoriteCountry(ctx, "US"))
    }

    @Test
    fun removeFavoriteCountry_removesLegacyLowercaseStoredEntry() {
        val ctx = freshContext()
        // Simulate a legacy lowercase entry.
        val prefs = ctx.getSharedPreferences("favorites_prefs", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("favorite_country_codes", setOf("us")).apply()

        // Remove using uppercase should remove the legacy lowercase entry.
        FavoritesStore.removeFavoriteCountry(ctx, "US")

        // Verify it's gone.
        assertFalse(FavoritesStore.isFavoriteCountry(ctx, "US"))
        assertTrue(FavoritesStore.getFavoriteCountryCodes(ctx).isEmpty())
    }

    // --- Server favorites: add/remove/persist ---

    @Test
    fun addFavoriteServer_persistsAndIsQueryable() {
        val ctx = freshContext()

        FavoritesStore.addFavoriteServer(ctx, 42)

        assertTrue(FavoritesStore.isFavoriteServer(ctx, 42))
        assertEquals(setOf(42), FavoritesStore.getFavoriteServerIds(ctx))
    }

    @Test
    fun addFavoriteServer_rejectsNonPositiveId() {
        val ctx = freshContext()

        FavoritesStore.addFavoriteServer(ctx, 0)
        FavoritesStore.addFavoriteServer(ctx, -1)

        assertTrue(FavoritesStore.getFavoriteServerIds(ctx).isEmpty())
    }

    @Test
    fun addFavoriteServer_survivesAcrossStoreInstances_simulatingRestart() {
        val ctx = freshContext()
        FavoritesStore.addFavoriteServer(ctx, 7)

        val stillFavorite = FavoritesStore.isFavoriteServer(ctx, 7)

        assertTrue(stillFavorite)
    }

    @Test
    fun removeFavoriteServer_removesFromPersistence() {
        val ctx = freshContext()
        FavoritesStore.addFavoriteServer(ctx, 1)
        FavoritesStore.addFavoriteServer(ctx, 2)

        FavoritesStore.removeFavoriteServer(ctx, 1)

        assertFalse(FavoritesStore.isFavoriteServer(ctx, 1))
        assertTrue(FavoritesStore.isFavoriteServer(ctx, 2))
        assertEquals(setOf(2), FavoritesStore.getFavoriteServerIds(ctx))
    }

    @Test
    fun isFavoriteServer_falseWhenNeverAdded() {
        val ctx = freshContext()

        assertFalse(FavoritesStore.isFavoriteServer(ctx, 99))
    }

    @Test
    fun addFavoriteServer_duplicateAddIsIdempotent() {
        val ctx = freshContext()

        FavoritesStore.addFavoriteServer(ctx, 5)
        FavoritesStore.addFavoriteServer(ctx, 5)

        assertEquals(setOf(5), FavoritesStore.getFavoriteServerIds(ctx))
    }

    // --- DefaultFavoritesServerStore: defense-in-depth require(serverId > 0) guard ---
    //
    // The ViewModel already filters out servers with id <= 0 before toggling, but the store
    // facade guards independently (documented in docs/runbooks/solutions.md) so unexpected
    // call sites cannot persist invalid IDs. These tests exercise the REAL production class
    // (round-5 review: an earlier version asserted on a test-local anonymous impl, which was
    // vacuous).

    @Test
    fun defaultFavoritesServerStore_addFavoriteServer_persistsValidId() {
        val store = DefaultFavoritesServerStore(freshContext())

        store.addFavoriteServer(10)

        assertTrue(store.isFavoriteServer(10))
        assertEquals(setOf(10), store.getFavoriteServerIds())
    }

    @Test(expected = IllegalArgumentException::class)
    fun defaultFavoritesServerStore_addFavoriteServer_zeroIdThrows() {
        DefaultFavoritesServerStore(freshContext()).addFavoriteServer(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun defaultFavoritesServerStore_addFavoriteServer_negativeIdThrows() {
        DefaultFavoritesServerStore(freshContext()).addFavoriteServer(-1)
    }

    @Test
    fun defaultFavoritesServerStore_removeAndQuery_delegateWithoutGuard() {
        // Production code intentionally guards only addFavoriteServer; remove and query are
        // plain delegations and must not throw for non-positive IDs.
        val store = DefaultFavoritesServerStore(freshContext())
        store.addFavoriteServer(7)

        assertFalse(store.isFavoriteServer(0))
        store.removeFavoriteServer(0)
        store.removeFavoriteServer(-1)

        assertTrue(store.isFavoriteServer(7))
        store.removeFavoriteServer(7)
        assertFalse(store.isFavoriteServer(7))
        assertTrue(store.getFavoriteServerIds().isEmpty())
    }
}
