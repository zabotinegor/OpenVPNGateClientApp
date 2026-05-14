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
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches and caches v2 country and server lists.
 *
 * Cache strategy mirrors [ServerRepository]:
 * - Countries cached per locale in SharedPrefs timestamp + file `v2_countries_<locale>.json`.
 * - Servers cached per country+locale in `v2_servers_<code>_<locale>.json`.
 */
class ServersV2Repository(
    private val api: ServersV2Api,
    private val settingsStore: UserSettingsStore = UserSettingsStore,
    private val countriesMutex: Mutex = Mutex(),
    private val serversMutexMap: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()
) {

    private companion object {
        private val TAG = LogTags.APP + ":ServersV2Repository"
        private const val CACHE_PREFS = "servers_v2_cache"
        private const val KEY_COUNTRIES_TS_LEGACY = "ts_countries"
        private const val KEY_COUNTRIES_TS_PREFIX = "ts_countries_"
        private const val KEY_SERVERS_TS_PREFIX = "ts_servers_"
        private const val COUNTRIES_CACHE_FILE_LEGACY = "v2_countries.json"
        private const val COUNTRIES_CACHE_FILE_PREFIX = "v2_countries_"
        private const val SERVERS_CACHE_FILE_PREFIX = "v2_servers_"
        private const val SERVERS_CACHE_FILE_SUFFIX = ".json"
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES_SAFETY_LIMIT = 200

        private fun normalizeCountryCode(countryCode: String): String =
            countryCode.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

        private fun normalizeLocale(locale: String): String =
            locale.trim().lowercase(Locale.ROOT).ifBlank { "en" }

        private fun serversCacheFile(ctx: Context, countryCode: String, locale: String): File {
            val normalizedCountryCode = normalizeCountryCode(countryCode)
            val normalizedLocale = normalizeLocale(locale)
            return File(
                ctx.cacheDir,
                "$SERVERS_CACHE_FILE_PREFIX${normalizedCountryCode}_${normalizedLocale}$SERVERS_CACHE_FILE_SUFFIX"
            )
        }

        private fun serversTimestampKey(countryCode: String, locale: String): String =
            "$KEY_SERVERS_TS_PREFIX${normalizeCountryCode(countryCode)}_${normalizeLocale(locale)}"

        private fun countriesCacheFile(ctx: Context, locale: String): File =
            File(ctx.cacheDir, "$COUNTRIES_CACHE_FILE_PREFIX${normalizeLocale(locale)}$SERVERS_CACHE_FILE_SUFFIX")

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
        val locale = settingsStore.resolvePreferredLocale(context)
        val normalizedLocale = normalizeLocale(locale)
        migrateLegacyCountriesCacheIfNeeded(context, prefs, normalizedLocale)
        fetchWithCache(
            cacheFile = countriesCacheFile(context, normalizedLocale),
            tsKey = "$KEY_COUNTRIES_TS_PREFIX$normalizedLocale",
            prefs = prefs,
            cacheTtlMs = settingsStore.load(context).cacheTtlMs,
            forceRefresh = forceRefresh,
            cacheOnly = cacheOnly,
            logPrefix = "getCountries[locale=$normalizedLocale]",
            parse = ::parseCountries,
            fetchNetwork = { Gson().toJson(api.getCountries(locale = normalizedLocale)) }
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
    ): List<ServerV2> {
        val locale = resolvePreferredLocale(context)
        val normalizedLocale = normalizeLocale(locale)
        val normalizedCountryCode = normalizeCountryCode(countryCode)
        val lockKey = "${normalizedCountryCode}|$normalizedLocale"
        val mutex = serversMutexMap.computeIfAbsent(lockKey) { Mutex() }
        return mutex.withLock {
            val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
            val cacheKey = serversTimestampKey(normalizedCountryCode, normalizedLocale)
            migrateLegacyServersCacheIfNeeded(context, prefs, normalizedCountryCode, normalizedLocale)
            AppLog.d(
                TAG,
                "getServersForCountry[$countryCode]: serverCount=$serverCount locale=$normalizedLocale"
            )
            fetchWithCache(
                cacheFile = serversCacheFile(context, countryCode, normalizedLocale),
                tsKey = cacheKey,
                prefs = prefs,
                cacheTtlMs = settingsStore.load(context).cacheTtlMs,
                forceRefresh = forceRefresh,
                cacheOnly = cacheOnly,
                logPrefix = "getServersForCountry[$countryCode][$normalizedLocale]",
                parse = ::parseServers,
                fetchNetwork = { Gson().toJson(fetchAllPages(countryCode, serverCount, normalizedLocale)) }
            )
        }
    }

    private fun resolvePreferredLocale(context: Context): String {
        return settingsStore.resolvePreferredLocale(context)
    }

    private fun migrateLegacyCountriesCacheIfNeeded(
        context: Context,
        prefs: SharedPreferences,
        normalizedLocale: String
    ) {
        val localizedFile = countriesCacheFile(context, normalizedLocale)
        val localizedTsKey = "$KEY_COUNTRIES_TS_PREFIX$normalizedLocale"
        val hasLocalizedTimestamp = prefs.contains(localizedTsKey)
        if (localizedFile.isFile || hasLocalizedTimestamp) {
            return
        }

        val legacyFile = File(context.cacheDir, COUNTRIES_CACHE_FILE_LEGACY)
        if (!legacyFile.isFile) {
            return
        }

        runCatching {
            legacyFile.copyTo(localizedFile, overwrite = false)
        }.onSuccess {
            val legacyTimestamp = prefs.getLong(KEY_COUNTRIES_TS_LEGACY, -1L)
            if (legacyTimestamp > 0L) {
                prefs.edit().putLong(localizedTsKey, legacyTimestamp).apply()
            }
            AppLog.d(TAG, "migrateLegacyCountriesCacheIfNeeded: migrated legacy cache to locale=$normalizedLocale")
        }.onFailure {
            AppLog.w(TAG, "migrateLegacyCountriesCacheIfNeeded: migration failed for locale=$normalizedLocale", it)
        }
    }

    private fun migrateLegacyServersCacheIfNeeded(
        context: Context,
        prefs: SharedPreferences,
        normalizedCountryCode: String,
        normalizedLocale: String
    ) {
        val localizedFile = serversCacheFile(context, normalizedCountryCode, normalizedLocale)
        val localizedTsKey = serversTimestampKey(normalizedCountryCode, normalizedLocale)
        val hasLocalizedTimestamp = prefs.contains(localizedTsKey)
        if (localizedFile.isFile || hasLocalizedTimestamp) {
            return
        }

        val legacyFile = File(
            context.cacheDir,
            "$SERVERS_CACHE_FILE_PREFIX${normalizedCountryCode}$SERVERS_CACHE_FILE_SUFFIX"
        )
        if (!legacyFile.isFile) {
            return
        }

        runCatching {
            legacyFile.copyTo(localizedFile, overwrite = false)
        }.onSuccess {
            val legacyTsKey = "$KEY_SERVERS_TS_PREFIX${normalizedCountryCode}"
            val legacyTimestamp = prefs.getLong(legacyTsKey, -1L)
            if (legacyTimestamp > 0L) {
                prefs.edit().putLong(localizedTsKey, legacyTimestamp).apply()
            }
            AppLog.d(
                TAG,
                "migrateLegacyServersCacheIfNeeded: migrated legacy cache for country=$normalizedCountryCode locale=$normalizedLocale"
            )
        }.onFailure {
            AppLog.w(
                TAG,
                "migrateLegacyServersCacheIfNeeded: migration failed for country=$normalizedCountryCode locale=$normalizedLocale",
                it
            )
        }
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
            return try {
                withContext(Dispatchers.IO) { parse(cacheFile.readText()) }
            } catch (e: Exception) {
                AppLog.w(TAG, "$logPrefix: cache parse error", e)
                cacheFile.delete()
                prefs.edit().remove(tsKey).apply()
                if (cacheOnly) {
                    throw IOException("$logPrefix: cache parse error (cacheOnly=true, network disabled)", e)
                }
                // Fall through to network fetch below for non-cacheOnly mode.
                fetchFromNetworkWithParsing(logPrefix, cacheFile, tsKey, prefs, cacheTtlMs, parse, fetchNetwork)
            }
        }

        if (cacheOnly) {
            if (cacheFile.isFile) {
                AppLog.d(TAG, "$logPrefix: cacheOnly, reading stale cache")
                return try {
                    withContext(Dispatchers.IO) { parse(cacheFile.readText()) }
                } catch (e: Exception) {
                    throw IOException("$logPrefix: cache parse error (corrupted file)", e)
                }
            }
            throw IOException("$logPrefix: cacheOnly=true but no cache available")
        }

        AppLog.d(TAG, "$logPrefix: fetching from network")
        return fetchFromNetworkWithParsing(logPrefix, cacheFile, tsKey, prefs, cacheTtlMs, parse, fetchNetwork)
    }

    private suspend fun <T> fetchFromNetworkWithParsing(
        logPrefix: String,
        cacheFile: File,
        tsKey: String,
        prefs: SharedPreferences,
        cacheTtlMs: Long,
        parse: (String) -> List<T>,
        fetchNetwork: suspend () -> String
    ): List<T> {
        return try {
            val json = withContext(Dispatchers.IO) { fetchNetwork() }
            val parsed = withContext(Dispatchers.Default) { parse(json) }
            withContext(Dispatchers.IO) { cacheFile.writeText(json) }
            prefs.edit().putLong(tsKey, System.currentTimeMillis()).apply()
            parsed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "$logPrefix: network fetch or parse failure (${e.javaClass.simpleName})", e)
            if (cacheFile.isFile) {
                AppLog.d(TAG, "$logPrefix: falling back to stale cache after network error")
                try {
                    withContext(Dispatchers.IO) { parse(cacheFile.readText()) }
                } catch (parseError: Exception) {
                    throw IOException("$logPrefix: network failed and cache is corrupted", parseError)
                }
            } else {
                throw IOException("$logPrefix: network failed and no cache available", e)
            }
        }
    }

    private suspend fun fetchAllPages(
        countryCode: String,
        serverCount: Int,
        locale: String
    ): List<ServerV2> {
        val result = mutableListOf<ServerV2>()
        var skip = 0
        var rawFetched = 0
        var pagesFetched = 0
        val serverCountBound = serverCount.coerceAtLeast(0)
        while (true) {
            val page = api.getServers(
                locale = locale,
                countryCode = countryCode,
                isActive = true,
                skip = skip,
                take = PAGE_SIZE
            )
            pagesFetched += 1
            // The v2 API returns {"items":[...], "total":N}.
            // Use raw page count (before configData filtering) for the partial-page exit
            // so a full page that happens to contain blank entries does not stop pagination early.
            // If the API supplies a reliable total, also stop when all items have been fetched.
            // When total is missing/zero, use serverCount fallback to prevent unbounded pagination.
            val items = page.items
                ?: throw IOException("fetchAllPages[$countryCode]: missing 'items' in response")
            val rawPageSize = items.size
            rawFetched += rawPageSize
            // Filter blank configData before accumulating so the cache stays clean.
            result += items.filter { it.configData.isNotBlank() }
            val reachedApiTotal = page.total > 0 && rawFetched >= page.total
            val reachedServerCountFallback = page.total <= 0 && serverCountBound > 0 && rawFetched >= serverCountBound
            val reachedSafetyLimit = pagesFetched >= MAX_PAGES_SAFETY_LIMIT
            if (rawPageSize < PAGE_SIZE || reachedApiTotal || reachedServerCountFallback || reachedSafetyLimit) {
                if (reachedSafetyLimit) {
                    AppLog.w(
                        TAG,
                        "fetchAllPages[$countryCode]: stopped by safety page limit ($MAX_PAGES_SAFETY_LIMIT)"
                    )
                }
                break
            }
            skip += PAGE_SIZE
        }
        AppLog.d(TAG, "fetchAllPages[$countryCode]: fetched ${result.size} servers (raw=$rawFetched)")
        return result
    }

    /** Clears the countries cache (timestamp only; file left until overwritten). */
    fun clearCountriesCache(context: Context) {
        context.cacheDir.listFiles()?.filter {
            (it.name == COUNTRIES_CACHE_FILE_LEGACY || it.name.startsWith(COUNTRIES_CACHE_FILE_PREFIX)) &&
                    it.name.endsWith(SERVERS_CACHE_FILE_SUFFIX)
        }?.forEach { it.delete() }
        val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val keysToRemove = prefs.all.keys.filter { it == KEY_COUNTRIES_TS_LEGACY || it.startsWith(KEY_COUNTRIES_TS_PREFIX) }
        prefs.edit().apply {
            keysToRemove.forEach { remove(it) }
        }.apply()
    }

    /** Clears all per-country server caches (timestamps and files). */
    fun clearAllServersCaches(context: Context) {
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("v2_servers_") && it.name.endsWith(SERVERS_CACHE_FILE_SUFFIX)
        }?.forEach { it.delete() }
        val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
        val keysToRemove = prefs.all.keys.filter { it.startsWith("ts_servers_") }
        prefs.edit().apply {
            keysToRemove.forEach { remove(it) }
        }.apply()
    }

}
