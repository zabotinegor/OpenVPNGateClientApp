package com.yahorzabotsin.openvpnclientgate.core.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.ui.common.components.ConnectionControlsUseCase
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val selectionInteractor: MainSelectionInteractor,
    private val connectionInteractor: MainConnectionInteractor,
    private val connectionStateProvider: VpnConnectionStateProvider,
    private val logger: MainLogger,
    private val connectionControlsUseCase: ConnectionControlsUseCase
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<MainEffect>()
    val effects = _effects.asSharedFlow()

    fun onAction(action: MainAction) {
        when (action) {
            MainAction.LoadInitialSelection -> loadInitialSelection()
            is MainAction.NavigationItemSelected -> onNavigationItemSelected(action.itemId)
            MainAction.OpenServerListFromConnectionControls -> onOpenServerListFromConnectionControls()
            is MainAction.ConnectionButtonClicked -> onConnectionButtonClicked(
                hasNotificationPermission = action.hasNotificationPermission,
                hasVpnPermission = action.hasVpnPermission
            )
            is MainAction.OnServerSelectionResult -> onServerSelectionResult(action.selection)
            is MainAction.OnMultiWindowModeChanged -> onMultiWindowModeChanged(action.isInMultiWindowMode)
        }
    }

    private fun loadInitialSelection() {
        viewModelScope.launch {
            try {
                val cacheOnly = connectionStateProvider.isConnected()
                val selection = selectionInteractor.loadInitialSelection(cacheOnly = cacheOnly) ?: return@launch
                logger.logInitialSelectionLoaded(selection)
                updateSelectedServer(
                    country = selection.country,
                    countryCode = selection.countryCode,
                    config = selection.config,
                    ip = selection.ip,
                    fromUserSelection = false
                )
            } catch (e: Exception) {
                logger.logInitialSelectionError(e)
            }
        }
    }

    private fun updateSelectedServer(
        country: String,
        countryCode: String?,
        config: String,
        ip: String?,
        fromUserSelection: Boolean
    ) {
        val nextVersion = _state.value.selectionVersion + 1
        _state.value = _state.value.copy(
            selectionVersion = nextVersion,
            pendingUserSelectionOverride = fromUserSelection,
            selectedServer = MainSelectedServer(
                country = country,
                countryCode = countryCode,
                config = config,
                ip = ip,
                fromUserSelection = fromUserSelection,
                version = nextVersion
            )
        )
    }

    private fun onNavigationItemSelected(itemId: Int) {
        viewModelScope.launch {
            when (itemId) {
                R.id.nav_server -> {
                    _state.value = _state.value.copy(reopenDrawerAfterReturn = true)
                    _effects.emit(
                        MainEffect.OpenDestination(
                            destination = MainDestination.ServerList,
                            reopenDrawerAfterReturn = true
                        )
                    )
                }
                R.id.nav_dns -> _effects.emit(MainEffect.OpenDestination(MainDestination.Dns))
                R.id.nav_filter -> _effects.emit(MainEffect.OpenDestination(MainDestination.Filter))
                R.id.nav_settings -> _effects.emit(MainEffect.OpenDestination(MainDestination.Settings))
                R.id.nav_about -> _effects.emit(MainEffect.OpenDestination(MainDestination.About))
                else -> _effects.emit(MainEffect.ShowToast(UiText.Res(R.string.feature_in_development)))
            }
            _effects.emit(MainEffect.CloseDrawer)
        }
    }

    private fun onOpenServerListFromConnectionControls() {
        _state.value = _state.value.copy(
            reopenDrawerAfterReturn = false,
            pendingUserSelectionOverride = false
        )
        viewModelScope.launch {
            _effects.emit(
                MainEffect.OpenDestination(
                    destination = MainDestination.ServerList,
                    reopenDrawerAfterReturn = false
                )
            )
        }
    }

    private fun onConnectionButtonClicked(hasNotificationPermission: Boolean, hasVpnPermission: Boolean) {
        viewModelScope.launch {
            when (connectionStateProvider.state.value) {
                ConnectionState.CONNECTED,
                ConnectionState.CONNECTING,
                ConnectionState.DISCONNECTING -> {
                    _effects.emit(MainEffect.StopVpn)
                }
                ConnectionState.DISCONNECTED -> {
                    val selected = _state.value.selectedServer
                    if (selected?.config.isNullOrBlank()) {
                        _effects.emit(MainEffect.ShowToast(UiText.Res(R.string.select_server_first)))
                        return@launch
                    }
                    if (!hasNotificationPermission) {
                        _effects.emit(MainEffect.RequestNotificationPermission)
                        return@launch
                    }
                    if (!hasVpnPermission) {
                        _effects.emit(MainEffect.RequestVpnPermission)
                        return@launch
                    }

                    val prepared = connectionInteractor.prepareStart(
                        selectedServer = selected,
                        preferUserSelection = _state.value.pendingUserSelectionOverride
                    )
                    if (prepared == null) {
                        _effects.emit(MainEffect.ShowToast(UiText.Res(R.string.select_server_first)))
                        return@launch
                    }

                    updateSelectedServer(
                        country = selected!!.country,
                        countryCode = selected.countryCode,
                        config = prepared.config,
                        ip = prepared.ip ?: selected.ip,
                        fromUserSelection = false
                    )
                    _effects.emit(MainEffect.StartVpn(prepared.config, prepared.country))
                }
            }
        }
    }

    private fun onServerSelectionResult(selection: SelectedServerResult?) {
        viewModelScope.launch {
            val hasSelectionData = selection?.country != null &&
                selection.city != null &&
                selection.config != null

            if (hasSelectionData) {
                logger.logServerSelectionApplied(selection!!)
                val previousConfig = _state.value.selectedServer?.config
                updateSelectedServer(
                    country = selection.country,
                    countryCode = selection.countryCode,
                    config = selection.config,
                    ip = selection.ip,
                    fromUserSelection = true
                )
                val shouldStopForUserSelection = connectionControlsUseCase.shouldStopForUserSelection(
                    state = connectionStateProvider.state.value,
                    previousConfig = previousConfig,
                    newConfig = selection.config
                )
                if (shouldStopForUserSelection) {
                    _effects.emit(MainEffect.StopVpn)
                }
            } else if (selection != null) {
                logger.logIncompleteServerSelection(selection)
            }

            if (_state.value.reopenDrawerAfterReturn) {
                _effects.emit(MainEffect.ReopenDrawer)
            } else {
                _effects.emit(MainEffect.RequestPrimaryFocus)
            }
            _state.value = _state.value.copy(reopenDrawerAfterReturn = false)
        }
    }

    private fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        _state.value = _state.value.copy(isDetailsVisible = !isInMultiWindowMode)
    }
}
