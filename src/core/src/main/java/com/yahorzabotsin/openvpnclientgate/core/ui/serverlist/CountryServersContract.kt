package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText

data class CountryServersUiState(
    val isLoading: Boolean = false,
    val countryName: String? = null,
    val countryCode: String? = null,
    val servers: List<Server> = emptyList()
)

sealed interface CountryServersAction {
    data class Initialize(val countryName: String?, val countryCode: String?) : CountryServersAction
    data class ServerSelected(val server: Server) : CountryServersAction
}

sealed interface CountryServersEffect {
    data class ShowToast(val text: UiText) : CountryServersEffect
    data class ShowSnackbar(val text: UiText) : CountryServersEffect
    data class FinishWithSelection(val result: ServerSelectionResult) : CountryServersEffect
    data object FinishCanceled : CountryServersEffect
    data object FocusFirstItem : CountryServersEffect
}
