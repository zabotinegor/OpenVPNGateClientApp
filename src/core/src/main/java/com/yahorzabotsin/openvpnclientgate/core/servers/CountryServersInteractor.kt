package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.settings.AppLocaleEpoch
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSourceEpoch
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
 *, expressed in the legacy [Server] shape used throughout the UI layer.
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
    val nextSkip: Int,
    val blocked: Boolean = false
)

interface CountryServersInteractor {
    suspend fun getServersForCountry(
        countryName: String,
        countryCode: String? = null,
        cacheOnly: Boolean
    ): List<Server>

    /**
     * Lazy-loading entry point: fetches one page of servers for [countryName]/
     * [countryCode] starting at [skip], up to [take] items.
     *
     * For the default V2 source, this genuinely fetches only the requested page over the
     * network when no fresh full-list cache exists, or serves the complete cached
     * list unchanged in one shot when a fresh cache is present (the already-fast path is
     * preserved). For every other source (VPN Gate/legacy, out of scope), this
     * always returns the complete country list as a single page with `hasMore = false`,
     * matching pre-existing behavior exactly.
     *
     * @param pagingSessionId identifies THIS screen's paging session; the repository keys its
     * accumulation state by it so overlapping sessions of the same country stay independent
     *), and teardown releases exactly this session via
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
     * complete list: the caller selected a server before every page finished
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
     * Best-effort cleanup hook: releases any in-memory V2 paging accumulator held
     * for [pagingSessionId] when the user leaves the country screen before its full list loaded
     * (and before a selection ever triggered [resolveSelection]'s own backfill, which cleans up
     * naturally once it completes). No-op for the legacy/VPN Gate source and when no state
     * exists for this session. Session-keyed cleanup works even when the
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
    companion object {
        private val TAG = LogTags.APP + ":CountryServersInteractor"
        private const val MAX_BACKFILL_PAGES_SAFETY_LIMIT = 200
    }

    // Outlives any single screen/ViewModel on purpose: the backfill triggered by
    // resolveSelection() must keep running after the country screen (and its viewModelScope)
    // has already finished/cleared following the user's selection.
    private val backfillScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Visible for test synchronization only: the backfill is fire-and-forget in production,
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
            // one shot.
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
        } else if (cacheOnly) {
            // Honor cache-only mode on every page: VPN connected after first page was
            // loaded -- stop network access but preserve the incomplete paging state so
            // paging resumes when VPN disconnects. Return blocked=true so the ViewModel
            // skips the drain loop and non-advancing cursor guard without advancing the
            // cursor past the last real response.
            return CountryServersPage(servers = emptyList(), hasMore = true, nextSkip = skip, blocked = true)
        }

        val page = try {
            repo.getServersPage(appContext, resolvedCode, skip = skip, take = take, pagingSessionId = pagingSessionId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (skip != 0) throw e
            // Offline fallback: restore the pre-existing offline fallback for the
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
            // Every selection supersedes any still-running backfill for the same country --
            // including this one, which may not launch a backfill of its own (hasMorePages =
            // false when the user scrolled the list to completion before choosing). Without this
            // bump, an earlier selection's backfill for the same country stays "current" per the
            // generation guard and lands its older pool on top of the selection below: with a
            // live server list that shifted in between, the newly selected server can be absent
            // from that older pool, so saveSelectionPreservingIndex fails to restore the index
            // and the persisted current server silently falls back to position 0.
            supersedeInFlightBackfills(countryName, countryCode)
        } else {
            val configs = serverRepository.loadConfigs(appContext, servers)
            resolvedServers = servers.map { server ->
                server.copy(configData = configs[server.lineIndex].orEmpty())
            }
        }

        SelectedCountryStore.saveSelection(appContext, countryName, resolvedServers)
        if (source == ServerSource.DEFAULT_V2 && hasMorePages) {
            // Backfill the remaining pages silently in the
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
        // Session-keyed: cleanup no longer depends on resolving a country
        // code, so screens opened by name without EXTRA_COUNTRY_CODE release their state too.
        serversV2Repository?.abandonPagingSession(pagingSessionId)
    }

    /**
     * Continues fetching this country's remaining pages on [backfillScope] --
     * independent of any ViewModel/screen scope, so it keeps running after the country screen
     * has already finished. Once the last page is reached, persists the full merged candidate
     * pool via [SelectedCountryStore.saveSelectionPreservingIndex] (preserves the just-set
     * current index as long as this is still the active selection), and separately persists the
     * same merged list as the country's on-disk full-list cache via
     * [ServersV2Repository.persistFullServerList]. Failures are logged and swallowed: a failed
     * backfill leaves the already-completed selection and its partial candidate pool untouched,
     * matching the "must not affect the completed selection" requirement.
     *
     * Fetches every page with `accumulate = false`
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
     * closing the concurrent-session hazard). This also makes the "accumulator leaked on
     * failure" concern moot: there is no repository-side accumulator entry for this session to
     * leak in the first place, so no `finally { abandonPagingSession(...) }` is needed here --
     * the local maps below are ordinary coroutine-local state, reclaimed on completion or
     * cancellation like any other.
     */
    /**
     * Advances [CountrySyncGenerations] for both keys a same-country backfill can be guarding on,
     * so any such backfill still in flight stands down instead of overwriting the selection that
     * is about to be written.
     *
     * Both keys are needed because [launchSilentBackfill] keys its launch generation by
     * `countryCode ?: countryName`: an in-flight backfill started from a screen opened *by name*
     * guards on the name key, while one started with a code guards on the code key. Bumping only
     * the key this call happens to have would leave the other kind of backfill unsuperseded.
     * Bumping a key nothing is guarding on is harmless -- generations are monotonic tickets that
     * are only ever compared for equality with a captured value.
     */
    private fun supersedeInFlightBackfills(countryName: String, countryCode: String?) {
        CountrySyncGenerations.supersede(countryName, countryCode)
    }

    private fun dedupKey(id: Int, ip: String?, configData: String): Any =
        if (id > 0) id else NoIdKey(ip, configData)

    private fun launchSilentBackfill(
        countryName: String,
        countryCode: String?,
        startSkip: Int,
        initialServers: List<Server>
    ) {
        val repo = serversV2Repository ?: return
        // Capture the generation at launch time (synchronously, before the coroutine starts) so
        // two backfills launched back-to-back for the same country stay strictly ordered: the
        // later launch always wins regardless of which coroutine is scheduled first.
        // When the caller has no countryCode (the country screen can be opened by name only),
        // the country code is not known until resolveCountryV2 runs inside the coroutine, so the
        // launch key falls back to the country name — see the code-key adoption below.
        val launchKey = CountrySyncGenerations.key(countryCode ?: countryName)
        val generation = CountrySyncGenerations.bump(launchKey)
        // Captured synchronously at launch, for the same reason as the generation above: both
        // are compared for equality later, so they must describe the world this backfill's seed
        // pages (initialServers) came from, not the world at the moment the coroutine starts.
        //
        // Server source: the live `serverSource == DEFAULT_V2` check below cannot see a source
        // that was switched away and back while this job was parked (the VPN Gate sync in
        // between already rewrote this country's pool), nor a switch whose sync was cancelled
        // before it advanced any generation. The epoch moves on every persisted source write.
        //
        // Locale: every ServersV2Repository call resolves the current locale independently, so a
        // language change mid-backfill would fetch the remaining pages in the new language,
        // merge them with the seed pages fetched in the old one, and persist that mixed list
        // under the NEW locale's cache key with a fresh timestamp. Relocalization does not
        // reliably bump a generation either -- it returns early on a fresh cache and is
        // cancelled on activity recreation -- so the locale is checked directly. The resolved
        // locale alone reads as unchanged after a language that moved away and back while a page
        // request was in flight, so the launch epoch is captured with it.
        val launchSourceEpoch = ServerSourceEpoch.current()
        val launchLocaleEpoch = AppLocaleEpoch.current()
        val launchLocale = UserSettingsStore.resolvePreferredLocale(appContext)
        lastBackfillJob = backfillScope.launch {
            try {
                val countryV2 = resolveCountryV2(repo, countryName, countryCode, cacheOnly = false)
                val resolvedCode = countryV2.code
                // A name-only launch keyed its generation by country NAME while every sync and
                // paging guard keys by country CODE, so a concurrent sync for this same country
                // would be invisible to the drift guard below. Now that the code is resolved,
                // adopt (and bump) the canonical code key too, and guard on BOTH: the launch key
                // preserves launch ordering between two backfills, the code key makes
                // code-keyed sync bumps visible.
                val codeKey = CountrySyncGenerations.key(resolvedCode)
                val codeGeneration: Long
                if (codeKey == launchKey) {
                    codeGeneration = generation
                } else {
                    // Adopting the code key must NOT stomp a bump that landed on it after this
                    // backfill launched. This backfill's seed pages (initialServers) were
                    // captured before that bump, so whoever bumped -- a same-country sync, or a
                    // newer backfill launched with the code -- holds fresher data and must win.
                    // Blindly bumping here would instead advance the counter past that sync,
                    // making it invisible to the drift guard below and letting these older pages
                    // overwrite the fresher selection/full-list cache. Generations are globally
                    // monotonic tickets, so "greater than our launch generation" means "issued
                    // after we launched" even across two different keys; the check and the claim
                    // are atomic, so a sync landing in between is not stomped either.
                    val adopted = CountrySyncGenerations.bumpUnlessBumpedSince(codeKey, generation)
                    if (adopted == null) {
                        AppLog.w(
                            TAG,
                            "Silent backfill for country=$countryName aborted: a newer sync/backfill claimed code=$resolvedCode while this backfill was resolving it"
                        )
                        return@launch
                    }
                    codeGeneration = adopted
                }
                fun isCurrentGeneration(): Boolean =
                    CountrySyncGenerations.current(launchKey) == generation &&
                        CountrySyncGenerations.current(codeKey) == codeGeneration
                // This backfill only ever exists for the DEFAULT_V2 source and carries V2 data.
                // Switching the source away from V2 mid-flight (Settings -> VPN Gate) makes every
                // remaining page of this job wrong for the pool it would write into: the
                // source-change sync repopulates the same country from the new source, and these
                // V2 rows landing afterwards would replace it -- resetting the active server to
                // index 0 whenever its config is absent from the V2 data. The source-change sync
                // path also supersedes this job's generation (see
                // CountrySyncGenerations.supersede), but that only helps when that sync actually
                // reaches its write: it returns early when the country is missing from the new
                // source's list or its configs fail to load. Rechecking the live source here
                // stands the job down in those cases too.
                //
                // The live read alone is a check-then-write against a value the Settings flow
                // can change in between, and it reads the same for a source that was switched
                // away and back. Pairing it with the launch epoch closes both: the epoch is
                // advanced by the source write itself, before the new value is published, so it
                // does not depend on the source-change sync ever reaching its own write.
                fun isSourceStillDefaultV2(): Boolean =
                    ServerSourceEpoch.current() == launchSourceEpoch &&
                        UserSettingsStore.load(appContext).serverSource == ServerSource.DEFAULT_V2
                // Pins this paging session to the language it started in: a mid-flight change
                // stands the job down instead of letting it merge pages fetched in two
                // languages and cache them under the new locale's key.
                //
                // The resolved-locale comparison alone is an equality check on a captured value,
                // so it cannot see a language that moved away and back (en -> ru -> en) while a
                // page request was in flight: that request resolves the locale on its own and
                // may have been served in the intermediate language, yet the comparison reads as
                // unchanged. The epoch advances on every language write, so away-and-back is
                // visible. The live comparison is still needed alongside it: an OS locale change
                // under the SYSTEM language option never goes through the settings store and so
                // advances no epoch.
                fun isLocaleUnchanged(): Boolean =
                    AppLocaleEpoch.current() == launchLocaleEpoch &&
                        UserSettingsStore.resolvePreferredLocale(appContext) == launchLocale
                fun mayStillWrite(): Boolean =
                    isCurrentGeneration() && isSourceStillDefaultV2() && isLocaleUnchanged()
                val accumulatedLegacy = LinkedHashMap<Any, Server>()
                val accumulatedV2 = LinkedHashMap<Any, ServerV2>()
                initialServers.forEach { server ->
                    val seedKey = dedupKey(server.id, server.ip, server.configData)
                accumulatedLegacy[seedKey] = server
                accumulatedV2[seedKey] = server.toServerV2(resolvedCode, countryV2.name)
            }
                var skip = startSkip
                var hasMore = true
                var pagesFetched = 0
                while (hasMore && pagesFetched < MAX_BACKFILL_PAGES_SAFETY_LIMIT) {
                    // Stop superseded work BEFORE fetching the next page, not only at the write
                    // guard below. The generation drifts the moment a newer selection or sync
                    // starts, but every page still queued here would otherwise be fetched and
                    // would contend on the repository's per-country mutex with the screen the
                    // user is actually looking at -- up to MAX_BACKFILL_PAGES_SAFETY_LIMIT pages
                    // of network and lock traffic whose results are guaranteed to be discarded.
                    // Bailing out here is safe precisely because the writes are already guarded:
                    // a drifted generation can never be un-drifted (tickets are monotonic), so
                    // this job had no reachable write left to perform.
                    if (!mayStillWrite()) {
                        AppLog.w(
                            TAG,
                            "Silent backfill for country=$countryName aborted after $pagesFetched page(s): generation drifted, server source changed, or app language changed"
                        )
                        return@launch
                    }
                    val page = repo.getServersPage(appContext, resolvedCode, skip = skip, accumulate = false)
                    page.servers.forEach { v2 ->
                        val pageKey = dedupKey(v2.id, v2.ip, v2.configData)
                accumulatedLegacy[pageKey] = v2.toLegacyServer()
                        accumulatedV2[pageKey] = v2
                    }
                    pagesFetched += 1
                    // Record the terminal status BEFORE the non-advancing-cursor guard: a
                    // final probe can legitimately return an empty page with hasMore=false
                    // and nextSkip == skip (count is an exact multiple of the page size and
                    // the API omits `total`) -- that is successful completion, not an
                    // incomplete backfill.
                    val pageHasMore = page.hasMore
                    if (page.nextSkip <= skip) {
                        // The cursor guard must not discard the terminal status recorded by
                        // this probe: an exact-multiple completion arrives exactly as an
                        // empty page with nextSkip == skip and hasMore = false.
                        hasMore = pageHasMore
                        break
                    }
                    skip = page.nextSkip
                    hasMore = pageHasMore
                }
                // Mirror ServersV2Repository.getServersPage's own safety-limit guard.
                // hasMore is still true here only when the loop above exited early -- either the
                // MAX_BACKFILL_PAGES_SAFETY_LIMIT bound was hit, or the non-advancing-cursor guard
                // (the non-advancing-cursor guard) broke out of it -- so the accumulated list is knowingly incomplete. The
                // partial candidate pool is still useful to ServerAutoSwitcher, so
                // saveSelectionPreservingIndex always runs; but persisting an incomplete list as
                // this country's authoritative on-disk full-list cache (with a fresh TTL stamp)
                // would silently strand it truncated for the rest of the TTL, so that persist is
                // skipped in this case.
                val backfillIncomplete = hasMore
                // Per-country generation guard: skip writes when a newer same-country
                // backfill or sync started (generation drifted on either key), when the user
                // switched the server source away from DEFAULT_V2 while this job was running,
                // or when the app language changed and these pages are no longer all in the
                // locale this session started in.
                val superseded = !mayStillWrite()
                if (superseded) {
                    AppLog.w(
                        TAG,
                        "Silent backfill for country=$countryName skipped its writes: generation drifted, server source changed, or app language changed"
                    )
                } else {
                    // The guard is re-evaluated INSIDE SelectedCountryStore's selection monitor,
                    // not just here: a newer selection bumps the generation before it takes that
                    // monitor to write its own pool, so a guard checked only out here could pass
                    // and then land on top of that newer selection. Checked under the monitor,
                    // this write either sees the bump and stands down, or completes before the
                    // newer selection's write -- which then wins by landing last.
                    SelectedCountryStore.saveSelectionPreservingIndex(
                        appContext,
                        countryName,
                        accumulatedLegacy.values.toList()
                    ) { mayStillWrite() }
                    if (!backfillIncomplete) {
                        // expectedLocale is the launch locale, not just the guard above: the
                        // repository resolves the locale itself to build the cache key, so a
                        // language change landing between the predicate and that resolution
                        // would otherwise still file this list under the new locale. Compared
                        // against the very value the write is keyed by, the check is exact.
                        repo.persistFullServerList(
                            appContext,
                            resolvedCode,
                            accumulatedV2.values.toList(),
                            expectedLocale = launchLocale
                        ) {
                            mayStillWrite()
                        }
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
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Silent backfill failed for country=$countryName", e)
            }
        }
    }
}


