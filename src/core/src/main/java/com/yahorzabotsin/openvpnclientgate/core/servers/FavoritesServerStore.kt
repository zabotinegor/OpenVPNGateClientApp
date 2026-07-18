package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context

/**
 * Thin, testable seam over [FavoritesStore]'s server favorites so ViewModels can be
 * unit-tested with a fake implementation instead of requiring an Android [Context] /
 * Robolectric in every test. Mirrors [FavoritesCountryStore]'s shape for the country side.
 */
interface FavoritesServerStore {
    fun getFavoriteServerIds(): Set<Int>
    fun isFavoriteServer(serverId: Int): Boolean
    fun addFavoriteServer(serverId: Int)
    fun removeFavoriteServer(serverId: Int)
}

class DefaultFavoritesServerStore(private val appContext: Context) : FavoritesServerStore {
    override fun getFavoriteServerIds(): Set<Int> =
        FavoritesStore.getFavoriteServerIds(appContext)

    override fun isFavoriteServer(serverId: Int): Boolean =
        FavoritesStore.isFavoriteServer(appContext, serverId)

    override fun addFavoriteServer(serverId: Int) {
        require(serverId > 0) { "Server ID must be greater than 0 to be favorited" }
        FavoritesStore.addFavoriteServer(appContext, serverId)
    }

    override fun removeFavoriteServer(serverId: Int) {
        FavoritesStore.removeFavoriteServer(appContext, serverId)
    }
}
