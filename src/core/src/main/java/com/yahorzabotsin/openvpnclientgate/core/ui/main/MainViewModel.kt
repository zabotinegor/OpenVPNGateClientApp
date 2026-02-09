package com.yahorzabotsin.openvpnclientgate.core.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val selectionInteractor: MainSelectionInteractor,
    private val connectionStateProvider: VpnConnectionStateProvider,
    private val logger: MainLogger
) : ViewModel() {

    private val _state = MutableStateFlow(MainUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<MainEffect>()
    val effects = _effects.asSharedFlow()

    private var reopenDrawerAfterReturn = false
    private var selectionVersion: Long = 0L

    fun onAction(action: MainAction) {
        when (action) {
            MainAction.LoadInitialSelection -> loadInitialSelection()
            is MainAction.NavigationItemSelected -> onNavigationItemSelected(action.itemId)
            MainAction.OpenServerListFromConnectionControls -> onOpenServerListFromConnectionControls()
            is MainAction.OnServerSelectionResult -> onServerSelectionResult(action.selection)
            is MainAction.OnVpnPermissionResult -> onVpnPermissionResult(action.granted)
            is MainAction.OnNotificationPermissionResult -> onNotificationPermissionResult(action.granted)
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
        selectionVersion += 1
        _state.value = _state.value.copy(
            selectedServer = MainSelectedServer(
                country = country,
                countryCode = countryCode,
                config = config,
                ip = ip,
                fromUserSelection = fromUserSelection,
                version = selectionVersion
            )
        )
    }

    private fun onNavigationItemSelected(itemId: Int) {
        viewModelScope.launch {
            when (itemId) {
                R.id.nav_server -> {
                    reopenDrawerAfterReturn = true
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
                else -> _effects.emit(MainEffect.ShowToast(R.string.feature_in_development))
            }
            _effects.emit(MainEffect.CloseDrawer)
        }
    }

    private fun onOpenServerListFromConnectionControls() {
        reopenDrawerAfterReturn = false
        viewModelScope.launch {
            _effects.emit(
                MainEffect.OpenDestination(
                    destination = MainDestination.ServerList,
                    reopenDrawerAfterReturn = false
                )
            )
        }
    }

    private fun onServerSelectionResult(selection: SelectedServerResult?) {
        viewModelScope.launch {
            val hasSelectionData = selection?.country != null &&
                selection.city != null &&
                selection.config != null

            if (hasSelectionData) {
                logger.logServerSelectionApplied(selection!!)
                updateSelectedServer(
                    country = selection.country,
                    countryCode = selection.countryCode,
                    config = selection.config,
                    ip = selection.ip,
                    fromUserSelection = true
                )
            } else if (selection != null) {
                logger.logIncompleteServerSelection(selection)
            }

            if (reopenDrawerAfterReturn) {
                _effects.emit(MainEffect.ReopenDrawer)
            } else {
                _effects.emit(MainEffect.RequestPrimaryFocus)
            }
            reopenDrawerAfterReturn = false
        }
    }

    private fun onVpnPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            if (granted) {
                _effects.emit(MainEffect.TriggerConnectionClick)
            } else {
                _effects.emit(MainEffect.ShowToast(R.string.vpn_permission_not_granted))
            }
        }
    }

    private fun onNotificationPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            if (granted) {
                _effects.emit(MainEffect.TriggerConnectionClick)
            } else {
                _effects.emit(MainEffect.ShowToast(R.string.notification_permission_required))
            }
        }
    }

    private fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        _state.value = _state.value.copy(isDetailsVisible = !isInMultiWindowMode)
    }
}
