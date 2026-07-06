package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context

/**
 * Thin, testable seam over [FavoritesStore]'s country favorites so ViewModels can be
 * unit-tested with a fake implementation instead of requiring an Android [Context] /
 * Robolectric in every test.
 */
interface FavoritesCountryStore {
    fun getFavoriteCountryCodes(): Set<String>
    fun isFavoriteCountry(countryCode: String): Boolean
    fun addFavoriteCountry(countryCode: String)
    fun removeFavoriteCountry(countryCode: String)
}

class DefaultFavoritesCountryStore(private val appContext: Context) : FavoritesCountryStore {
    override fun getFavoriteCountryCodes(): Set<String> =
        FavoritesStore.getFavoriteCountryCodes(appContext)

    override fun isFavoriteCountry(countryCode: String): Boolean =
        FavoritesStore.isFavoriteCountry(appContext, countryCode)

    override fun addFavoriteCountry(countryCode: String) {
        FavoritesStore.addFavoriteCountry(appContext, countryCode)
    }

    override fun removeFavoriteCountry(countryCode: String) {
        FavoritesStore.removeFavoriteCountry(appContext, countryCode)
    }
}
