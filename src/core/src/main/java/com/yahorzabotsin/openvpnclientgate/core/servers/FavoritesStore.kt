package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/**
 * Persists favorite country codes and favorite server ids in SharedPreferences.
 *
 * Favorites are stored as sets of identifiers only (country code / server id),
 * not as full domain objects, so they can be trivially checked against a fresh
 * synced list. Filtering favorites against a current list (see [FavoritesFilter])
 * never removes an absent favorite from persistence — it only excludes it from
 * the filtered result — so a favorite automatically reappears once its
 * country/server is present again in a later sync.
 *
 * Country codes are normalized to uppercase (Locale.ROOT) at this store boundary
 * for every add/remove/query operation. This is the single source of truth for
 * casing: callers may pass any casing (e.g. differing casing across synced data
 * sources) and add/remove/isFavoriteCountry/getFavoriteCountryCodes will all agree
 * on favorite state consistently, without relying on every consumer independently
 * normalizing case (see countries-favorites-ui-mobile review finding #1).
 */
object FavoritesStore {
    private const val PREFS_NAME = "favorites_prefs"
    private const val KEY_FAVORITE_COUNTRY_CODES = "favorite_country_codes"
    private const val KEY_FAVORITE_SERVER_IDS = "favorite_server_ids"
    private val favoritesLock = Any()

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun normalizeCountryCode(countryCode: String): String =
        countryCode.uppercase(Locale.ROOT)

    // --- Country favorites ---

    fun addFavoriteCountry(ctx: Context, countryCode: String) {
        if (countryCode.isBlank()) return
        val normalized = normalizeCountryCode(countryCode)
        synchronized(favoritesLock) {
            val current = prefs(ctx).getStringSet(KEY_FAVORITE_COUNTRY_CODES, null)?.toMutableSet() ?: mutableSetOf()
            if (current.add(normalized)) {
                saveFavoriteCountryCodes(ctx, current)
            }
        }
    }

    fun removeFavoriteCountry(ctx: Context, countryCode: String) {
        val normalized = normalizeCountryCode(countryCode)
        synchronized(favoritesLock) {
            val current = prefs(ctx).getStringSet(KEY_FAVORITE_COUNTRY_CODES, null)?.toMutableSet() ?: mutableSetOf()
            if (current.remove(normalized)) {
                saveFavoriteCountryCodes(ctx, current)
            }
        }
    }

    fun isFavoriteCountry(ctx: Context, countryCode: String): Boolean =
        getFavoriteCountryCodes(ctx).contains(normalizeCountryCode(countryCode))

    fun getFavoriteCountryCodes(ctx: Context): Set<String> =
        prefs(ctx).getStringSet(KEY_FAVORITE_COUNTRY_CODES, emptySet())?.toSet() ?: emptySet()

    private fun saveFavoriteCountryCodes(ctx: Context, codes: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY_FAVORITE_COUNTRY_CODES, codes).apply()
    }

    // --- Server favorites ---

    fun addFavoriteServer(ctx: Context, serverId: Int) {
        if (serverId <= 0) return
        synchronized(favoritesLock) {
            val current = prefs(ctx).getStringSet(KEY_FAVORITE_SERVER_IDS, null)
                ?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
            if (current.add(serverId)) {
                saveFavoriteServerIds(ctx, current)
            }
        }
    }

    fun removeFavoriteServer(ctx: Context, serverId: Int) {
        synchronized(favoritesLock) {
            val current = prefs(ctx).getStringSet(KEY_FAVORITE_SERVER_IDS, null)
                ?.mapNotNull { it.toIntOrNull() }?.toMutableSet() ?: mutableSetOf()
            if (current.remove(serverId)) {
                saveFavoriteServerIds(ctx, current)
            }
        }
    }

    fun isFavoriteServer(ctx: Context, serverId: Int): Boolean =
        getFavoriteServerIds(ctx).contains(serverId)

    fun getFavoriteServerIds(ctx: Context): Set<Int> =
        prefs(ctx).getStringSet(KEY_FAVORITE_SERVER_IDS, emptySet())
            ?.mapNotNull { it.toIntOrNull() }
            ?.toSet()
            ?: emptySet()

    private fun saveFavoriteServerIds(ctx: Context, ids: Set<Int>) {
        prefs(ctx).edit()
            .putStringSet(KEY_FAVORITE_SERVER_IDS, ids.map { it.toString() }.toSet())
            .apply()
    }
}
