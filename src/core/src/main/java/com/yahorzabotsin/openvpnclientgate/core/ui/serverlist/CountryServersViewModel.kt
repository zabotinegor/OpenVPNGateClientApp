package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryServersInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.FavoritesFilter
import com.yahorzabotsin.openvpnclientgate.core.servers.FavoritesServerStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.ServerRefreshFeatureFlags
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class CountryServersViewModel(
    private val interactor: CountryServersInteractor,
    private val connectionStateProvider: VpnConnectionStateProvider,
    private val logger: CountryServersLogger,
    private val favoritesStore: FavoritesServerStore,
    // Paging state mutations must never execute inline inside RecyclerView's
    // scroll-callback frame. viewModelScope uses Dispatchers.Main.immediate, which runs
    // synchronously when already on main -- so a LoadNextPage triggered from onScrolled
    // appended the LoadingFooter via notifyDataSetChanged() during an active measure &
    // layout pass, tripping RecyclerView's "Cannot call this method in a scroll callback"
    // soft assertion (reproduced on both touch fling and TV D-pad). Non-immediate
    // Dispatchers.Main always dispatches through the main queue, deferring the mutation to
    // a later frame. Injectable so tests can pin the deferral with a controlled scheduler.
    private val pagingScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
) : ViewModel() {

    // The state-based duplicate-trigger guards (isLoadingMore) are only updated from
    // inside the deferred paging coroutine, so two back-to-back triggers landing in the same
    // frame both pass them and double-fetch the same skip. This claim is checked-and-set
    // SYNCHRONOUSLY in loadNextPage() -- the caller's frame -- closing that window entirely;
    // released when the fetch settles (including cancellation and failure).
    private val pageFetchInFlight = AtomicBoolean(false)

    // This screen's paging session identity. The
    // repository keys its accumulation state by it, making overlapping country screens fully
    // independent, and teardown releases exactly this session (no shared key to overwrite or
    // cross-abandon; works even when the screen was opened by name without a country code).
    private val pagingSessionId: String = java.util.UUID.randomUUID().toString()

    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "CountryServersViewModel"

    private val _state = MutableStateFlow(CountryServersUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CountryServersEffect>()
    val effects = _effects.asSharedFlow()

    fun onAction(action: CountryServersAction) {
        when (action) {
            is CountryServersAction.Initialize -> onInitialize(action.countryName, action.countryCode, action.pageSize)
            is CountryServersAction.ServerSelected -> onServerSelected(action.server)
            is CountryServersAction.ToggleFavorite -> onToggleFavorite(action.server)
            CountryServersAction.LoadNextPage -> onLoadNextPage()
            CountryServersAction.RetryLoadNextPage -> onRetryLoadNextPage()
        }
    }

    private fun onInitialize(countryName: String?, countryCode: String?, pageSize: Int) {
        if (_state.value.countryName != null) return

        if (countryName.isNullOrBlank()) {
            viewModelScope.launch { _effects.emit(CountryServersEffect.FinishCanceled) }
            return
        }

        _state.value = _state.value.copy(
            countryName = countryName,
            countryCode = countryCode,
            pageSize = pageSize.coerceAtLeast(1)
        )

        loadFirstPage(countryName, countryCode)
    }

    private fun loadFirstPage(countryName: String, countryCode: String?) {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            updateState { it.copy(isLoading = true, pageLoadError = false) }
            try {
                val vpnConnected = connectionStateProvider.isConnected()
                val cacheOnly = ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(vpnConnected)
                val pageSize = _state.value.pageSize
                logInfo(
                    "Loading country servers. country=$countryName, vpn_connected=$vpnConnected, " +
                        "cache_only=$cacheOnly, page_size=$pageSize"
                )
                var page = interactor.getServersPage(
                    countryName = countryName,
                    countryCode = countryCode,
                    skip = 0,
                    take = pageSize,
                    cacheOnly = cacheOnly,
                    pagingSessionId = pagingSessionId
                )
                // A raw page can filter down to zero displayable servers (e.g. a page made
                // entirely of blank-configData entries) while more pages remain. Mirror
                // fetchAllPages' guard (ServersV2Repository's raw-page-size exit
                // condition) and keep paging past such pages instead of treating this as "no
                // servers for this country", which would incorrectly close the screen.
                // Guard against a page whose nextSkip does not advance past the skip that
                // produced it (an empty `items` array while the backend still reports
                // total > skip) -- without this, the loop above would re-issue the identical
                // request, bounded only by the repository's 200-page safety limit.
                var previousSkip = 0
                while (page.servers.isEmpty() && page.hasMore && page.nextSkip > previousSkip) {
                    previousSkip = page.nextSkip
                    page = interactor.getServersPage(
                        countryName = countryName,
                        countryCode = countryCode,
                        skip = page.nextSkip,
                        take = pageSize,
                        cacheOnly = cacheOnly,
                        pagingSessionId = pagingSessionId
                    )
                }
                if (page.servers.isEmpty()) {
                    logger.logNoServers(countryName)
                    _effects.emit(CountryServersEffect.ShowToast(UiText.Res(R.string.no_servers_for_country)))
                    _effects.emit(CountryServersEffect.FinishCanceled)
                } else {
                    logger.logLoadSuccess(countryName, page.servers.size)
                    updateState {
                        it.copy(
                            servers = page.servers,
                            favoriteServerIds = favoritesStore.getFavoriteServerIds(),
                            hasMorePages = page.hasMore,
                            nextSkip = page.nextSkip,
                            pageLoadError = false
                        )
                    }
                    // Compute focus position: skip SectionHeader at position 0 when favorites exist
                    val currentItems = _state.value.items
                    if (currentItems.isNotEmpty()) {
                        val focusPosition = if (currentItems[0] is ServerListItem.SectionHeader) {
                            1  // Focus first ServerRow after the header
                        } else {
                            0  // Focus first item (should be a ServerRow)
                        }
                        _effects.emit(CountryServersEffect.FocusFirstItem(focusPosition))
                    }
                }
            } catch (e: Exception) {
                logger.logLoadError(countryName, e)
                _effects.emit(CountryServersEffect.ShowSnackbar(UiText.Res(R.string.error_getting_servers)))
                _effects.emit(CountryServersEffect.FinishCanceled)
            } finally {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    private fun onLoadNextPage() {
        val snapshot = _state.value
        val countryName = snapshot.countryName
        if (countryName.isNullOrBlank()) return
        // Guard against duplicate triggers: repeated onScrolled callbacks near the bottom,
        // an in-flight page fetch, or a page that already reported the list is complete.
        if (snapshot.isLoading || snapshot.isLoadingMore || snapshot.pageLoadError) return
        if (!snapshot.hasMorePages) return
        loadNextPage(countryName, snapshot.countryCode, snapshot.nextSkip, snapshot.pageSize)
    }

    private fun onRetryLoadNextPage() {
        val snapshot = _state.value
        val countryName = snapshot.countryName
        if (countryName.isNullOrBlank()) return
        if (snapshot.isLoading || snapshot.isLoadingMore) return
        if (!snapshot.pageLoadError) return
        loadNextPage(countryName, snapshot.countryCode, snapshot.nextSkip, snapshot.pageSize)
    }

    private fun loadNextPage(countryName: String, countryCode: String?, skip: Int, pageSize: Int) {
        // Run on pagingScope (non-immediate main), never inline in the scroll frame.
        // Claim synchronously BEFORE dispatching -- the state-based guards above were
        // read before this call and isLoadingMore only becomes true once the deferred body
        // runs, so back-to-back triggers could both launch. The atomic claim closes that.
        if (!pageFetchInFlight.compareAndSet(false, true)) return
        pagingScope.launch {
            updateState { it.copy(isLoadingMore = true, pageLoadError = false) }
            try {
                val vpnConnected = connectionStateProvider.isConnected()
                val cacheOnly = ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(vpnConnected)
                var page = interactor.getServersPage(
                    countryName = countryName,
                    countryCode = countryCode,
                    skip = skip,
                    take = pageSize,
                    cacheOnly = cacheOnly,
                    pagingSessionId = pagingSessionId
                )
                logger.logLoadSuccess(countryName, page.servers.size)
                // When the page is blocked (cache-only during VPN connected), skip the
                // drain loop and non-advancing cursor guard: the cursor must stay at the
                // last real offset so the next scroll retries the same page after VPN
                // disconnects, instead of advancing past real servers.
                var nonAdvancingCursor = false
                if (!page.blocked) {
                    // Mirror the initial-load drain: a raw page can consist entirely of
                    // blank-configData entries while the cursor advances. Keep fetching
                    // advancing empty pages here too, otherwise a user already at the loaded
                    // end never triggers another scroll callback and the remaining servers
                    // stay unreachable.
                    var lastSkip = skip
                    var drained = 0
                    while (page.hasMore && page.nextSkip > lastSkip && drained < MAX_EMPTY_PAGE_DRAIN) {
                        // A page can advance without contributing anything new: every entry
                        // may duplicate servers already shown (cross-page dedup). Keep
                        // draining through such pages, bounded per trigger -- a hostile or
                        // degenerate backend must not turn one scroll trigger into an
                        // unbounded request burst, and the preserved cursor lets the next
                        // trigger continue.
                        val knownKeys = _state.value.servers.mapTo(HashSet()) { dedupKey(it.id, it.ip, it.configData) }
                        val newRows = page.servers.count { dedupKey(it.id, it.ip, it.configData) !in knownKeys }
                        if (newRows > 0) break
                        drained++
                        lastSkip = page.nextSkip
                        page = interactor.getServersPage(
                            countryName = countryName,
                            countryCode = countryCode,
                            skip = page.nextSkip,
                            take = pageSize,
                            cacheOnly = cacheOnly,
                            pagingSessionId = pagingSessionId
                        )
                        logger.logLoadSuccess(countryName, page.servers.size)
                    }
                    // A misbehaving backend can also return a page whose cursor does not
                    // advance while still reporting more (total > skip). Committing that
                    // cursor would re-fetch the identical offset on every near-end scroll
                    // until the safety limit -- stop paging instead, mirroring the cursor
                    // guards used elsewhere.
                    nonAdvancingCursor = page.hasMore && page.nextSkip <= lastSkip
                    if (nonAdvancingCursor) {
                        runCatching { AppLog.w(tag, "loadNextPage: non-advancing cursor (skip=$lastSkip, nextSkip=${page.nextSkip}) -- stopping paging") }
                        // The session is terminal here: release its repository state
                        // immediately instead of waiting for teardown.
                        interactor.abandonPagingSession(pagingSessionId)
                    }
                }
                updateState {
                    it.copy(
                        servers = mergeServersDeduped(it.servers, page.servers),
                        hasMorePages = page.hasMore && !nonAdvancingCursor,
                        nextSkip = page.nextSkip,
                        pageLoadError = false
                    )
                }
            } catch (e: Exception) {
                // Surface a retry affordance instead of silently stalling; keep nextSkip
                // untouched so retry resumes from the same page.
                logger.logLoadError(countryName, e)
                updateState { it.copy(pageLoadError = true) }
            } finally {
                updateState { it.copy(isLoadingMore = false) }
                // Release only after the fetch settles so a trigger racing this
                // coroutine's tail frames cannot double-fire.
                pageFetchInFlight.set(false)
            }
        }
    }

    private fun onServerSelected(server: Server) {
        val snapshot = _state.value
        val countryName = snapshot.countryName
        if (snapshot.isLoading || countryName.isNullOrBlank()) return

        viewModelScope.launch {
            updateState { it.copy(isLoading = true) }
            try {
                val result = interactor.resolveSelection(
                    countryName = countryName,
                    countryCode = snapshot.countryCode,
                    servers = snapshot.servers,
                    selectedServer = server,
                    // Lets the interactor kick off a silent background backfill of the
                    // remaining pages when the user selects before the full list has loaded.
                    hasMorePages = snapshot.hasMorePages,
                    nextSkip = snapshot.nextSkip
                )
                _effects.emit(CountryServersEffect.FinishWithSelection(result))
            } catch (e: Exception) {
                logger.logSelectionError(server.ip, e)
                _effects.emit(CountryServersEffect.ShowSnackbar(UiText.Res(R.string.error_getting_servers)))
                _effects.emit(CountryServersEffect.FinishCanceled)
            } finally {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    private fun onToggleFavorite(server: Server) {
        val serverId = server.id
        if (serverId <= 0) return
        val currentlyFavorite = serverId in _state.value.favoriteServerIds
        if (currentlyFavorite) {
            favoritesStore.removeFavoriteServer(serverId)
        } else {
            favoritesStore.addFavoriteServer(serverId)
        }
        val text = if (currentlyFavorite) {
            UiText.Res(R.string.favorites_removed_toast)
        } else {
            UiText.Res(R.string.favorites_added_toast)
        }
        viewModelScope.launch { _effects.emit(CountryServersEffect.ShowToast(text)) }
        updateState { it.copy(favoriteServerIds = favoritesStore.getFavoriteServerIds()) }
    }

    /**
     * Releases the V2 repository's in-memory paging accumulator when the user leaves this
     * screen (back navigation, process death, or a completed server selection) before the
     * country's full list finished loading. Without this, a country left mid-scroll retained its
     * accumulated servers -- including full configData blobs -- for the entire process lifetime
     * (the repository is a Koin singleton). A session that reaches hasMore=false already cleans
     * up on its own inside ServersV2Repository.getServersPage().
     *
     * As of the accumulate=false fix, resolveSelection()'s silent background
     * backfill is fully session-isolated -- it fetches into its own local accumulator and
     * never reads or writes this screen's paging state. That means the backfill can never clean
     * up the foreground accumulator on this screen's behalf, so onCleared() is the *only*
     * release path left, for every teardown reason including a completed selection.
     *
     * Teardown is keyed by this screen's own
     * [pagingSessionId], so it releases exactly this screen's state -- overlapping sessions of
     * the same country are never disturbed, and screens opened by name without a country code
     * are cleaned up too.
     *
     * The call is unconditional: it is a safe no-op for sessions that already completed or were
     * released early (for example by the stop-paging branch), and it still cleans up paths where
     * the UI state never recorded more pages -- malformed or shifting responses can report
     * `hasMore=true` from the repository while the screen holds no paging state of its own.
     */
    override fun onCleared() {
        interactor.abandonPagingSession(pagingSessionId)
        // The paging scope is not viewModelScope, so it must be cancelled explicitly
        // to keep an in-flight deferred page fetch from outliving the ViewModel.
        pagingScope.cancel()
        super.onCleared()
    }

    private fun updateState(block: (CountryServersUiState) -> CountryServersUiState) {
        _state.value = block(_state.value).derived()
    }

    /** De-duplicates by server id when appending a newly-fetched page onto the servers
     * already loaded, keeping the first (earliest-loaded) occurrence. Offset-based pagination
     * over the backend's live active-server cache can otherwise yield the same server again at
     * a shifted offset once pages are fetched seconds-to-minutes apart (user scrolling) instead
     * of the old eager loop's milliseconds.
     *
     * Servers without a stable id (id <= 0) must not collapse onto the shared 0 key: they get
     * a fallback identity derived from their connection attributes instead. */
    private fun mergeServersDeduped(existing: List<Server>, incoming: List<Server>): List<Server> {
        if (incoming.isEmpty()) return existing
        val merged = LinkedHashMap<Any, Server>(existing.size + incoming.size)
        existing.forEach { merged[dedupKey(it.id, it.ip, it.configData)] = it }
        incoming.forEach { merged.putIfAbsent(dedupKey(it.id, it.ip, it.configData), it) }
        return merged.values.toList()
    }

    private fun dedupKey(id: Int, ip: String?, configData: String): Any =
        if (id > 0) id else com.yahorzabotsin.openvpnclientgate.core.servers.NoIdKey(ip, configData)

    private fun CountryServersUiState.derived(): CountryServersUiState =
        copy(items = buildItems(servers, favoriteServerIds, isLoadingMore, pageLoadError))

    private fun buildItems(
        servers: List<Server>,
        favoriteServerIds: Set<Int>,
        isLoadingMore: Boolean,
        pageLoadError: Boolean
    ): List<ServerListItem> {
        if (servers.isEmpty()) return emptyList()

        // Mirrors ServerListViewModel.buildItems() (countries screen): the pinned
        // favorites section is purely additive — favorited servers also stay at their
        // normal position in the regular list below, marked favorite by O(1) id lookup.
        val favorites = FavoritesFilter.filterFavoriteServers(favoriteServerIds, servers)

        val items = mutableListOf<ServerListItem>()
        if (favorites.isNotEmpty()) {
            items.add(
                ServerListItem.SectionHeader(
                    UiText.Res(R.string.favorites_section_title),
                    showFavoriteIcon = true
                )
            )
            favorites.forEach { server ->
                items.add(ServerListItem.ServerRow(server, isFavorite = true, isPinnedSection = true))
            }
            // Second header above the full list below, only shown alongside
            // the pinned Favorites block. Labeled "All servers" (not "Other") since favorited
            // servers still also appear in the list that follows.
            items.add(ServerListItem.SectionHeader(UiText.Res(R.string.all_servers_section_title)))
        }
        servers.forEach { server ->
            items.add(ServerListItem.ServerRow(server, isFavorite = server.id in favoriteServerIds))
        }
        // Loading-footer row appended while a next-page fetch is in flight or has
        // failed mid-scroll. No footer once every server has loaded falls out naturally
        // since neither flag is ever true once hasMorePages is false.
        if (isLoadingMore) {
            items.add(ServerListItem.LoadingFooter(FooterState.LOADING))
        } else if (pageLoadError) {
            items.add(ServerListItem.LoadingFooter(FooterState.ERROR))
        }
        return items
    }

    private fun logInfo(message: String) {
        runCatching { AppLog.i(tag, message) }
    }
}

/** Upper bound on advancing empty/duplicate-only pages drained within a single load-more
 * trigger: a degenerate backend must not turn one scroll trigger into an unbounded request
 * burst. When the bound is hit the cursor is preserved, so the next trigger continues. */
private const val MAX_EMPTY_PAGE_DRAIN = 10
