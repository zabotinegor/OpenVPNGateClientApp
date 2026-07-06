package com.yahorzabotsin.openvpnclientgate.core.servers

/**
 * Pure filtering utilities that intersect persisted favorites ([FavoritesStore]) with a
 * currently synced list. A favorite that is absent from the current list is simply excluded
 * from the result — it is never deleted from persistence. Because filtering is a pure function
 * over the current persisted favorites, a favorite automatically reappears in the result the
 * next time its country/server is present in the input list, with no separate restore step.
 */
object FavoritesFilter {

    /**
     * Returns the subset of [countries] whose [CountryV2.code] is a persisted favorite.
     * Favorite codes that have no match in [countries] are omitted from the result but remain
     * persisted in [FavoritesStore].
     */
    fun filterFavoriteCountries(
        favoriteCountryCodes: Set<String>,
        countries: List<CountryV2>
    ): List<CountryV2> {
        if (favoriteCountryCodes.isEmpty() || countries.isEmpty()) return emptyList()
        return countries.filter { countryV2 ->
            favoriteCountryCodes.any { favoriteCode ->
                favoriteCode.equals(countryV2.code, ignoreCase = true)
            }
        }
    }

    /**
     * Returns the subset of [servers] whose [Server.id] is a persisted favorite.
     * Favorite ids that have no match in [servers] are omitted from the result but remain
     * persisted in [FavoritesStore].
     */
    fun filterFavoriteServers(
        favoriteServerIds: Set<Int>,
        servers: List<Server>
    ): List<Server> {
        if (favoriteServerIds.isEmpty() || servers.isEmpty()) return emptyList()
        return servers.filter { favoriteServerIds.contains(it.id) }
    }
}
