package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText

data class ServerListUiState(
    val isLoading: Boolean = false,
    val isVpnConnected: Boolean = false,
    val isRefreshEnabled: Boolean = true,
    val showRefreshHint: Boolean = false,
    val countries: List<CountryWithServers> = emptyList(),
    val favoriteCountryCodes: Set<String> = emptySet(),
    val items: List<CountryListItem> = emptyList()
)

sealed interface ServerListAction {
    data class Load(val forceRefresh: Boolean) : ServerListAction
    data class CountrySelected(val country: Country) : ServerListAction
    data class ToggleFavorite(val country: Country) : ServerListAction
}

sealed interface ServerListEffect {
    data class ShowSnackbar(val text: UiText) : ServerListEffect
    data class ShowToast(val text: UiText) : ServerListEffect
    data class OpenCountryServers(val countryName: String, val countryCode: String?) : ServerListEffect
    data class FinishWithSelection(val result: ServerSelectionResult) : ServerListEffect
    data object SetResultCanceled : ServerListEffect
    data object FinishCanceled : ServerListEffect
    data class FocusFirstItem(val adapterPosition: Int) : ServerListEffect
}
