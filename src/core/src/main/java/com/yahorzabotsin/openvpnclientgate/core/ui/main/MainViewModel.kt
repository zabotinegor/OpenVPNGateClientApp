package com.yahorzabotsin.openvpnclientgate.core.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.servers.refresh.ServerRefreshFeatureFlags
import com.yahorzabotsin.openvpnclientgate.core.ui.common.navigation.MarkdownRenderer
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText
import com.yahorzabotsin.openvpnclientgate.vpn.VpnConnectionStateProvider
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainViewModel(
    private val selectionInteractor: MainSelectionInteractor,
    private val versionReleaseInteractor: VersionReleaseInteractor,
    private val updateCheckInteractor: UpdateCheckInteractor,
    private val connectionInteractor: MainConnectionInteractor,
    private val connectionStateProvider: VpnConnectionStateProvider,
    private val logger: MainLogger
) : ViewModel() {

    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "MainViewModel"

    private val _state = MutableStateFlow(MainUiState())
    val state = _state.asStateFlow()

    private val _effects = Channel<MainEffect>(capacity = Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()
    private var updatePromptShown = false

    fun onAction(action: MainAction) {
        when (action) {
            MainAction.LoadInitialSelection -> loadInitialSelection()
            MainAction.RefreshUpdateAvailability -> loadUpdateAvailability()
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
        loadWhatsNew()
        loadUpdateAvailability()
        viewModelScope.launch {
            try {
                val vpnConnected = connectionStateProvider.isConnected()
                val cacheOnly = ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(vpnConnected)
                logInfo("Initial selection load mode resolved. vpn_connected=$vpnConnected, cache_only=$cacheOnly")
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
                if (e is CancellationException) throw e
                logger.logInitialSelectionError(e)
            }
        }
    }

    private fun loadWhatsNew() {
        viewModelScope.launch {
            try {
                val latestRelease = versionReleaseInteractor.loadLatestRelease()
                if (latestRelease == null) {
                    logger.logWhatsNewUnavailable()
                    return@launch
                }

                val whatsNew = MainWhatsNew(
                    versionNumber = latestRelease.versionNumber,
                    name = latestRelease.name,
                    changelog = latestRelease.changelog,
                    changelogHtml = MarkdownRenderer.renderDocument(latestRelease.changelog)
                )
                logger.logWhatsNewLoaded(whatsNew)
                _state.value = _state.value.copy(whatsNew = whatsNew)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.logWhatsNewLoadError(e)
            }
        }
    }

    private fun loadUpdateAvailability() {
        viewModelScope.launch {
            try {
                val update = updateCheckInteractor.check(forceRefresh = true)
                if (update == null || !update.hasUpdate || update.asset?.downloadProxyUrl.isNullOrBlank()) {
                    logger.logUpdateUnavailable()
                    _state.value = _state.value.copy(availableUpdate = null)
                    return@launch
                }

                val availableUpdate = MainAvailableUpdate(
                    currentBuild = update.currentBuild,
                    latestBuild = update.latestBuild,
                    versionNumber = update.latestVersion ?: "",
                    name = update.name,
                    changelog = update.changelog,
                    assetId = update.asset?.id ?: 0,
                    assetName = update.asset?.name.orEmpty(),
                    assetPlatform = update.asset?.platform.orEmpty(),
                    assetBuildNumber = update.asset?.buildNumber,
                    assetType = update.asset?.assetType.orEmpty(),
                    assetSizeBytes = update.asset?.sizeBytes ?: 0L,
                    assetContentHash = update.asset?.contentHash.orEmpty(),
                    downloadProxyUrl = update.asset?.downloadProxyUrl.orEmpty(),
                    message = update.message
                )
                logger.logUpdateLoaded(availableUpdate)
                _state.value = _state.value.copy(availableUpdate = availableUpdate)
                if (!updatePromptShown) {
                    updatePromptShown = true
                    _effects.send(MainEffect.PromptUpdate(availableUpdate))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.logUpdateLoadError(e)
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
                    _effects.send(
                        MainEffect.OpenDestination(
                            destination = MainDestination.ServerList,
                            reopenDrawerAfterReturn = true
                        )
                    )
                }
                R.id.nav_dns -> _effects.send(MainEffect.OpenDestination(MainDestination.Dns))
                R.id.nav_filter -> _effects.send(MainEffect.OpenDestination(MainDestination.Filter))
                R.id.nav_settings -> _effects.send(MainEffect.OpenDestination(MainDestination.Settings))
                R.id.nav_about -> _effects.send(MainEffect.OpenDestination(MainDestination.About))
                R.id.nav_whats_new -> {
                    val whatsNew = _state.value.whatsNew
                    if (whatsNew != null) {
                        _effects.send(MainEffect.OpenDestination(MainDestination.WhatsNew(whatsNew)))
                    }
                }
                R.id.nav_update -> {
                    val update = _state.value.availableUpdate
                    if (update != null) {
                        _effects.send(MainEffect.PromptUpdate(update, oneTimeOnly = false))
                    }
                }
                else -> _effects.send(MainEffect.ShowToast(UiText.Res(R.string.feature_in_development)))
            }
            _effects.send(MainEffect.CloseDrawer)
        }
    }

    private fun onOpenServerListFromConnectionControls() {
        _state.value = _state.value.copy(
            reopenDrawerAfterReturn = false,
            pendingUserSelectionOverride = false
        )
        viewModelScope.launch {
            _effects.send(
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
                    _effects.send(MainEffect.StopVpn)
                }
                ConnectionState.DISCONNECTED -> {
                    val selected = _state.value.selectedServer
                    if (selected?.config.isNullOrBlank()) {
                        _effects.send(MainEffect.ShowToast(UiText.Res(R.string.select_server_first)))
                        return@launch
                    }
                    if (!hasNotificationPermission) {
                        _effects.send(MainEffect.RequestNotificationPermission)
                        return@launch
                    }
                    if (!hasVpnPermission) {
                        _effects.send(MainEffect.RequestVpnPermission)
                        return@launch
                    }

                    val prepared = connectionInteractor.prepareStart(
                        selectedServer = selected,
                        preferUserSelection = _state.value.pendingUserSelectionOverride
                    )
                    if (prepared == null) {
                        _effects.send(MainEffect.ShowToast(UiText.Res(R.string.select_server_first)))
                        return@launch
                    }

                    updateSelectedServer(
                        country = selected!!.country,
                        countryCode = selected.countryCode,
                        config = prepared.config,
                        ip = prepared.ip ?: selected.ip,
                        fromUserSelection = false
                    )
                    _effects.send(MainEffect.StartVpn(prepared.config, prepared.country))
                }
            }
        }
    }

    private fun onServerSelectionResult(selection: SelectedServerResult?) {
        viewModelScope.launch {
            val resolvedSelection = selection?.takeIf {
                it.country != null && it.city != null && it.config != null
            }

            if (resolvedSelection != null) {
                logger.logServerSelectionApplied(resolvedSelection)
                val country = requireNotNull(resolvedSelection.country)
                val config = requireNotNull(resolvedSelection.config)
                val previousConfig = _state.value.selectedServer?.config
                val previousIp = _state.value.selectedServer?.ip
                updateSelectedServer(
                    country = country,
                    countryCode = resolvedSelection.countryCode,
                    config = config,
                    ip = resolvedSelection.ip,
                    fromUserSelection = true
                )
                val connectionState = connectionStateProvider.state.value
                val shouldStopForUserSelection = connectionInteractor.shouldStopForUserSelection(
                    state = connectionState,
                    previousConfig = previousConfig,
                    newConfig = config,
                    previousIp = previousIp,
                    newIp = resolvedSelection.ip
                )
                if (shouldStopForUserSelection) {
                    _effects.send(MainEffect.StopVpn)
                }
            } else if (selection != null) {
                logger.logIncompleteServerSelection(selection)
            }

            if (_state.value.reopenDrawerAfterReturn) {
                _effects.send(MainEffect.ReopenDrawer)
            } else {
                _effects.send(MainEffect.RequestPrimaryFocus)
            }
            _state.value = _state.value.copy(reopenDrawerAfterReturn = false)
        }
    }

    private fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        _state.value = _state.value.copy(isDetailsVisible = !isInMultiWindowMode)
    }

    private fun logInfo(message: String) {
        runCatching { AppLog.i(tag, message) }
    }
}
