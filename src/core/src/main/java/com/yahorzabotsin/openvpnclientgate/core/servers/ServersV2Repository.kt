package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import android.content.Context.MODE_PRIVATE
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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

        private fun parseCountries(json: String): List<CountryV2> {
            val arr = JSONArray(json)
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                CountryV2(
                    code = o.getString("code"),
                    name = o.getString("name"),
                    serverCount = o.optInt("serverCount", 0)
                )
            }
        }

        private fun parseServers(json: String): List<ServerV2> {
            val arr = JSONArray(json)
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val configData = o.optString("configData", "")
                if (configData.isBlank()) {
                    AppLog.w(TAG, "Server at index $i has empty configData — skipping")
                    return@mapNotNull null
                }
                ServerV2(
                    ip = o.optString("ip", ""),
                    countryCode = o.optString("countryCode", ""),
                    countryName = o.optString("countryName", ""),
                    configData = configData
                )
            }
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
        val cacheTtl = settingsStore.load(context).cacheTtlMs
        val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val ts = prefs.getLong(KEY_COUNTRIES_TS, -1L)
        val cacheFile = countriesCacheFile(context)
        val cacheValid = !forceRefresh && ts > 0L && cacheFile.isFile &&
                (System.currentTimeMillis() - ts) < cacheTtl

        if (cacheValid) {
            AppLog.d(TAG, "getCountries: cache hit")
            return@withLock withContext(Dispatchers.IO) {
                parseCountries(cacheFile.readText())
            }
        }

        if (cacheOnly) {
            if (cacheFile.isFile) {
                AppLog.d(TAG, "getCountries: cacheOnly, reading stale cache")
                return@withLock withContext(Dispatchers.IO) {
                    parseCountries(cacheFile.readText())
                }
            }
            throw IOException("getCountries: cacheOnly=true but no cache available")
        }

        AppLog.d(TAG, "getCountries: fetching from network")
        return@withLock try {
            val body = api.getCountries()
            val json = withContext(Dispatchers.IO) { body.string() }
            val countries = parseCountries(json)
            withContext(Dispatchers.IO) { cacheFile.writeText(json) }
            prefs.edit().putLong(KEY_COUNTRIES_TS, System.currentTimeMillis()).apply()
            countries
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "getCountries: network failure", e)
            if (cacheFile.isFile) {
                AppLog.d(TAG, "getCountries: falling back to stale cache after network error")
                withContext(Dispatchers.IO) { parseCountries(cacheFile.readText()) }
            } else {
                throw IOException("getCountries: network failed and no cache available", e)
            }
        }
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
        val cacheTtl = settingsStore.load(context).cacheTtlMs
        val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val cacheKey = "ts_servers_${countryCode.lowercase()}"
        val ts = prefs.getLong(cacheKey, -1L)
        val cacheFile = serversCacheFile(context, countryCode)
        val cacheValid = !forceRefresh && ts > 0L && cacheFile.isFile &&
                (System.currentTimeMillis() - ts) < cacheTtl

        if (cacheValid) {
            AppLog.d(TAG, "getServersForCountry[$countryCode]: cache hit")
            return@withLock withContext(Dispatchers.IO) {
                parseServers(cacheFile.readText())
            }
        }

        if (cacheOnly) {
            if (cacheFile.isFile) {
                AppLog.d(TAG, "getServersForCountry[$countryCode]: cacheOnly, reading stale cache")
                return@withLock withContext(Dispatchers.IO) {
                    parseServers(cacheFile.readText())
                }
            }
            throw IOException("getServersForCountry[$countryCode]: cacheOnly=true but no cache available")
        }

        AppLog.d(TAG, "getServersForCountry[$countryCode]: fetching from network (serverCount=$serverCount)")
        return@withLock try {
            val allServers = fetchAllPages(countryCode, serverCount)
            val json = serversToJson(allServers)
            withContext(Dispatchers.IO) { cacheFile.writeText(json) }
            prefs.edit().putLong(cacheKey, System.currentTimeMillis()).apply()
            allServers
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "getServersForCountry[$countryCode]: network failure", e)
            if (cacheFile.isFile) {
                AppLog.d(TAG, "getServersForCountry[$countryCode]: falling back to stale cache")
                withContext(Dispatchers.IO) { parseServers(cacheFile.readText()) }
            } else {
                throw IOException("getServersForCountry[$countryCode]: network failed and no cache available", e)
            }
        }
    }

    private suspend fun fetchAllPages(countryCode: String, serverCount: Int): List<ServerV2> {
        val result = mutableListOf<ServerV2>()
        var skip = 0
        while (true) {
            val body = api.getServers(
                countryCode = countryCode,
                isActive = true,
                skip = skip,
                take = PAGE_SIZE
            )
            val pageJson = withContext(Dispatchers.IO) { body.string() }
            // The v2 API returns {"items":[...], "total":..., "page":..., "pageSize":...}.
            // Use raw count (before configData filtering) to decide whether more pages exist.
            // Filtered count can be < PAGE_SIZE even on a full page if some servers have blank
            // configData, which would cause the loop to terminate too early.
            val itemsArray = JSONObject(pageJson).getJSONArray("items")
            val rawPageSize = itemsArray.length()
            val page = parseServers(itemsArray.toString())
            result += page
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

    private fun serversToJson(servers: List<ServerV2>): String {
        val arr = JSONArray()
        servers.forEach { s ->
            arr.put(
                JSONObject()
                    .put("ip", s.ip)
                    .put("countryCode", s.countryCode)
                    .put("countryName", s.countryName)
                    .put("configData", s.configData)
            )
        }
        return arr.toString()
    }
}
