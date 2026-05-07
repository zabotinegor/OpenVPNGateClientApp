package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import com.google.gson.Gson
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Fetches and caches v2 country and server lists.
 *
 * Cache strategy mirrors [ServerRepository]:
 * - Countries cached in SharedPrefs timestamp + file `v2_countries.json`.
 * - Servers per country cached in `v2_servers_<code>.json`, keyed by country code.
 */
class ServersV2Repository(
    private val api: ServersV2Api,
    private val settingsStore: UserSettingsStore = UserSettingsStore,
    private val countriesMutex: Mutex = Mutex(),
    private val serversMutex: Mutex = Mutex()
) {

    private companion object {
        private val TAG = LogTags.APP + ":ServersV2Repository"
        private const val CACHE_PREFS = "servers_v2_cache"
        private const val KEY_COUNTRIES_TS = "ts_countries"
        private const val COUNTRIES_CACHE_FILE = "v2_countries.json"
        private const val SERVERS_CACHE_FILE_PREFIX = "v2_servers_"
        private const val SERVERS_CACHE_FILE_SUFFIX = ".json"
        private const val PAGE_SIZE = 50

        private fun serversCacheFile(ctx: Context, countryCode: String): File =
            File(ctx.cacheDir, "$SERVERS_CACHE_FILE_PREFIX${countryCode.lowercase()}$SERVERS_CACHE_FILE_SUFFIX")

        private fun countriesCacheFile(ctx: Context): File =
            File(ctx.cacheDir, COUNTRIES_CACHE_FILE)

        private fun parseCountries(json: String): List<CountryV2> =
            Gson().fromJson(json, Array<CountryV2>::class.java).toList()

        private fun parseServers(json: String): List<ServerV2> =
            Gson().fromJson(json, Array<ServerV2>::class.java).filter { s ->
                if (s.configData.isBlank()) {
                    AppLog.w(TAG, "Server ${s.ip} has empty configData — skipping")
                    false
                } else true
            }
    }

    /**
     * Returns the cached country list, fetching from network if cache is absent or expired.
     *
     * @param forceRefresh ignore cache and fetch fresh data
     * @param cacheOnly never make a network call; throws [IOException] if cache is absent
     */
    suspend fun getCountries(
        context: Context,
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    ): List<CountryV2> = countriesMutex.withLock {
        val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        fetchWithCache(
            cacheFile = countriesCacheFile(context),
            tsKey = KEY_COUNTRIES_TS,
            prefs = prefs,
            cacheTtlMs = settingsStore.load(context).cacheTtlMs,
            forceRefresh = forceRefresh,
            cacheOnly = cacheOnly,
            logPrefix = "getCountries",
            parse = ::parseCountries,
            fetchNetwork = { Gson().toJson(api.getCountries()) }
        )
    }

    /**
     * Returns all servers for the given country code, fetching all pages if `serverCount > 50`.
     * Servers with empty `configData` are filtered out.
     *
     * @param forceRefresh ignore cache and fetch fresh data
     * @param cacheOnly never make a network call; throws [IOException] if cache is absent
     */
    suspend fun getServersForCountry(
        context: Context,
        countryCode: String,
        serverCount: Int,
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    ): List<ServerV2> = serversMutex.withLock {
        val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val cacheKey = "ts_servers_${countryCode.lowercase()}"
        AppLog.d(TAG, "getServersForCountry[$countryCode]: serverCount=$serverCount")
        fetchWithCache(
            cacheFile = serversCacheFile(context, countryCode),
            tsKey = cacheKey,
            prefs = prefs,
            cacheTtlMs = settingsStore.load(context).cacheTtlMs,
            forceRefresh = forceRefresh,
            cacheOnly = cacheOnly,
            logPrefix = "getServersForCountry[$countryCode]",
            parse = ::parseServers,
            fetchNetwork = { Gson().toJson(fetchAllPages(countryCode, serverCount)) }
        )
    }

    private suspend fun <T> fetchWithCache(
        cacheFile: File,
        tsKey: String,
        prefs: SharedPreferences,
        cacheTtlMs: Long,
        forceRefresh: Boolean,
        cacheOnly: Boolean,
        logPrefix: String,
        parse: (String) -> List<T>,
        fetchNetwork: suspend () -> String
    ): List<T> {
        val ts = prefs.getLong(tsKey, -1L)
        val cacheValid = !forceRefresh && ts > 0L && cacheFile.isFile &&
                (System.currentTimeMillis() - ts) < cacheTtlMs

        if (cacheValid) {
            AppLog.d(TAG, "$logPrefix: cache hit")
            return withContext(Dispatchers.IO) { parse(cacheFile.readText()) }
        }

        if (cacheOnly) {
            if (cacheFile.isFile) {
                AppLog.d(TAG, "$logPrefix: cacheOnly, reading stale cache")
                return withContext(Dispatchers.IO) { parse(cacheFile.readText()) }
            }
            throw IOException("$logPrefix: cacheOnly=true but no cache available")
        }

        AppLog.d(TAG, "$logPrefix: fetching from network")
        return try {
            val json = fetchNetwork()
            val parsed = parse(json)
            withContext(Dispatchers.IO) { cacheFile.writeText(json) }
            prefs.edit().putLong(tsKey, System.currentTimeMillis()).apply()
            parsed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "$logPrefix: network failure", e)
            if (cacheFile.isFile) {
                AppLog.d(TAG, "$logPrefix: falling back to stale cache after network error")
                withContext(Dispatchers.IO) { parse(cacheFile.readText()) }
            } else {
                throw IOException("$logPrefix: network failed and no cache available", e)
            }
        }
    }

    private suspend fun fetchAllPages(countryCode: String, serverCount: Int): List<ServerV2> {
        val result = mutableListOf<ServerV2>()
        var skip = 0
        while (true) {
            val page = api.getServers(
                countryCode = countryCode,
                isActive = true,
                skip = skip,
                take = PAGE_SIZE
            )
            // The v2 API returns {"items":[...], "total":..., ...}.
            // Use raw count (before configData filtering) to decide whether more pages exist.
            // Filtered count can be < PAGE_SIZE even on a full page if some servers have blank
            // configData, which would cause the loop to terminate too early.
            val items = page.items
                ?: throw IOException("fetchAllPages[$countryCode]: missing 'items' in response")
            val rawPageSize = items.size
            result += items
            if (rawPageSize < PAGE_SIZE || result.size >= serverCount) break
            skip += PAGE_SIZE
        }
        AppLog.d(TAG, "fetchAllPages[$countryCode]: fetched ${result.size} servers")
        return result
    }

    /** Clears the countries cache (timestamp only; file left until overwritten). */
    fun clearCountriesCache(context: Context) {
        context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE).edit()
            .remove(KEY_COUNTRIES_TS)
            .apply()
    }

}
