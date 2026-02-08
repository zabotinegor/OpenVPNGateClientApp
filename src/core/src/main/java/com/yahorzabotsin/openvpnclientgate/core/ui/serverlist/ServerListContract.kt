package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.ui.CountryWithServers

data class ServerListUiState(
    val isLoading: Boolean = false,
    val isVpnConnected: Boolean = false,
    val isRefreshEnabled: Boolean = true,
    val showRefreshHint: Boolean = false,
    val countries: List<CountryWithServers> = emptyList()
)

sealed interface ServerListAction {
    data class Load(val forceRefresh: Boolean) : ServerListAction
    data class CountrySelected(val country: Country) : ServerListAction
}

sealed interface ServerListEffect {
    data class ShowSnackbar(val resId: Int) : ServerListEffect
    data class ShowToast(val resId: Int) : ServerListEffect
    data class OpenCountryServers(val countryName: String, val countryCode: String?) : ServerListEffect
    data class FinishWithSelection(val result: ServerSelectionResult) : ServerListEffect
    data object SetResultCanceled : ServerListEffect
    data object FinishCanceled : ServerListEffect
    data object FocusFirstItem : ServerListEffect
}
