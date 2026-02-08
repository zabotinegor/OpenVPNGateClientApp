package com.yahorzabotsin.openvpnclientgate.core.ui.dns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.settings.DnsOption
import com.yahorzabotsin.openvpnclientgate.core.settings.DnsOptions
import com.yahorzabotsin.openvpnclientgate.core.settings.DnsSettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.ui.DnsOptionItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DnsViewModel(
    private val settingsRepository: DnsSettingsRepository,
    private val logger: DnsLogger
) : ViewModel() {

    private val providerByOption = DnsOptions.providers.associateBy { it.option }

    private val _state = MutableStateFlow(DnsUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<DnsEffect>()
    val effects = _effects.asSharedFlow()

    init {
        load()
    }

    fun onAction(action: DnsAction) {
        when (action) {
            is DnsAction.SelectOption -> onSelectOption(action.option)
        }
    }

    private fun load() {
        val items = DnsOptions.providers.map { provider ->
            DnsOptionItem(provider.option, provider.label, null)
        }
        val current = settingsRepository.loadDnsOption()
        logger.logScreenOpened(items.size, current)
        _state.value = DnsUiState(items = items, selectedOption = current)
        viewModelScope.launch {
            _effects.emit(DnsEffect.FocusSelected(current))
        }
    }

    private fun onSelectOption(option: DnsOption) {
        val old = _state.value.selectedOption
        if (option == old) return
        _state.value = _state.value.copy(selectedOption = option)
        val label = providerByOption[option]?.label ?: option.name
        logger.logSelectionChanged(old, option, label)
        settingsRepository.saveDnsOption(option)
    }
}
