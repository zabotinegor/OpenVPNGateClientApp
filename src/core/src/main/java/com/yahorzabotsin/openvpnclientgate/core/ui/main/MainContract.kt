package com.yahorzabotsin.openvpnclientgate.core.ui.main

import androidx.annotation.StringRes

data class MainUiState(
    val isDetailsVisible: Boolean = true,
    val selectedServer: MainSelectedServer? = null,
    val reopenDrawerAfterReturn: Boolean = false,
    val selectionVersion: Long = 0L
)

data class MainSelectedServer(
    val country: String,
    val countryCode: String?,
    val config: String,
    val ip: String?,
    val fromUserSelection: Boolean,
    val version: Long
)

sealed interface MainAction {
    data object LoadInitialSelection : MainAction
    data class NavigationItemSelected(val itemId: Int) : MainAction
    data object OpenServerListFromConnectionControls : MainAction
    data class OnServerSelectionResult(val selection: SelectedServerResult?) : MainAction
    data class OnVpnPermissionResult(val granted: Boolean) : MainAction
    data class OnNotificationPermissionResult(val granted: Boolean) : MainAction
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
}

sealed interface MainEffect {
    data class OpenDestination(
        val destination: MainDestination,
        val reopenDrawerAfterReturn: Boolean = false
    ) : MainEffect

    data object CloseDrawer : MainEffect
    data object ReopenDrawer : MainEffect
    data object RequestPrimaryFocus : MainEffect
    data object TriggerConnectionClick : MainEffect

    data class ShowToast(@StringRes val resId: Int) : MainEffect
}
