package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
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

class ServerListViewModel(
    private val interactor: ServerListInteractor,
    private val connectionStateProvider: VpnConnectionStateProvider,
    private val logger: ServerListLogger
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
        }
    }

    private fun observeConnectionState() {
        viewModelScope.launch {
            connectionStateProvider.state.collect { state ->
                val connected = state == ConnectionState.CONNECTED
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
                val loaded = interactor.getServers(forceRefresh, cacheOnly)
                servers = loaded
                logger.logLoadSuccess(loaded.size)
                val countries = loaded
                    .groupBy { it.country }
                    .map { (country, serversByCountry) ->
                        CountryWithServers(country, serversByCountry.size)
                    }
                    .sortedBy { it.country.name }
                updateState { it.copy(countries = countries) }
                _effects.emit(ServerListEffect.FocusFirstItem)
            } catch (e: Exception) {
                logger.logLoadError(e)
                _effects.emit(ServerListEffect.ShowSnackbar(UiText.Res(R.string.error_getting_servers)))
            } finally {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    private fun handleCountrySelection(selected: Country) {
        val countryName = selected.name
        val countryCode = selected.code
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

    private fun ServerListUiState.derived(): ServerListUiState =
        copy(
            isRefreshEnabled = !isLoading && !ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(isVpnConnected),
            showRefreshHint = ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(isVpnConnected)
        ).also {
            if (!isLoading) {
                logDebug(
                    tag,
                    "Derived refresh UI state. vpn_connected=$isVpnConnected, refresh_enabled=${it.isRefreshEnabled}, show_hint=${it.showRefreshHint}"
                )
            }
        }

    private fun logInfo(message: String) {
        runCatching { AppLog.i(tag, message) }
    }

    private fun logDebug(logTag: String, message: String) {
        runCatching { AppLog.d(logTag, message) }
    }
}
