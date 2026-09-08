package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import com.google.gson.Gson
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.settings.AppLocaleEpoch
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Result of a single lazy-loaded page fetch. [nextSkip] is derived from the raw
 * (pre-configData-filtering) item count returned by the backend so the caller's next request
 * stays aligned with the API's own `skip`/`take` cursor, even though [servers] itself only
 * contains the filtered, non-blank-configData entries.
 *
 * @param languageChanged true when the app language changed after this paging session started, so
 * this page is localized differently from the pages the caller already holds. The repository drops
 * its own accumulator in that case, but the caller keeps its list independently: it must discard
 * what it has and restart the session at `skip = 0` rather than append this page, otherwise the
 * visible list ends up mixing two languages.
 */
data class ServersV2Page(
    val servers: List<ServerV2>,
    val hasMore: Boolean,
    val nextSkip: Int,
    val languageChanged: Boolean = false
)

/** Identity of a paging entry that has no stable server id: equality over the full
 * connection attributes, so distinct connections never collide (unlike a hash). */
internal data class NoIdKey(val ip: String?, val configData: String)

/**
 * Everything a foreground paging session captures at its first page (`skip = 0`) and then
 * validates for every later page and for its final cache write.
 *
 * Each repository call resolves the app language and the country's sync generation on its own,
 * so without a capture taken once per session a session has no notion of "the conditions I
 * started under" — every page silently adopts whatever is true at the moment it runs.
 *
 * @param locale the normalized locale the session's first page was fetched in. The cache key is
 * built from the locale resolved per call, so a language change landing mid-session would append
 * pages fetched in the new language to an accumulator built in the old one and file the mixed
 * result under the new language's key with a fresh TTL stamp.
 * @param localeEpoch pairs with [locale] because the equality check alone cannot see a language
 * that moved away and back (en -> ru -> en) while a page request was in flight. The live
 * comparison is still needed alongside it: an OS locale change under the system-language option
 * never goes through the settings store and so advances no epoch.
 * @param syncGeneration the country's sync generation at session start, so a full-list persist is
 * skipped when a newer same-country sync completed while the user was scrolling.
 * @param persistTicket a monotonic ticket ordering this session against every other paging
 * session for the same country, so an older session that finishes last cannot overwrite a newer
 * session's already-written cache.
 * @param languageChanged set once a later page was served in a different language than [locale].
 * The already-consumed offsets can never be re-fetched in the new language, so such a session can
 * no longer produce a valid full list: it stops accumulating and never persists, instead of
 * silently caching a partial or mixed-language list.
 */
internal data class PagingSessionPin(
    val locale: String,
    val localeEpoch: Long,
    val syncGeneration: Long,
    val persistTicket: Long,
    val languageChanged: Boolean = false
)

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
    private val pagesFetchedForSession: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
) {

    // What each foreground accumulate session captured at skip=0 -- language, language epoch,
    // country sync generation and its cache-write ordering ticket -- keyed the same as
    // [pageAccumulators] and removed alongside them. See [PagingSessionPin]. Not a constructor
    // parameter: a public constructor may not expose this internal type, and nothing outside
    // this class has any business seeding a session's capture.
    private val pageSessionPins: ConcurrentHashMap<String, PagingSessionPin> = ConcurrentHashMap()

    // Highest cache-write ticket that has already written a country's full-list cache, keyed by
    // canonical country key. A writer may only write when no LATER-started writer has written
    // this country already, which is what stops an older one that finishes last from replacing a
    // newer one's list and re-stamping the TTL. One Long per country actually paged, so it stays
    // bounded for the process lifetime.
    //
    // Both writers of that one cache file take part: foreground accumulate sessions
    // ([getServersPage]) and the silent background backfill, which builds its own merged list and
    // writes it through [persistFullServerList]. The backfill needs the same ordering because
    // nothing else separates the two -- starting a foreground session does not advance
    // CountrySyncGenerations, so a backfill descheduled after its final page and a foreground
    // session opened afterwards both read as current, and whichever writes last wins on a plain
    // last-writer-wins file write.
    private val lastPersistedCacheTicket: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    // Tickets come from one process-wide sequence so they stay comparable across both kinds of
    // writer, and are allocated at start -- session start for foreground paging, launch time for
    // a backfill. Starting a foreground paging session deliberately does NOT advance
    // CountrySyncGenerations, which would stand down the unrelated silent backfill for the same
    // country and drop the auto-switch candidate pool it is building.
    private val pagingTicketSequence = AtomicLong(0L)

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
                CountrySyncGenerations.bump(countryCode)
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
     *
     * The session also captures the language it started in and a cache-write ordering ticket at
     * its first page (see [PagingSessionPin]), so a language change mid-scroll cannot produce a
     * mixed-language cached list, and an older session finishing after a newer one cannot
     * overwrite the newer session's list.
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
        // Captured together with the locale above, BEFORE the request is issued, and never
        // re-read after the response. The pin records a (locale, epoch) pair, and the two halves
        // only describe the same instant if they were read at the same instant: reading the epoch
        // after the response returns pairs the language the response was actually served in with
        // whatever state the app moved to while it was in flight. That skew is invisible to the
        // between-page comparison for a `skip == 0` request, which has no earlier pin to compare
        // against, so a first page that is also the last one -- or one the user never scrolls past
        // -- would keep a stale-language list pinned as valid across configuration recreation.
        val requestLocaleEpoch = AppLocaleEpoch.current()
        val normalizedCountryCode = normalizeCountryCode(countryCode)
        val lockKey = "$normalizedCountryCode|$normalizedLocale"
        val mutex = serversMutexMap.computeIfAbsent(lockKey) { Mutex() }
        // Canonicalize the generation key ONCE, here, and use that single value for both the
        // skip=0 capture and the hasMore=false comparison below. Sync bumps
        // (getServersForCountry) and backfill generations (launchSilentBackfill) go through the
        // same CountrySyncGenerations.key(); reading the map with the raw countryCode instead
        // made a newer sync invisible to this guard whenever the code arrived lower-case, so a
        // stale paged accumulator would overwrite the fresher full-list cache.
        val generationKey = CountrySyncGenerations.key(countryCode)
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

            // Pin the language to the SESSION, not to the individual request. A screen kept
            // across configuration recreation keeps its paging session, while this method
            // resolves the language again for every page, so an app-language or OS-locale change
            // mid-scroll would otherwise append pages fetched in the new language to the
            // accumulator built in the old one and cache that mixed list under the new
            // language's key with a fresh TTL stamp. The offsets already consumed cannot be
            // re-fetched in the new language, so a session that saw a change can no longer
            // produce a valid full list: it drops what it accumulated and gives up its write.
            // The live locale comparison and the epoch are both required -- the epoch catches a
            // language that moved away and back between two pages, and the live comparison
            // catches an OS locale change under the system-language option, which advances no
            // epoch because it never goes through the settings store.
            val pinnedBeforeThisPage = pageSessionPins[sessionKey]
            // Did the language move WHILE this request was in flight? Both terms compare against
            // values captured before it was issued, so this catches what the between-page
            // comparison cannot: an app-language change (epoch) or an OS locale change under the
            // system-language option (live locale, which advances no epoch) that landed after the
            // request went out. It applies to every page, `skip == 0` included -- that is the only
            // staleness check the first page of a session has.
            val localeMovedInFlight = AppLocaleEpoch.current() != requestLocaleEpoch ||
                normalizeLocale(resolvePreferredLocale(context)) != normalizedLocale
            val languageChanged = localeMovedInFlight ||
                (skip != 0 && pinnedBeforeThisPage != null &&
                    (pinnedBeforeThisPage.locale != normalizedLocale ||
                        pinnedBeforeThisPage.localeEpoch != requestLocaleEpoch))
            if (languageChanged) {
                AppLog.w(
                    TAG,
                    "getServersPage[$countryCode]: language changed mid-session " +
                        "(${pinnedBeforeThisPage?.locale ?: normalizedLocale} -> $normalizedLocale, " +
                        "inFlight=$localeMovedInFlight) -- dropping this session's accumulated " +
                        "pages instead of merging two languages"
                )
                pageAccumulators.remove(sessionKey)
                if (pinnedBeforeThisPage != null) {
                    pageSessionPins[sessionKey] = pinnedBeforeThisPage.copy(languageChanged = true)
                }
            }
            // A `skip = 0` request restarts the session outright -- it rebuilds the accumulator
            // and re-pins the language below -- so a previous pin's give-up flag does not carry
            // into it. Without that bound, a session restarted *because* of a language change
            // would stay poisoned: it would refuse its own cache write and keep reporting the
            // change back to the caller, which would restart it again, and again.
            //
            // An in-flight move is exempt from that bound: it is a property of THIS request, not
            // an inherited flag, so a `skip == 0` page whose language shifted under it must still
            // report the restart. The restart itself terminates -- the re-issued request resolves
            // the new locale and captures the new epoch, so it sees no move and pins cleanly.
            val sessionGaveUpItsWrite = localeMovedInFlight ||
                (skip != 0 && (languageChanged || pinnedBeforeThisPage?.languageChanged == true))

            fun pinThisSession() {
                pageSessionPins[sessionKey] = PagingSessionPin(
                    locale = normalizedLocale,
                    // The epoch captured alongside `normalizedLocale` before the request, not a
                    // fresh read: the pin must describe one instant, and a re-read here would
                    // reintroduce the split this capture exists to close.
                    localeEpoch = requestLocaleEpoch,
                    syncGeneration = CountrySyncGenerations.current(generationKey),
                    persistTicket = pagingTicketSequence.incrementAndGet()
                )
            }
            if (skip == 0) {
                if (localeMovedInFlight) {
                    // Neither seed the accumulator with a response served in a language that is no
                    // longer current, nor pin that response as this session's language. Releasing
                    // both leaves the session in its pre-start state, which is exactly what the
                    // caller's restart at `skip = 0` expects to find.
                    pageAccumulators.remove(sessionKey)
                    pageSessionPins.remove(sessionKey)
                } else {
                    pageAccumulators[sessionKey] = filtered.toMutableList()
                    pinThisSession()
                }
            } else if (!sessionGaveUpItsWrite) {
                // De-duplicate by server id when accumulating. Pages are requested
                // seconds-to-minutes apart (the user scrolling) instead of the old eager loop's
                // milliseconds, so the backend's active-server cache can shift between page
                // fetches and yield the same server again at a different offset.
                val accumulated = pageAccumulators.getOrPut(sessionKey) { mutableListOf() }
                // De-dup keys fall back to connection attributes for entries without a stable
                // id, so zero-id servers neither collapse onto one row nor get discarded.
                val seenKeys = accumulated.mapTo(HashSet()) { dedupKey(it) }
                filtered.forEach { server -> if (seenKeys.add(dedupKey(server))) accumulated.add(server) }
                // A page arriving for a session that holds no capture -- its state was released
                // while this fetch was in flight, and the accumulator above was rebuilt from
                // scratch -- starts a new session's worth of accumulation here, so it takes a
                // capture here too. Leaving it unpinned would make its write unordered against
                // the other sessions for this country, which is the hazard being closed.
                if (pinnedBeforeThisPage == null) pinThisSession()
            }
            if (!hasMore) {
                pagesFetchedForSession.remove(sessionKey)
                val fullList = pageAccumulators.remove(sessionKey).orEmpty()
                val pin = pageSessionPins.remove(sessionKey)
                // A stop forced by the safety limit means the accumulated list is
                // knowingly incomplete -- do not cache it as this country's authoritative full
                // list, which would otherwise stick for the whole TTL.
                // A same-country sync (SSE push, periodic, or foreground refresh)
                // completing while this paging session was in flight writes a fresher full-list
                // cache -- do not overwrite it with the paging session's older accumulated data.
                val selectionMovedOn = pin != null &&
                    CountrySyncGenerations.current(generationKey) != pin.syncGeneration
                when {
                    reachedSafetyLimit -> Unit // already logged above
                    sessionGaveUpItsWrite -> AppLog.w(
                        TAG,
                        "getServersPage[$countryCode]: skipping full-list cache persist -- " +
                            "the app language changed while this session was paging"
                    )
                    selectionMovedOn -> AppLog.w(
                        TAG,
                        "getServersPage[$countryCode]: skipping full-list cache persist -- " +
                            "selection version moved (a newer sync completed while paging was in flight)"
                    )
                    fullList.isEmpty() -> Unit // persistFullListCache is a no-op for an empty list
                    // Order the write against every other paging session for this country. Two
                    // overlapping sessions capture the same sync generation -- starting a session
                    // does not advance it -- so the guard above cannot tell them apart, and the
                    // session-keyed accumulators only stop them sharing memory, not sharing this
                    // one cache file. Without this, a session that started earlier but finished
                    // later replaces the newer session's list and re-stamps the TTL, stranding
                    // the older pages for its whole duration.
                    !claimCachePersistSlot(generationKey, pin?.persistTicket) -> AppLog.w(
                        TAG,
                        "getServersPage[$countryCode]: skipping full-list cache persist -- " +
                            "a newer paging session for this country already wrote its list"
                    )
                    else -> persistFullListCache(context, countryCode, normalizedLocale, fullList)
                }
            }

            AppLog.d(
                TAG,
                "getServersPage[$countryCode]: skip=$skip take=$take fetched=${filtered.size} hasMore=$hasMore"
            )
            ServersV2Page(
                servers = filtered,
                hasMore = hasMore,
                nextSkip = nextSkip,
                // Dropping the repository's accumulator only protects the on-disk cache; the
                // screen keeps a list of its own, so it has to hear about the change to discard
                // that list and restart instead of appending a differently-localized page.
                languageChanged = sessionGaveUpItsWrite
            )
        }
    }

    /**
     * Persists [servers] as [countryCode]'s complete on-disk server list cache -- the
     * same file/timestamp key [getServersForCountry]/[getFreshCachedServers] read -- on behalf
     * of a caller (the silent background backfill) that fetched its pages via [getServersPage]
     * with `accumulate = false` and therefore built its own merged list instead of relying on
     * [pageAccumulators]. Acquires this country+locale's paging lock itself. No-op for an empty
     * list (mirrors [persistFullListCache]'s own guard).
     *
     * [expectedLocale] pins the write to the locale the caller's pages were fetched in. The cache
     * key is derived from the locale resolved *here*, so without this check a language change
     * landing after the caller's own guard would file a list built in the previous language under
     * the new language's key, with a fresh TTL stamp -- stranding the wrong-language list for the
     * rest of the TTL. Compared against the very value the key is built from, so there is no
     * window left between the check and the write.
     *
     * [persistTicket] (from [allocateCachePersistTicket], taken at the caller's launch) orders this
     * write against every foreground paging session for the same country. Nothing else does:
     * starting a foreground session does not advance [CountrySyncGenerations], so a backfill parked
     * after fetching its final page and a foreground session opened and completed afterwards both
     * pass their generation guards, and this write would otherwise replace that newer list and
     * re-stamp the TTL simply by landing last. A `null` ticket opts out of the ordering entirely
     * and is only correct where no concurrent paging session for the country can exist.
     */
    suspend fun persistFullServerList(
        context: Context,
        countryCode: String,
        servers: List<ServerV2>,
        expectedLocale: String? = null,
        persistTicket: Long? = null,
        shouldPersist: (() -> Boolean)? = null
    ) {
        val normalizedLocale = normalizeLocale(resolvePreferredLocale(context))
        if (expectedLocale != null && normalizeLocale(expectedLocale) != normalizedLocale) {
            AppLog.w(
                TAG,
                "persistFullServerList[$countryCode]: skipping write -- locale changed from " +
                    "${normalizeLocale(expectedLocale)} to $normalizedLocale while the caller was fetching"
            )
            return
        }
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
            // Claimed only after the predicate passed: a writer that stands down must not consume
            // the country's watermark and lock out the writers that follow it. Claiming is a plain
            // map operation, so taking it while holding this country+locale's paging lock cannot
            // block on anything.
            if (persistTicket != null &&
                !claimCachePersistSlot(CountrySyncGenerations.key(countryCode), persistTicket)
            ) {
                AppLog.w(
                    TAG,
                    "persistFullServerList[$countryCode]: skipping write -- " +
                        "a newer paging session for this country already wrote its list"
                )
                return
            }
            persistFullListCache(context, countryCode, normalizedLocale, servers)
        }
    }

    /**
     * Allocates a cache-write ordering ticket from the same sequence foreground paging sessions
     * draw from, for a caller that persists a country's full list through [persistFullServerList].
     *
     * Take it at launch, next to the caller's other launch-time captures -- the ticket has to
     * describe when this work *started*, so a foreground session opened afterwards outranks it.
     * Allocating it at write time would make the writer that finishes last always look newest,
     * which is the ordering hole it exists to close.
     */
    internal fun allocateCachePersistTicket(): Long = pagingTicketSequence.incrementAndGet()

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
        // Only this session's own state is released. The per-country write watermark is
        // deliberately left alone: it records which session last wrote the shared cache, so
        // clearing it on one screen's teardown would re-open the very ordering hole it closes.
        pageSessionPins.remove(pagingSessionId)
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

    /**
     * Reserves the right for the writer holding [persistTicket] to write [generationKey]'s
     * full-list cache, returning false when a writer that started later already wrote it. Both
     * kinds of writer go through here: a foreground accumulate session and a silent backfill
     * calling [persistFullServerList], ordered against each other by one shared ticket sequence.
     *
     * The check and the claim are one atomic step per key, mirroring
     * [CountrySyncGenerations.bumpUnlessBumpedSince]. The surrounding paging lock is not enough on
     * its own: it is keyed by country **and locale**, while this watermark is per country, so two
     * writers on the same country in different languages hold different locks and would otherwise
     * be free to interleave a read and a write here.
     *
     * A writer with no ticket (a foreground session whose state was released while the last page
     * was in flight) is treated as unordered and declines the write rather than claiming the
     * country's watermark.
     */
    private fun claimCachePersistSlot(generationKey: String, persistTicket: Long?): Boolean {
        if (persistTicket == null) return false
        var claimed = false
        lastPersistedCacheTicket.compute(generationKey) { _, previous ->
            if (previous != null && previous > persistTicket) {
                previous
            } else {
                claimed = true
                persistTicket
            }
        }
        return claimed
    }

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
