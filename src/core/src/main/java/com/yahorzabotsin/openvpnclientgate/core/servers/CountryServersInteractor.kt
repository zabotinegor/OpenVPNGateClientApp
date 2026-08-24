package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Result of a single lazy-loaded page request against [CountryServersInteractor.getServersPage]
 * (US-23), expressed in the legacy [Server] shape used throughout the UI layer.
 *
 * @param servers the servers for this page (or the full list, when [ServersV2Repository]'s
 * warm-cache fast path is taken -- see [getServersPage] doc).
 * @param hasMore true when at least one more page is expected; false once the country's full
 * list has been delivered (via genuine pagination or the cache fast path).
 * @param nextSkip the `skip` offset to request next; meaningless when [hasMore] is false.
 */
data class CountryServersPage(
    val servers: List<Server>,
    val hasMore: Boolean,
    val nextSkip: Int
)

interface CountryServersInteractor {
    suspend fun getServersForCountry(
        countryName: String,
        countryCode: String? = null,
        cacheOnly: Boolean
    ): List<Server>

    /**
     * Lazy-loading entry point (US-23): fetches one page of servers for [countryName]/
     * [countryCode] starting at [skip], up to [take] items.
     *
     * For the default V2 source, this genuinely fetches only the requested page over the
     * network when no fresh full-list cache exists (AC1/AC2), or serves the complete cached
     * list unchanged in one shot when a fresh cache is present (AC8 -- the already-fast path is
     * preserved). For every other source (VPN Gate/legacy, out of scope per the story), this
     * always returns the complete country list as a single page with `hasMore = false`,
     * matching pre-US-23 behavior exactly.
     *
     * @param pagingSessionId identifies THIS screen's paging session; the repository keys its
     * accumulation state by it so overlapping sessions of the same country stay independent
     * (review threads PRRT bveIU/bxsTH/bxsYe), and teardown releases exactly this session via
     * [abandonPagingSession].
     */
    suspend fun getServersPage(
        countryName: String,
        countryCode: String?,
        skip: Int,
        take: Int,
        cacheOnly: Boolean,
        pagingSessionId: String
    ): CountryServersPage

    /**
     * @param hasMorePages true when the page(s) backing [servers] are not yet the country's
     * complete list (US-23 M2): the caller selected a server before every page finished
     * loading. When true, a silent background fetch of the remaining pages is kicked off so
     * [SelectedCountryStore]'s persisted candidate pool for [ServerAutoSwitcher]
     * (`vpn.ServerAutoSwitcher`) becomes complete shortly after selection, without blocking or
     * delaying this call.
     * @param nextSkip the `skip` offset to resume the background backfill from; meaningless
     * when [hasMorePages] is false.
     */
    suspend fun resolveSelection(
        countryName: String,
        countryCode: String?,
        servers: List<Server>,
        selectedServer: Server,
        hasMorePages: Boolean = false,
        nextSkip: Int = 0
    ): ServerSelectionResult

    /**
     * Best-effort cleanup hook (US-23 M4): releases any in-memory V2 paging accumulator held
     * for [pagingSessionId] when the user leaves the country screen before its full list loaded
     * (and before a selection ever triggered [resolveSelection]'s own backfill, which cleans up
     * naturally once it completes). No-op for the legacy/VPN Gate source and when no state
     * exists for this session. Session-keyed (review PRRT bxsOx): cleanup works even when the
     * screen was opened by name without a country code, because it no longer depends on
     * resolving one. Not suspend: intended to be called from a ViewModel's non-suspend
     * `onCleared()`.
     */
    fun abandonPagingSession(pagingSessionId: String)
}

class DefaultCountryServersInteractor(
    private val appContext: Context,
    private val serverRepository: ServerRepository,
    private val serversV2Repository: ServersV2Repository? = null
) : CountryServersInteractor {
    private companion object {
        private val TAG = LogTags.APP + ":CountryServersInteractor"
        // US-23 M3 parity: bounds the silent background backfill (M2) the same way
        // ServersV2Repository bounds the foreground paged path, in case of a wrong/hostile total.
        private const val MAX_BACKFILL_PAGES_SAFETY_LIMIT = 200
    }

    // Outlives any single screen/ViewModel on purpose (US-23 M2): the backfill triggered by
    // resolveSelection() must keep running after the country screen (and its viewModelScope)
    // has already finished/cleared following the user's selection.
    private val backfillScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Visible for test synchronization only: the M2 backfill is fire-and-forget in production,
    // but a test needs a handle to join() the background coroutine before asserting on
    // SelectedCountryStore's persisted candidate pool.
    @VisibleForTesting
    internal var lastBackfillJob: Job? = null
        private set

    override suspend fun getServersForCountry(
        countryName: String,
        countryCode: String?,
        cacheOnly: Boolean
    ): List<Server> {
        val source = UserSettingsStore.load(appContext).serverSource
        if (source == ServerSource.DEFAULT_V2) {
            return getServersForCountryV2(countryName, countryCode, cacheOnly)
        }
        val allServers = serverRepository.getServers(
            context = appContext,
            forceRefresh = false,
            cacheOnly = cacheOnly
        )
        return allServers.filter { it.country.name == countryName }
    }

    private suspend fun getServersForCountryV2(
        countryName: String,
        countryCode: String?,
        cacheOnly: Boolean
    ): List<Server> {
        val repo = serversV2Repository
            ?: throw IOException("ServersV2Repository not injected for v2 source")
        val countryV2 = resolveCountryV2(repo, countryName, countryCode, cacheOnly)

        val v2Servers = repo.getServersForCountry(
            context = appContext,
            countryCode = countryV2.code,
            serverCount = countryV2.serverCount,
            forceRefresh = false,
            cacheOnly = cacheOnly
        )
        if (v2Servers.isEmpty()) throw IOException("No servers available for $countryName")

        val legacyServers = v2Servers.map { it.toLegacyServer() }
        AppLog.i(TAG, "getServersForCountryV2: country=$countryName servers=${legacyServers.size}")
        return legacyServers
    }

    /**
     * Finds the [CountryV2] for [countryName]/[countryCode] in the cached country list.
     * Prefers code lookup for stability: if a country label changes (backend rename/
     * localization), the code-based path avoids failure. If the cache is absent (splash sync
     * failed, app data cleared), honors [cacheOnly]: with cacheOnly=false a network fetch is
     * attempted before failing.
     */
    private suspend fun resolveCountryV2(
        repo: ServersV2Repository,
        countryName: String,
        countryCode: String?,
        cacheOnly: Boolean
    ): CountryV2 {
        val countries = repo.getCountries(appContext, forceRefresh = false, cacheOnly = cacheOnly)
        return countryCode?.let { code ->
            countries.firstOrNull { it.code.equals(code, ignoreCase = true) }
        } ?: countries.firstOrNull { it.name.equals(countryName, ignoreCase = true) }
            ?: throw IOException("Country '$countryName' (code=${countryCode ?: "<unknown>"}) not found in cache.")
    }

    override suspend fun getServersPage(
        countryName: String,
        countryCode: String?,
        skip: Int,
        take: Int,
        cacheOnly: Boolean,
        pagingSessionId: String
    ): CountryServersPage {
        val source = UserSettingsStore.load(appContext).serverSource
        if (source != ServerSource.DEFAULT_V2) {
            // Out of scope for lazy loading (VPN Gate/legacy source): always the full list in
            // one shot, exactly as before US-23.
            val all = getServersForCountry(countryName, countryCode, cacheOnly)
            return CountryServersPage(servers = all, hasMore = false, nextSkip = all.size)
        }

        val repo = serversV2Repository
            ?: throw IOException("ServersV2Repository not injected for v2 source")
        val countryV2 = resolveCountryV2(repo, countryName, countryCode, cacheOnly)
        val resolvedCode = countryV2.code

        if (skip == 0) {
            val freshCached = repo.getFreshCachedServers(appContext, resolvedCode)
            if (!freshCached.isNullOrEmpty()) {
                val legacy = freshCached.map { it.toLegacyServer() }
                AppLog.i(TAG, "getServersPage: warm-cache fast path country=$countryName servers=${legacy.size}")
                return CountryServersPage(servers = legacy, hasMore = false, nextSkip = legacy.size)
            }
            if (cacheOnly) {
                // No fresh cache and network is disallowed (VPN connected): preserve the
                // pre-existing cacheOnly contract exactly -- a single stale-cache-tolerant full
                // read via getServersForCountryV2, never genuine network pagination.
                val all = getServersForCountryV2(countryName, countryCode, cacheOnly = true)
                return CountryServersPage(servers = all, hasMore = false, nextSkip = all.size)
            }
        }

        val page = try {
            repo.getServersPage(appContext, resolvedCode, skip = skip, take = take, pagingSessionId = pagingSessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (skip != 0) throw e
            // US-23 M1/F2 + review PRRT bveIO: restore the pre-US-23 offline fallback for the
            // cold (skip=0) path. fetchWithCache()/fetchFromNetworkWithParsing() used to catch
            // Exception broadly (after rethrowing CancellationException) and fall back to a
            // stale on-disk cache entry for *any* failure -- not just IOException. Retrofit
            // throws HttpException (a RuntimeException) for a non-2xx response and Gson throws
            // JsonSyntaxException for a malformed body; neither is an IOException, so a
            // narrower catch here would still let a backend 5xx/malformed-body response close
            // the screen despite a usable stale cache. getServersPage() has no fallback of its
            // own, so route through the STALE-CACHE-DIRECT read (cacheOnly=true makes the
            // repository read the stale file with networking disabled) instead of re-attempting
            // the network -- an offline cold open must not pay a second ~30s timeout here.
            AppLog.w(
                TAG,
                "getServersPage: network fetch failed at skip=0 for $countryName, falling back to stale-cache-tolerant full fetch",
                e
            )
            val all = getServersForCountryV2(countryName, countryCode, cacheOnly = true)
            return CountryServersPage(servers = all, hasMore = false, nextSkip = all.size)
        }
        val legacyServers = page.servers.map { it.toLegacyServer() }
        if (skip == 0 && legacyServers.isEmpty() && !page.hasMore) {
            throw IOException("No servers available for $countryName")
        }
        AppLog.i(
            TAG,
            "getServersPage: country=$countryName skip=$skip take=$take fetched=${legacyServers.size} hasMore=${page.hasMore}"
        )
        return CountryServersPage(servers = legacyServers, hasMore = page.hasMore, nextSkip = page.nextSkip)
    }

    override suspend fun resolveSelection(
        countryName: String,
        countryCode: String?,
        servers: List<Server>,
        selectedServer: Server,
        hasMorePages: Boolean,
        nextSkip: Int
    ): ServerSelectionResult {
        if (servers.isEmpty()) throw IOException("No servers available for $countryName")

        val source = UserSettingsStore.load(appContext).serverSource
        val resolvedServers: List<Server>
        if (source == ServerSource.DEFAULT_V2) {
            // configData is already embedded in the server from v2 API — no loadConfigs() call
            resolvedServers = servers
        } else {
            val configs = serverRepository.loadConfigs(appContext, servers)
            resolvedServers = servers.map { server ->
                server.copy(configData = configs[server.lineIndex].orEmpty())
            }
        }

        SelectedCountryStore.saveSelection(appContext, countryName, resolvedServers)
        if (source == ServerSource.DEFAULT_V2 && hasMorePages) {
            // US-23 M2 (product decision): backfill the remaining pages silently in the
            // background so the auto-switch candidate pool completes shortly after selection,
            // without making the user's connect action wait on it.
            launchSilentBackfill(countryName, countryCode, nextSkip, resolvedServers)
        }
        val chosenIndex = resolveSelectedIndex(
            selectedServer = selectedServer,
            inputServers = servers,
            resolvedServers = resolvedServers
        )

        runCatching { SelectedCountryStore.setCurrentIndex(appContext, chosenIndex) }
        val chosenResolved = resolvedServers[chosenIndex]
        val currentPos = runCatching { SelectedCountryStore.getCurrentPosition(appContext) }.getOrNull()
        val currentPosText = currentPos?.let { "${it.first}/${it.second}" } ?: "unknown"
        AppLog.i(
            TAG,
            "Selection resolved: country=$countryName selectedIp=${selectedServer.ip ?: "<none>"} selectedLine=${selectedServer.lineIndex} chosenIndex=${chosenIndex + 1}/${resolvedServers.size} chosenIp=${chosenResolved.ip ?: "<none>"} currentPos=$currentPosText"
        )

        return ServerSelectionResult(
            countryName = countryName,
            countryCode = countryCode,
            city = chosenResolved.city,
            config = chosenResolved.configData,
            ip = chosenResolved.ip
        )
    }

    private fun resolveSelectedIndex(
        selectedServer: Server,
        inputServers: List<Server>,
        resolvedServers: List<Server>
    ): Int {
        val selectedIndexInInput = inputServers.indexOfFirst { it === selectedServer }
            .takeIf { it >= 0 }
            ?: inputServers.indexOf(selectedServer).takeIf { it >= 0 }

        return listOf(
            selectedIndexInInput ?: -1,
            resolvedServers.indexOfFirst { it.lineIndex == selectedServer.lineIndex && it.ip == selectedServer.ip },
            resolvedServers.indexOfFirst { it.ip == selectedServer.ip },
            resolvedServers.indexOfFirst { it.lineIndex == selectedServer.lineIndex },
            resolvedServers.indexOfFirst { it.configData == selectedServer.configData && it.city == selectedServer.city }
        ).firstOrNull { it >= 0 } ?: 0
    }

    override fun abandonPagingSession(pagingSessionId: String) {
        // Session-keyed (review PRRT bxsOx): cleanup no longer depends on resolving a country
        // code, so screens opened by name without EXTRA_COUNTRY_CODE release their state too.
        serversV2Repository?.abandonPagingSession(pagingSessionId)
    }

    /**
     * US-23 M2: continues fetching this country's remaining pages on [backfillScope] --
     * independent of any ViewModel/screen scope, so it keeps running after the country screen
     * has already finished. Once the last page is reached, persists the full merged candidate
     * pool via [SelectedCountryStore.saveSelectionPreservingIndex] (preserves the just-set
     * current index as long as this is still the active selection), and separately persists the
     * same merged list as the country's on-disk full-list cache via
     * [ServersV2Repository.persistFullServerList]. Failures are logged and swallowed: a failed
     * backfill leaves the already-completed selection and its partial candidate pool untouched,
     * matching the "must not affect the completed selection" requirement.
     *
     * US-23 F1 fix: fetches every page with `accumulate = false`
     * ([ServersV2Repository.getServersPage]'s session-isolation parameter), so this backfill
     * never reads or writes [ServersV2Repository]'s shared `pageAccumulators`. Before this fix,
     * the backfill reused that shared per-country accumulator -- the same one the foreground
     * screen's own paging used -- keyed only by country+locale with no session identity. A
     * `ViewModel.onCleared()` firing (correctly) for the just-finished foreground session raced
     * this backfill and cleared the accumulator out from under it; the backfill's own last page
     * then started a *new* accumulator from scratch and persisted only its own tail as the
     * country's "complete" list, with a fresh cache timestamp -- silently truncating the cache
     * for the rest of the TTL. Fetching accumulate=false removes the shared state entirely: this
     * method now owns its own local merge (seeded from [initialServers], the pages the
     * foreground screen had already loaded) and persists it independently, so it can no longer
     * be disturbed by -- or interfere with -- any other session on the same country (also
     * closing F3's concurrent-session hazard). This also makes F5's "accumulator leaked on
     * failure" concern moot: there is no repository-side accumulator entry for this session to
     * leak in the first place, so no `finally { abandonPagingSession(...) }` is needed here --
     * the local maps below are ordinary coroutine-local state, reclaimed on completion or
     * cancellation like any other.
     */
    private fun launchSilentBackfill(
        countryName: String,
        countryCode: String?,
        startSkip: Int,
        initialServers: List<Server>
    ) {
        val repo = serversV2Repository ?: return
        lastBackfillJob = backfillScope.launch {
            try {
                val countryV2 = resolveCountryV2(repo, countryName, countryCode, cacheOnly = false)
                val resolvedCode = countryV2.code
                val accumulatedLegacy = LinkedHashMap<Int, Server>()
                val accumulatedV2 = LinkedHashMap<Int, ServerV2>()
                initialServers.forEach { server ->
                    accumulatedLegacy[server.id] = server
                    accumulatedV2[server.id] = server.toServerV2(resolvedCode, countryV2.name)
                }
                var skip = startSkip
                var hasMore = true
                var pagesFetched = 0
                while (hasMore && pagesFetched < MAX_BACKFILL_PAGES_SAFETY_LIMIT) {
                    val page = repo.getServersPage(appContext, resolvedCode, skip = skip, accumulate = false)
                    page.servers.forEach { v2 ->
                        accumulatedLegacy[v2.id] = v2.toLegacyServer()
                        accumulatedV2[v2.id] = v2
                    }
                    pagesFetched += 1
                    // G3: mirror F4's non-advancing-cursor guard (CountryServersViewModel.
                    // loadFirstPage). A page with an empty `items` array while the backend still
                    // reports total > skip yields nextSkip == skip with hasMore still true --
                    // without this guard the loop would re-issue the identical request up to
                    // MAX_BACKFILL_PAGES_SAFETY_LIMIT times, unattended, on the user's connection.
                    if (page.nextSkip <= skip) break
                    skip = page.nextSkip
                    hasMore = page.hasMore
                }
                // G2: mirror F6 (ServersV2Repository.getServersPage's own safety-limit guard).
                // hasMore is still true here only when the loop above exited early -- either the
                // MAX_BACKFILL_PAGES_SAFETY_LIMIT bound was hit, or the non-advancing-cursor guard
                // (G3) broke out of it -- so the accumulated list is knowingly incomplete. The
                // partial candidate pool is still useful to ServerAutoSwitcher, so
                // saveSelectionPreservingIndex always runs; but persisting an incomplete list as
                // this country's authoritative on-disk full-list cache (with a fresh TTL stamp)
                // would silently strand it truncated for the rest of the TTL, so that persist is
                // skipped in this case.
                val backfillIncomplete = hasMore
                SelectedCountryStore.saveSelectionPreservingIndex(appContext, countryName, accumulatedLegacy.values.toList())
                if (!backfillIncomplete) {
                    repo.persistFullServerList(appContext, resolvedCode, accumulatedV2.values.toList())
                } else {
                    AppLog.w(
                        TAG,
                        "Silent backfill for country=$countryName stopped early (incomplete) after pagesFetched=$pagesFetched; not caching as the country's full list"
                    )
                }
                AppLog.i(
                    TAG,
                    "Silent backfill complete: country=$countryName totalServers=${accumulatedLegacy.size} pagesFetched=$pagesFetched incomplete=$backfillIncomplete"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Silent backfill failed for country=$countryName", e)
            }
        }
    }
}


