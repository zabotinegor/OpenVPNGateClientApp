package com.yahorzabotsin.openvpnclientgate.core.ui.dns

import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOption

data class DnsUiState(
    val items: List<DnsOptionItem> = emptyList(),
    val selectedOption: DnsOption = DnsOption.SERVER
)

sealed interface DnsAction {
    data class SelectOption(val option: DnsOption) : DnsAction
}

sealed interface DnsEffect {
    data class FocusSelected(val option: DnsOption) : DnsEffect
}
