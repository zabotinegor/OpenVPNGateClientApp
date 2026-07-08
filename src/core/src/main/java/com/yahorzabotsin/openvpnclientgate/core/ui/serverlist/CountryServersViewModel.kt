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
            is CountryServersAction.Initialize -> onInitialize(action.countryName, action.countryCode)
            is CountryServersAction.ServerSelected -> onServerSelected(action.server)
            is CountryServersAction.ToggleFavorite -> onToggleFavorite(action.server)
        }
    }

    private fun onInitialize(countryName: String?, countryCode: String?) {
        if (_state.value.countryName != null) return

        if (countryName.isNullOrBlank()) {
            viewModelScope.launch { _effects.emit(CountryServersEffect.FinishCanceled) }
            return
        }

        _state.value = _state.value.copy(
            countryName = countryName,
            countryCode = countryCode
        )

        loadServers(countryName, countryCode)
    }

    private fun loadServers(countryName: String, countryCode: String?) {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            updateState { it.copy(isLoading = true) }
            try {
                val vpnConnected = connectionStateProvider.isConnected()
                val cacheOnly = ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(vpnConnected)
                logInfo("Loading country servers. country=$countryName, vpn_connected=$vpnConnected, cache_only=$cacheOnly")
                val loaded = interactor.getServersForCountry(
                    countryName = countryName,
                    countryCode = countryCode,
                    cacheOnly = cacheOnly
                )
                if (loaded.isEmpty()) {
                    logger.logNoServers(countryName)
                    _effects.emit(CountryServersEffect.ShowToast(UiText.Res(R.string.no_servers_for_country)))
                    _effects.emit(CountryServersEffect.FinishCanceled)
                } else {
                    logger.logLoadSuccess(countryName, loaded.size)
                    updateState {
                        it.copy(
                            servers = loaded,
                            favoriteServerIds = favoritesStore.getFavoriteServerIds()
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
        val currentlyFavorite = favoritesStore.isFavoriteServer(serverId)
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
        copy(items = buildItems(servers, favoriteServerIds))

    private fun buildItems(
        servers: List<Server>,
        favoriteServerIds: Set<Int>
    ): List<ServerListItem> {
        if (servers.isEmpty()) return emptyList()

        val favorites = FavoritesFilter.filterFavoriteServers(favoriteServerIds, servers)
        val nonFavorites = servers.filter { it !in favorites }

        val items = mutableListOf<ServerListItem>()
        if (favorites.isNotEmpty()) {
            items.add(ServerListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)))
            favorites.forEach { server ->
                items.add(ServerListItem.ServerRow(server, isFavorite = true))
            }
        }
        nonFavorites.forEach { server ->
            items.add(ServerListItem.ServerRow(server, isFavorite = false))
        }
        return items
    }

    private fun logInfo(message: String) {
        runCatching { AppLog.i(tag, message) }
    }
}
