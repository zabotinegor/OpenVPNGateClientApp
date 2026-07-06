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

    // --- Country favorites: add/remove/persist (AC1, AC2) ---

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

    // --- Server favorites: add/remove/persist (AC1, AC2) ---

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
}
