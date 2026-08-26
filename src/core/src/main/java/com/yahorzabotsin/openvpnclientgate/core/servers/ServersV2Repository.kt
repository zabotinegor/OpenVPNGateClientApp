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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of a single lazy-loaded page fetch. [nextSkip] is derived from the raw
 * (pre-configData-filtering) item count returned by the backend so the caller's next request
 * stays aligned with the API's own `skip`/`take` cursor, even though [servers] itself only
 * contains the filtered, non-blank-configData entries.
 */
data class ServersV2Page(
    val servers: List<ServerV2>,
    val hasMore: Boolean,
    val nextSkip: Int
)

/** Identity of a paging entry that has no stable server id: equality over the full
 * connection attributes, so distinct connections never collide (unlike a hash). */
internal data class NoIdKey(val ip: String?, val configData: String)

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
    private val serversMutexMap: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap(),
    private val fileCopy: (File, File) -> Unit = { source, target -> source.copyTo(target, overwrite = false) },
    // Accumulates filtered ServerV2 items across a lazy-loading paging session (keyed by
    // country+locale), so the full merged list can be written to the same on-disk cache that
    // fetchAllPages() writes once the session reaches its last page (hasMore=false) -- matching
    // "cached full list served fast" for the *next* time this country is opened, without
    // requiring the current screen to drain every page up front. Entries are removed once a
    // session completes (hasMore=false) or is explicitly abandoned via [abandonPagingSession]
    // -- a country left mid-scroll before either of those would otherwise retain its
    // accumulated ServerV2 list, including full configData blobs, for the process lifetime.
    private val pageAccumulators: ConcurrentHashMap<String, MutableList<ServerV2>> = ConcurrentHashMap(),

    // Per-session page counter, keyed the same as [pageAccumulators]. Mirrors
    // fetchAllPages's MAX_PAGES_SAFETY_LIMIT bound: without it, a backend that reports a wrong
    // or hostile `total` keeps `hasMore=true` forever and the accumulator/adapter/UI state grow
    // without bound. Reset on a fresh skip=0 session and removed alongside the accumulator.
    private val pagesFetchedForSession: ConcurrentHashMap<String, Int> = ConcurrentHashMap(),
    // Captures SelectedCountryVersionSignal.version at skip=0 of each foreground
    // accumulate session so a full-list cache persist at hasMore=false can be skipped when a
    // newer same-country sync completed in the meantime.
    private val pageStartVersions: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
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
        val lockKey = "$normalizedCountryCode|$normalizedLocale"
        val mutex = serversMutexMap.computeIfAbsent(lockKey) { Mutex() }
        return mutex.withLock {
            val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
            val cacheKey = serversTimestampKey(normalizedCountryCode, normalizedLocale)
            migrateLegacyServersCacheIfNeeded(context, prefs, normalizedCountryCode, normalizedLocale)
            AppLog.d(
                TAG,
                "getServersForCountry[$countryCode]: serverCount=$serverCount locale=$normalizedLocale"
            )
            val networkHit = AtomicBoolean(false)
            val result = fetchWithCache(
                cacheFile = serversCacheFile(context, countryCode, normalizedLocale),
                tsKey = cacheKey,
                prefs = prefs,
                cacheTtlMs = settingsStore.load(context).cacheTtlMs,
                forceRefresh = forceRefresh,
                cacheOnly = cacheOnly,
                logPrefix = "getServersForCountry[$countryCode][$normalizedLocale]",
                parse = ::parseServers,
                fetchNetwork = { Gson().toJson(fetchAllPages(countryCode, serverCount, normalizedLocale)) },
                networkHit = networkHit
            )
            // Bump the selection version and per-country generation inside the mutex so
            // that a foreground paging page waiting on this lock sees the updated version
            // and skips its stale cache persist. Bump when a network result was committed
            // (covers both forceRefresh and TTL-expired non-forced refreshes), even if
            // the committed list is empty (backend may have removed all servers).
            if (networkHit.get()) {
                SelectedCountryVersionSignal.bump()
                CountrySyncGenerations.generations.merge(countryCode.uppercase(), 1L) { prev, _ -> prev + 1L }
            }
            result
        }
    }

    /**
     * Returns the cached server list for [countryCode] only if a non-expired (still within
     * TTL) cache entry exists, without ever making a network call -- used by the lazy-loading
     * screen to take the existing "already fast" warm-cache path unchanged. Returns
     * null when the cache is absent, expired, or fails to parse (caller falls back to genuine
     * paged fetching in that case).
     */
    suspend fun getFreshCachedServers(
        context: Context,
        countryCode: String
    ): List<ServerV2>? {
        val locale = resolvePreferredLocale(context)
        val normalizedLocale = normalizeLocale(locale)
        val normalizedCountryCode = normalizeCountryCode(countryCode)
        val lockKey = "$normalizedCountryCode|$normalizedLocale"
        val mutex = serversMutexMap.computeIfAbsent(lockKey) { Mutex() }
        return mutex.withLock {
            val prefs = context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
            migrateLegacyServersCacheIfNeeded(context, prefs, normalizedCountryCode, normalizedLocale)
            val cacheFile = serversCacheFile(context, countryCode, normalizedLocale)
            val tsKey = serversTimestampKey(normalizedCountryCode, normalizedLocale)
            val ts = prefs.getLong(tsKey, -1L)
            val cacheTtlMs = settingsStore.load(context).cacheTtlMs
            val cacheValid = ts > 0L && cacheFile.isFile && (System.currentTimeMillis() - ts) < cacheTtlMs
            if (!cacheValid) return@withLock null
            try {
                withContext(Dispatchers.IO) { parseServers(cacheFile.readText()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "getFreshCachedServers[$countryCode]: cache parse error", e)
                null
            }
        }
    }

    /**
     * Fetches exactly one page (`skip`/`take`) of servers for [countryCode] directly from the
     * network -- the lazy-loading counterpart to [getServersForCountry]'s [fetchAllPages].
     * Does not consult or write the on-disk cache per call; instead, filtered items
     * are accumulated in-memory for THIS paging session only (keyed by [pagingSessionId], so
     * two overlapping country screens can never share or clobber each other's state) and the full merged list is persisted to
     * the same cache file [getServersForCountry] reads once [ServersV2Page.hasMore] turns
     * false (the session reached its last page), so a later re-open within the TTL takes the
     * warm-cache fast path. [abandonPagingSession] releases the session's state.
     */
    suspend fun getServersPage(
        context: Context,
        countryCode: String,
        skip: Int,
        take: Int = PAGE_SIZE,
        accumulate: Boolean = true,
        pagingSessionId: String? = null
    ): ServersV2Page {
        val locale = resolvePreferredLocale(context)
        val normalizedLocale = normalizeLocale(locale)
        val normalizedCountryCode = normalizeCountryCode(countryCode)
        val lockKey = "$normalizedCountryCode|$normalizedLocale"
        val mutex = serversMutexMap.computeIfAbsent(lockKey) { Mutex() }
        // Validate the session id BEFORE acquiring the lock or touching the
        // network -- a default-args accumulate caller must fail fast instead of paying a real
        // request and only then receiving an IllegalArgumentException. The non-accumulating
        // backfill path is explicitly exempt (it owns its own accumulation).
        if (accumulate) {
            require(!pagingSessionId.isNullOrEmpty()) {
                "getServersPage[$countryCode]: accumulate=true requires a pagingSessionId"
            }
        }
        return mutex.withLock {
            val page = withContext(Dispatchers.IO) {
                api.getServers(
                    locale = normalizedLocale,
                    countryCode = countryCode,
                    isActive = true,
                    skip = skip,
                    take = take
                )
            }
            val items = page.items
                ?: throw IOException("getServersPage[$countryCode]: missing 'items' in response")
            val rawCount = items.size
            val filtered = items.filter { it.configData.isNotBlank() }
            val reachedApiTotal = page.total > 0 && (skip + rawCount) >= page.total
            val nextSkip = skip + rawCount

            if (!accumulate) {
                // A session-isolated fetch (currently only the silent background
                // backfill in CountryServersInteractor). Deliberately does not touch
                // pageAccumulators/pagesFetchedForSession or persist the on-disk cache -- those
                // are shared per country+locale across every foreground paging session, and a
                // caller that mixed into them could have its pages wiped by an unrelated
                // abandonPagingSession() call from a screen teardown racing this fetch, or
                // collide with a second concurrently-open session on the same country. The
                // caller owns its own accumulation, its own safety-limit bound, and -- once
                // done -- persisting the merged list via [persistFullServerList].
                val hasMore = if (page.total > 0) !reachedApiTotal else rawCount >= take
                AppLog.d(
                    TAG,
                    "getServersPage[$countryCode]: (non-accumulating) skip=$skip take=$take fetched=${filtered.size} hasMore=$hasMore"
                )
                return@withLock ServersV2Page(servers = filtered, hasMore = hasMore, nextSkip = nextSkip)
            }

            // Bound the number of pages fetched per session the same way
            // fetchAllPages does, so a wrong or hostile `total` from the backend cannot keep
            // hasMore=true (and this accumulator/the UI list) growing forever.
            // Accumulation is keyed by the caller's
            // paging session id -- overlapping country screens get disjoint state, so no
            // session can overwrite, abandon, or tail-persist another session's pages.
            // (The session id was already validated before the network call above.)
            val sessionKey = pagingSessionId!!
            val pagesFetched = if (skip == 0) 1 else (pagesFetchedForSession[sessionKey] ?: 0) + 1
            pagesFetchedForSession[sessionKey] = pagesFetched
            val reachedSafetyLimit = pagesFetched >= MAX_PAGES_SAFETY_LIMIT
            val hasMore = !reachedSafetyLimit && if (page.total > 0) !reachedApiTotal else rawCount >= take
            if (reachedSafetyLimit) {
                AppLog.w(TAG, "getServersPage[$countryCode]: stopped by safety page limit ($MAX_PAGES_SAFETY_LIMIT)")
            }

            // De-duplicate by server id when accumulating. Pages are now requested
            // seconds-to-minutes apart (the user scrolling) instead of the old eager loop's
            // milliseconds, so the backend's active-server cache can shift between page fetches
            // and yield the same server again at a different offset.
            if (skip == 0) {
                pageAccumulators[sessionKey] = filtered.toMutableList()
                pageStartVersions[sessionKey] = CountrySyncGenerations.generations[countryCode] ?: 0L
            } else {
                val accumulated = pageAccumulators.getOrPut(sessionKey) { mutableListOf() }
                // De-dup keys fall back to connection attributes for entries without a stable
                // id, so zero-id servers neither collapse onto one row nor get discarded.
                val seenKeys = accumulated.mapTo(HashSet()) { dedupKey(it) }
                filtered.forEach { server -> if (seenKeys.add(dedupKey(server))) accumulated.add(server) }
            }
            if (!hasMore) {
                pagesFetchedForSession.remove(sessionKey)
                val fullList = pageAccumulators.remove(sessionKey).orEmpty()
                val startVersion = pageStartVersions.remove(sessionKey)
                // A stop forced by the safety limit means the accumulated list is
                // knowingly incomplete -- do not cache it as this country's authoritative full
                // list, which would otherwise stick for the whole TTL.
                // A same-country sync (SSE push, periodic, or foreground refresh)
                // completing while this paging session was in flight writes a fresher full-list
                // cache -- do not overwrite it with the paging session's older accumulated data.
                val selectionMovedOn = startVersion != null &&
                    (CountrySyncGenerations.generations[countryCode] ?: 0L) != startVersion
                if (reachedSafetyLimit) {
                    // already logged above
                } else if (selectionMovedOn) {
                    AppLog.w(
                        TAG,
                        "getServersPage[$countryCode]: skipping full-list cache persist -- " +
                            "selection version moved (a newer sync completed while paging was in flight)"
                    )
                } else {
                    persistFullListCache(context, countryCode, normalizedLocale, fullList)
                }
            }

            AppLog.d(
                TAG,
                "getServersPage[$countryCode]: skip=$skip take=$take fetched=${filtered.size} hasMore=$hasMore"
            )
            ServersV2Page(servers = filtered, hasMore = hasMore, nextSkip = nextSkip)
        }
    }

    /**
     * Persists [servers] as [countryCode]'s complete on-disk server list cache -- the
     * same file/timestamp key [getServersForCountry]/[getFreshCachedServers] read -- on behalf
     * of a caller (the silent background backfill) that fetched its pages via [getServersPage]
     * with `accumulate = false` and therefore built its own merged list instead of relying on
     * [pageAccumulators]. Acquires this country+locale's paging lock itself. No-op for an empty
     * list (mirrors [persistFullListCache]'s own guard).
     */
    suspend fun persistFullServerList(
        context: Context,
        countryCode: String,
        servers: List<ServerV2>,
        shouldPersist: (() -> Boolean)? = null
    ) {
        val normalizedLocale = normalizeLocale(resolvePreferredLocale(context))
        val normalizedCountryCode = normalizeCountryCode(countryCode)
        val lockKey = "$normalizedCountryCode|$normalizedLocale"
        val mutex = serversMutexMap.computeIfAbsent(lockKey) { Mutex() }
        mutex.withLock {
            // Re-check via caller-provided predicate: a newer same-country backfill or
            // sync may have started while we waited for the mutex.
            if (shouldPersist != null && !shouldPersist()) {
                AppLog.w(TAG, "persistFullServerList[$countryCode]: skipping write -- caller predicate declined")
                return
            }
            persistFullListCache(context, countryCode, normalizedLocale, servers)
        }
    }

    /**
     * Best-effort synchronous cleanup: drops the in-memory paging accumulator (and
     * its page counter) owned by paging session [pagingSessionId], releasing retained
     * `configData` blobs for a lazy-loading session that will never resume -- e.g. the user
     * leaves the country screen mid-scroll, before [getServersPage] ever reaches
     * `hasMore=false`. Safe to call even when no state exists for this session (no-op). Does
     * not touch the on-disk cache.
     *
     * Keyed by the caller's paging session id, not
     * by country+locale -- so cleanup can never touch another, still-live session's state, and
     * it works even when the country screen was opened by name without
     * [com.yahorzabotsin.openvpnclientgate.core.ui.serverlist.CountryServersActivity]
     * `EXTRA_COUNTRY_CODE` (the old code-keyed lookup silently no-op'd there).
     *
     * Note: like every other mutation of these maps this one is not taken under
     * a country mutex -- it is intentionally non-suspend so a ViewModel's `onCleared()` can
     * call it synchronously. With session-keyed entries the old cross-session hazard is gone
     * structurally: only the owning session ever reads or writes its own entries, and the
     * owning ViewModel cancels its deferred paging scope right after abandoning, so an
     * in-flight fetch of the SAME session cannot commit into the abandoned entry afterwards.
     */
    fun abandonPagingSession(pagingSessionId: String) {
        pagesFetchedForSession.remove(pagingSessionId)
        pageStartVersions.remove(pagingSessionId)
        if (pageAccumulators.remove(pagingSessionId) != null) {
            AppLog.d(TAG, "abandonPagingSession[session=$pagingSessionId]: cleared accumulator for abandoned session")
        }
    }

    /** Writes the fully-paged-in server list to the same cache file/timestamp key that
     * [fetchWithCache] uses, so the next open of this country within the TTL is a cache hit.
     * Must be called while already holding this country+locale's [serversMutexMap] lock. */
    /** De-dup identity for accumulated servers: stable id when present, otherwise a fallback
     * built from the full connection attributes (never a hash of them -- a hash collision
     * would collapse distinct zero-id servers into one row). */
    private fun dedupKey(server: ServerV2): Any =
        if (server.id > 0) server.id else NoIdKey(server.ip, server.configData)

    private suspend fun persistFullListCache(
        context: Context,
        countryCode: String,
        normalizedLocale: String,
        servers: List<ServerV2>
    ) {
        if (servers.isEmpty()) return
        try {
            val cacheFile = serversCacheFile(context, countryCode, normalizedLocale)
            val tsKey = serversTimestampKey(normalizeCountryCode(countryCode), normalizedLocale)
            val json = Gson().toJson(servers)
            withContext(Dispatchers.IO) { cacheFile.writeText(json) }
            context.getSharedPreferences(CACHE_PREFS, MODE_PRIVATE)
                .edit().putLong(tsKey, System.currentTimeMillis()).apply()
            AppLog.d(TAG, "persistFullListCache[$countryCode]: cached ${servers.size} servers after final page")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "persistFullListCache[$countryCode]: failed to write cache", e)
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

        try {
            fileCopy(legacyFile, localizedFile)
            val legacyTimestamp = prefs.getLong(KEY_COUNTRIES_TS_LEGACY, -1L)
            if (legacyTimestamp > 0L) {
                prefs.edit().putLong(localizedTsKey, legacyTimestamp).apply()
            }
            AppLog.d(TAG, "migrateLegacyCountriesCacheIfNeeded: migrated legacy cache to locale=$normalizedLocale")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { localizedFile.delete() }
            AppLog.w(TAG, "migrateLegacyCountriesCacheIfNeeded: migration failed for locale=$normalizedLocale", e)
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

        try {
            fileCopy(legacyFile, localizedFile)
            val legacyTsKey = "$KEY_SERVERS_TS_PREFIX${normalizedCountryCode}"
            val legacyTimestamp = prefs.getLong(legacyTsKey, -1L)
            if (legacyTimestamp > 0L) {
                prefs.edit().putLong(localizedTsKey, legacyTimestamp).apply()
            }
            AppLog.d(
                TAG,
                "migrateLegacyServersCacheIfNeeded: migrated legacy cache for country=$normalizedCountryCode locale=$normalizedLocale"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { localizedFile.delete() }
            AppLog.w(
                TAG,
                "migrateLegacyServersCacheIfNeeded: migration failed for country=$normalizedCountryCode locale=$normalizedLocale",
                e
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
        fetchNetwork: suspend () -> String,
        networkHit: AtomicBoolean? = null
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
                fetchFromNetworkWithParsing(logPrefix, cacheFile, tsKey, prefs, cacheTtlMs, parse, fetchNetwork, networkHit)
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
        return fetchFromNetworkWithParsing(logPrefix, cacheFile, tsKey, prefs, cacheTtlMs, parse, fetchNetwork, networkHit)
    }

    private suspend fun <T> fetchFromNetworkWithParsing(
        logPrefix: String,
        cacheFile: File,
        tsKey: String,
        prefs: SharedPreferences,
        cacheTtlMs: Long,
        parse: (String) -> List<T>,
        fetchNetwork: suspend () -> String,
        networkHit: AtomicBoolean? = null
    ): List<T> {
        return try {
            val json = withContext(Dispatchers.IO) { fetchNetwork() }
            val parsed = withContext(Dispatchers.Default) { parse(json) }
            withContext(Dispatchers.IO) { cacheFile.writeText(json) }
            prefs.edit().putLong(tsKey, System.currentTimeMillis()).apply()
            networkHit?.set(true)
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
        if (keysToRemove.isEmpty()) return
        val editor = prefs.edit()
        keysToRemove.forEach { key -> editor.remove(key) }
        editor.apply()
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
