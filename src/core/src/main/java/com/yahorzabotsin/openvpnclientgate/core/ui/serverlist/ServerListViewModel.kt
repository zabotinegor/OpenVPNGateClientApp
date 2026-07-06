package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.FavoritesCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerListInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.ServerRefreshFeatureFlags
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.Locale

class ServerListViewModel(
    private val interactor: ServerListInteractor,
    private val connectionStateProvider: VpnConnectionStateProvider,
    private val logger: ServerListLogger,
    private val favoritesStore: FavoritesCountryStore
) : ViewModel() {

    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ServerListViewModel"

    private val _state = MutableStateFlow(
        ServerListUiState(isVpnConnected = connectionStateProvider.isConnected()).derived()
    )
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ServerListEffect>(
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val effects = _effects.asSharedFlow()

    private var servers: List<Server> = emptyList()

    init {
        observeConnectionState()
        loadServers(forceRefresh = false)
    }

    fun onAction(action: ServerListAction) {
        when (action) {
            is ServerListAction.Load -> loadServers(action.forceRefresh)
            is ServerListAction.CountrySelected -> handleCountrySelection(action.country)
            is ServerListAction.ToggleFavorite -> toggleFavorite(action.country)
        }
    }

    private fun toggleFavorite(country: Country) {
        val code = country.code
        if (code.isNullOrBlank()) return
        val currentlyFavorite = favoritesStore.isFavoriteCountry(code)
        if (currentlyFavorite) {
            favoritesStore.removeFavoriteCountry(code)
        } else {
            favoritesStore.addFavoriteCountry(code)
        }
        viewModelScope.launch {
            val text = if (currentlyFavorite) {
                UiText.Res(R.string.favorites_removed_toast)
            } else {
                UiText.Res(R.string.favorites_added_toast)
            }
            _effects.emit(ServerListEffect.ShowToast(text))
        }
        updateState { it.copy(favoriteCountryCodes = favoritesStore.getFavoriteCountryCodes()) }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionStateProvider.state.collect { state ->
                val connected =
                    state == ConnectionState.CONNECTED ||
                    state == ConnectionState.PAUSING ||
                    state == ConnectionState.PAUSED
                updateState { it.copy(isVpnConnected = connected) }
            }
        }
    }

    private fun loadServers(forceRefresh: Boolean) {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            updateState { it.copy(isLoading = true) }
            try {
                val vpnConnected = _state.value.isVpnConnected
                val cacheOnly = ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(vpnConnected)
                logInfo("Loading servers. force_refresh=$forceRefresh, vpn_connected=$vpnConnected, cache_only=$cacheOnly")

                val countries: List<CountryWithServers>
                if (interactor.isDefaultV2Source()) {
                    val v2Countries = interactor.getCountriesV2(forceRefresh, cacheOnly)
                    countries = v2Countries.map { cv2 ->
                        CountryWithServers(
                            country = com.yahorzabotsin.openvpnclientgate.core.servers.Country(
                                name = cv2.name,
                                code = cv2.code
                            ),
                            serverCount = cv2.serverCount
                        )
                    }.sortedBy { it.country.name }
                    servers = emptyList()
                } else {
                    val loaded = interactor.getServers(forceRefresh, cacheOnly)
                    servers = loaded
                    logger.logLoadSuccess(loaded.size)
                    countries = loaded
                        .groupBy { it.country }
                        .map { (country, serversByCountry) ->
                            CountryWithServers(country, serversByCountry.size)
                        }
                        .sortedBy { it.country.name }
                }

                updateState {
                    it.copy(
                        countries = countries,
                        favoriteCountryCodes = favoritesStore.getFavoriteCountryCodes()
                    )
                }

                // Compute focus position: skip SectionHeader at position 0 when favorites exist
                val currentItems = _state.value.items
                if (currentItems.isNotEmpty()) {
                    val focusPosition = if (currentItems[0] is CountryListItem.SectionHeader) {
                        1  // Focus first CountryRow after the header
                    } else {
                        0  // Focus first item (should be a CountryRow)
                    }
                    _effects.emit(ServerListEffect.FocusFirstItem(focusPosition))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (shouldSuppressRecoverableV2CountriesError(e)) {
                    logInfo("DEFAULT_V2 countries load failed without cache; keeping empty list")
                    updateState { it.copy(countries = emptyList()) }
                } else {
                    logger.logLoadError(e)
                    _effects.emit(ServerListEffect.ShowSnackbar(UiText.Res(R.string.error_getting_servers)))
                }
            } finally {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    private fun shouldSuppressRecoverableV2CountriesError(error: Exception): Boolean {
        if (!interactor.isDefaultV2Source()) return false
        if (error !is IOException) return false
        val message = error.message?.lowercase(Locale.ROOT) ?: return false
        return message.contains("getcountries[locale=") &&
            message.contains("network failed and no cache available")
    }

    private fun handleCountrySelection(selected: Country) {
        val countryName = selected.name
        val countryCode = selected.code
        if (interactor.isDefaultV2Source()) {
            // For v2, always open the server list screen — servers are loaded lazily per country
            viewModelScope.launch {
                _effects.emit(ServerListEffect.OpenCountryServers(countryName, countryCode))
            }
            return
        }
        val countryServers = servers.filter { it.country.name == countryName }
        if (countryServers.isEmpty()) {
            logger.logNoServers(countryName)
            viewModelScope.launch {
                _effects.emit(ServerListEffect.ShowToast(UiText.Res(R.string.no_servers_for_country)))
                _effects.emit(ServerListEffect.FinishCanceled)
            }
            return
        }

        if (countryServers.size == 1) {
            viewModelScope.launch {
                updateState { it.copy(isLoading = true) }
                try {
                    val result = interactor.resolveSelection(
                        countryName = countryName,
                        countryCode = countryCode,
                        server = countryServers.first(),
                        countryServers = countryServers
                    )
                    _effects.emit(ServerListEffect.FinishWithSelection(result))
                } catch (e: Exception) {
                    logger.logSelectionError(countryName, e)
                    _effects.emit(ServerListEffect.ShowSnackbar(UiText.Res(R.string.error_getting_servers)))
                    _effects.emit(ServerListEffect.SetResultCanceled)
                } finally {
                    updateState { it.copy(isLoading = false) }
                }
            }
        } else {
            viewModelScope.launch {
                _effects.emit(ServerListEffect.OpenCountryServers(countryName, countryCode))
            }
        }
    }

    private fun updateState(block: (ServerListUiState) -> ServerListUiState) {
        _state.value = block(_state.value).derived()
    }

    private fun ServerListUiState.derived(): ServerListUiState {
        val cacheOnly = ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(isVpnConnected)
        return copy(
            isRefreshEnabled = !isLoading && !cacheOnly,
            showRefreshHint = cacheOnly,
            items = buildItems(countries, favoriteCountryCodes)
        )
    }

    private fun buildItems(
        countries: List<CountryWithServers>,
        favoriteCountryCodes: Set<String>
    ): List<CountryListItem> {
        if (countries.isEmpty()) return emptyList()

        val upperCaseFavorites = favoriteCountryCodes.map { it.uppercase(Locale.ROOT) }.toSet()
        val favorites = if (upperCaseFavorites.isEmpty()) {
            emptyList()
        } else {
            countries.filter { cws ->
                val code = cws.country.code
                !code.isNullOrBlank() && code.uppercase(Locale.ROOT) in upperCaseFavorites
            }
        }

        val items = mutableListOf<CountryListItem>()
        if (favorites.isNotEmpty()) {
            items.add(CountryListItem.SectionHeader(UiText.Res(R.string.favorites_section_title)))
            favorites.forEach { cws ->
                items.add(CountryListItem.CountryRow(cws, isFavorite = true))
            }
        }
        countries.forEach { cws ->
            val code = cws.country.code
            val isFavorite = !code.isNullOrBlank() && code.uppercase(Locale.ROOT) in upperCaseFavorites
            items.add(CountryListItem.CountryRow(cws, isFavorite = isFavorite))
        }
        return items
    }

    private fun logInfo(message: String) {
        runCatching { AppLog.i(tag, message) }
    }

}
