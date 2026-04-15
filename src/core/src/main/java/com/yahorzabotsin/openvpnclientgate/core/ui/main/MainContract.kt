package com.yahorzabotsin.openvpnclientgate.core.ui.main

import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText

data class MainUiState(
    val isDetailsVisible: Boolean = true,
    val selectedServer: MainSelectedServer? = null,
    val whatsNew: MainWhatsNew? = null,
    val availableUpdate: MainAvailableUpdate? = null,
    val reopenDrawerAfterReturn: Boolean = false,
    val selectionVersion: Long = 0L,
    val pendingUserSelectionOverride: Boolean = false
)

data class MainSelectedServer(
    val country: String,
    val countryCode: String?,
    val config: String,
    val ip: String?,
    val fromUserSelection: Boolean,
    val version: Long
)

data class MainWhatsNew(
    val versionNumber: String,
    val name: String,
    val changelog: String,
    val changelogHtml: String
)

data class MainAvailableUpdate(
    val currentBuild: Long,
    val latestBuild: Long?,
    val versionNumber: String,
    val name: String,
    val changelog: String,
    val assetId: Int,
    val assetName: String,
    val assetPlatform: String,
    val assetBuildNumber: Long?,
    val assetType: String,
    val assetSizeBytes: Long,
    val assetContentHash: String,
    val downloadProxyUrl: String,
    val message: String
)

sealed interface MainAction {
    data object LoadInitialSelection : MainAction
    data object RefreshUpdateAvailability : MainAction
    data class NavigationItemSelected(val itemId: Int) : MainAction
    data object OpenServerListFromConnectionControls : MainAction
    data class ConnectionButtonClicked(
        val hasNotificationPermission: Boolean,
        val hasVpnPermission: Boolean
    ) : MainAction
    data class OnServerSelectionResult(val selection: SelectedServerResult?) : MainAction
    data class OnMultiWindowModeChanged(val isInMultiWindowMode: Boolean) : MainAction
}

data class SelectedServerResult(
    val country: String?,
    val countryCode: String?,
    val city: String?,
    val config: String?,
    val ip: String?
)

sealed interface MainDestination {
    data object ServerList : MainDestination
    data object Dns : MainDestination
    data object Filter : MainDestination
    data object Settings : MainDestination
    data object About : MainDestination
    data class WhatsNew(val data: MainWhatsNew) : MainDestination
}

sealed interface MainEffect {
    data class OpenDestination(
        val destination: MainDestination,
        val reopenDrawerAfterReturn: Boolean = false
    ) : MainEffect

    data object CloseDrawer : MainEffect
    data object ReopenDrawer : MainEffect
    data object RequestPrimaryFocus : MainEffect
    data object RequestVpnPermission : MainEffect
    data object RequestNotificationPermission : MainEffect
    data class StartVpn(val config: String, val country: String?) : MainEffect
    data object StopVpn : MainEffect

    data class PromptUpdate(
        val update: MainAvailableUpdate,
        val oneTimeOnly: Boolean = true
    ) : MainEffect
    data class ShowToast(val text: UiText) : MainEffect
}
