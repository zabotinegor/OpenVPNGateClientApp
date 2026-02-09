package com.yahorzabotsin.openvpnclientgate.core.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.SettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val logger: SettingsLogger
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state = _state.asStateFlow()

    private val _effects = MutableSharedFlow<SettingsEffect>()
    val effects = _effects.asSharedFlow()

    init {
        load()
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SelectLanguage -> onLanguageSelected(action.option)
            is SettingsAction.SelectTheme -> onThemeSelected(action.option)
            is SettingsAction.SelectServerSource -> onServerSourceSelected(action.source)
            is SettingsAction.SetCustomServerUrl -> onCustomServerUrlChanged(action.value)
            is SettingsAction.SetAutoSwitchWithinCountry -> onAutoSwitchChanged(action.enabled)
            is SettingsAction.SetStatusStallTimeoutInput -> onStatusTimeoutChanged(action.value)
            is SettingsAction.SetCacheTtlInput -> onCacheTtlChanged(action.value)
        }
    }

    private fun load() {
        val settings = repository.load()
        val state = SettingsUiState(
            language = settings.language,
            theme = settings.theme,
            serverSource = settings.serverSource,
            customServerUrl = settings.customServerUrl,
            autoSwitchWithinCountry = settings.autoSwitchWithinCountry,
            statusStallTimeoutSeconds = settings.statusStallTimeoutSeconds,
            cacheTtlMs = settings.cacheTtlMs
        )
        _state.value = state
        logger.logScreenOpened(state)
    }

    private fun onLanguageSelected(option: LanguageOption) {
        if (_state.value.language == option) return
        val old = _state.value.language
        _state.value = _state.value.copy(language = option)
        repository.saveLanguage(option)
        logger.logLanguageChanged(old, option)
        emitEffects(
            SettingsEffect.ApplyThemeAndLocale,
            SettingsEffect.RefreshNotification
        )
    }

    private fun onThemeSelected(option: ThemeOption) {
        if (_state.value.theme == option) return
        val old = _state.value.theme
        _state.value = _state.value.copy(theme = option)
        repository.saveTheme(option)
        logger.logThemeChanged(old, option)
        emitEffects(SettingsEffect.ApplyThemeAndLocale)
    }

    private fun onServerSourceSelected(source: ServerSource) {
        if (_state.value.serverSource == source) return
        val old = _state.value.serverSource
        _state.value = _state.value.copy(serverSource = source)
        repository.saveServerSource(source)
        logger.logServerSourceChanged(old, source)
    }

    private fun onCustomServerUrlChanged(value: String) {
        if (_state.value.serverSource != ServerSource.CUSTOM) return
        val trimmed = value.trim()
        if (_state.value.customServerUrl == trimmed) return
        _state.value = _state.value.copy(customServerUrl = trimmed)
        repository.saveCustomServerUrl(trimmed)
        logger.logCustomServerUrlChanged(trimmed)
    }

    private fun onAutoSwitchChanged(enabled: Boolean) {
        if (_state.value.autoSwitchWithinCountry == enabled) return
        _state.value = _state.value.copy(autoSwitchWithinCountry = enabled)
        repository.saveAutoSwitchWithinCountry(enabled)
        logger.logAutoSwitchChanged(enabled)
    }

    private fun onStatusTimeoutChanged(raw: String) {
        val seconds = raw.trim().toIntOrNull() ?: return
        if (seconds <= 0) return
        if (_state.value.statusStallTimeoutSeconds == seconds) return
        _state.value = _state.value.copy(statusStallTimeoutSeconds = seconds)
        repository.saveStatusStallTimeoutSeconds(seconds)
        logger.logStatusStallTimeoutChanged(seconds)
    }

    private fun onCacheTtlChanged(raw: String) {
        val minutes = raw.trim().toLongOrNull() ?: return
        if (minutes <= 0) return
        val ttlMs = minutes * 60 * 1000L
        if (_state.value.cacheTtlMs == ttlMs) return
        _state.value = _state.value.copy(cacheTtlMs = ttlMs)
        repository.saveCacheTtlMs(ttlMs)
        logger.logCacheTtlChanged(ttlMs)
    }

    private fun emitEffects(vararg effects: SettingsEffect) {
        viewModelScope.launch {
            effects.forEach { effect ->
                _effects.emit(effect)
            }
        }
    }
}
