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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CountryServersViewModel(
    private val interactor: CountryServersInteractor,
    private val connectionStateProvider: VpnConnectionStateProvider,
    private val logger: CountryServersLogger,
    private val favoritesStore: FavoritesServerStore
) : ViewModel() {

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
                val page = interactor.getServersPage(
                    countryName = countryName,
                    countryCode = countryCode,
                    skip = 0,
                    take = pageSize,
                    cacheOnly = cacheOnly
                )
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
        // an in-flight page fetch, or a page that already reported the list is complete (AC3).
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
        viewModelScope.launch {
            updateState { it.copy(isLoadingMore = true, pageLoadError = false) }
            try {
                val vpnConnected = connectionStateProvider.isConnected()
                val cacheOnly = ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(vpnConnected)
                val page = interactor.getServersPage(
                    countryName = countryName,
                    countryCode = countryCode,
                    skip = skip,
                    take = pageSize,
                    cacheOnly = cacheOnly
                )
                logger.logLoadSuccess(countryName, page.servers.size)
                updateState {
                    it.copy(
                        servers = it.servers + page.servers,
                        hasMorePages = page.hasMore,
                        nextSkip = page.nextSkip,
                        pageLoadError = false
                    )
                }
            } catch (e: Exception) {
                // AC4: surface a retry affordance instead of silently stalling; keep nextSkip
                // untouched so retry resumes from the same page.
                logger.logLoadError(countryName, e)
                updateState { it.copy(pageLoadError = true) }
            } finally {
                updateState { it.copy(isLoadingMore = false) }
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
                    selectedServer = server
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

    private fun updateState(block: (CountryServersUiState) -> CountryServersUiState) {
        _state.value = block(_state.value).derived()
    }

    private fun CountryServersUiState.derived(): CountryServersUiState =
        copy(items = buildItems(servers, favoriteServerIds, isLoadingMore, pageLoadError))

    private fun buildItems(
        servers: List<Server>,
        favoriteServerIds: Set<Int>,
        isLoadingMore: Boolean,
        pageLoadError: Boolean
    ): List<ServerListItem> {
        if (servers.isEmpty()) return emptyList()

        // Mirrors ServerListViewModel.buildItems() (SUB-02 countries screen): the pinned
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
            // SUB-09 AC3/AC4: second header above the full list below, only shown alongside
            // the pinned Favorites block. Labeled "All servers" (not "Other") since favorited
            // servers still also appear in the list that follows.
            items.add(ServerListItem.SectionHeader(UiText.Res(R.string.all_servers_section_title)))
        }
        servers.forEach { server ->
            items.add(ServerListItem.ServerRow(server, isFavorite = server.id in favoriteServerIds))
        }
        // AC2/AC4: loading-footer row appended while a next-page fetch is in flight or has
        // failed mid-scroll. AC3 (no footer once every server has loaded) falls out naturally
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
