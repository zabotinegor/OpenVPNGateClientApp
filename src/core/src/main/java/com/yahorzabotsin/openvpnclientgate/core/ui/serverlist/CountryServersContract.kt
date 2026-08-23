package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionResult
import com.yahorzabotsin.openvpnclientgate.core.ui.common.text.UiText

data class CountryServersUiState(
    val isLoading: Boolean = false,
    val countryName: String? = null,
    val countryCode: String? = null,
    val servers: List<Server> = emptyList(),
    val favoriteServerIds: Set<Int> = emptySet(),
    val items: List<ServerListItem> = emptyList(),
    // --- US-23 lazy-loading paging state ---
    /** Number of servers requested per page; computed at runtime by the Activity from the
     * device's real screen/row measurements (AC5) and carried in via [CountryServersAction.Initialize]. */
    val pageSize: Int = 0,
    val hasMorePages: Boolean = false,
    val isLoadingMore: Boolean = false,
    val pageLoadError: Boolean = false,
    /** `skip` offset for the next page request; only meaningful while [hasMorePages] is true. */
    val nextSkip: Int = 0
)

sealed interface CountryServersAction {
    data class Initialize(val countryName: String?, val countryCode: String?, val pageSize: Int) : CountryServersAction
    data class ServerSelected(val server: Server) : CountryServersAction
    data class ToggleFavorite(val server: Server) : CountryServersAction
    /** Dispatched when the scroll listener detects the user is nearing the end of the
     * currently loaded servers (AC2). No-op when already loading or no more pages exist. */
    data object LoadNextPage : CountryServersAction
    /** Dispatched from the loading-footer's retry affordance after a mid-scroll page fetch
     * failure (AC4). Resumes from the same page (does not advance skip on failure). */
    data object RetryLoadNextPage : CountryServersAction
}

sealed interface CountryServersEffect {
    data class ShowToast(val text: UiText) : CountryServersEffect
    data class ShowSnackbar(val text: UiText) : CountryServersEffect
    data class FinishWithSelection(val result: ServerSelectionResult) : CountryServersEffect
    data object FinishCanceled : CountryServersEffect
    data class FocusFirstItem(val adapterPosition: Int) : CountryServersEffect
}
