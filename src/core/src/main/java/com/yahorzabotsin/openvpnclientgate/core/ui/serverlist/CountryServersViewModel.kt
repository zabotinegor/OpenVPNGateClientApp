package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryServersInteractor
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CountryServersViewModel(
    private val interactor: CountryServersInteractor,
    private val connectionStateProvider: VpnConnectionStateProvider,
    private val logger: CountryServersLogger
) : ViewModel() {

    private val _state = MutableStateFlow(CountryServersUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CountryServersEffect>()
    val effects = _effects.asSharedFlow()

    private var initialized = false

    fun onAction(action: CountryServersAction) {
        when (action) {
            is CountryServersAction.Initialize -> onInitialize(action.countryName, action.countryCode)
            is CountryServersAction.ServerSelected -> onServerSelected(action.server)
        }
    }

    private fun onInitialize(countryName: String?, countryCode: String?) {
        if (initialized) return
        initialized = true

        if (countryName.isNullOrBlank()) {
            viewModelScope.launch { _effects.emit(CountryServersEffect.FinishCanceled) }
            return
        }

        _state.value = _state.value.copy(
            countryName = countryName,
            countryCode = countryCode
        )

        loadServers(countryName)
    }

    private fun loadServers(countryName: String) {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            updateState { it.copy(isLoading = true) }
            try {
                val loaded = interactor.getServersForCountry(
                    countryName = countryName,
                    cacheOnly = connectionStateProvider.isConnected()
                )
                if (loaded.isEmpty()) {
                    logger.logNoServers(countryName)
                    _effects.emit(CountryServersEffect.ShowToast(R.string.no_servers_for_country))
                    _effects.emit(CountryServersEffect.FinishCanceled)
                } else {
                    logger.logLoadSuccess(countryName, loaded.size)
                    updateState { it.copy(servers = loaded) }
                    _effects.emit(CountryServersEffect.FocusFirstItem)
                }
            } catch (e: Exception) {
                logger.logLoadError(countryName, e)
                _effects.emit(CountryServersEffect.ShowSnackbar(R.string.error_getting_servers))
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
                _effects.emit(CountryServersEffect.ShowSnackbar(R.string.error_getting_servers))
                _effects.emit(CountryServersEffect.FinishCanceled)
            } finally {
                updateState { it.copy(isLoading = false) }
            }
        }
    }

    private fun updateState(block: (CountryServersUiState) -> CountryServersUiState) {
        _state.value = block(_state.value)
    }
}
