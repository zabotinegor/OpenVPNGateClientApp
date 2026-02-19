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
            statusStallTimeoutInput = settings.statusStallTimeoutSeconds.toString(),
            cacheTtlMs = settings.cacheTtlMs,
            cacheTtlInput = (settings.cacheTtlMs / 60000L).coerceAtLeast(1L).toString()
        )
        _state.value = state
        logger.logScreenOpened(state)
    }

    private fun onLanguageSelected(option: LanguageOption) {
        val current = _state.value
        if (current.language == option) return

        _state.value = current.copy(language = option)
        repository.saveLanguage(option)
        logger.logLanguageChanged(current.language, option)
        emitEffects(
            SettingsEffect.ApplyThemeAndLocale,
            SettingsEffect.StopControllerIfIdle
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
        val current = _state.value
        if (current.serverSource != ServerSource.CUSTOM) return
        if (current.customServerUrl == value) return

        _state.value = current.copy(customServerUrl = value)

        val previousTrimmed = current.customServerUrl.trim()
        val newTrimmed = value.trim()
        if (previousTrimmed == newTrimmed) return

        repository.saveCustomServerUrl(newTrimmed)
        logger.logCustomServerUrlChanged(newTrimmed)
    }

    private fun onAutoSwitchChanged(enabled: Boolean) {
        if (_state.value.autoSwitchWithinCountry == enabled) return
        _state.value = _state.value.copy(autoSwitchWithinCountry = enabled)
        repository.saveAutoSwitchWithinCountry(enabled)
        logger.logAutoSwitchChanged(enabled)
    }

    private fun onStatusTimeoutChanged(raw: String) {
        val current = _state.value
        val inputChanged = current.statusStallTimeoutInput != raw
        val parsedSeconds = raw.trim().toIntOrNull()
        val validSeconds = parsedSeconds?.takeIf { it > 0 }
        val logicalChanged = validSeconds != null && current.statusStallTimeoutSeconds != validSeconds

        if (!inputChanged && !logicalChanged) return

        _state.value = if (logicalChanged) {
            current.copy(
                statusStallTimeoutInput = raw,
                statusStallTimeoutSeconds = validSeconds!!
            )
        } else {
            current.copy(statusStallTimeoutInput = raw)
        }

        if (logicalChanged) {
            repository.saveStatusStallTimeoutSeconds(validSeconds!!)
            logger.logStatusStallTimeoutChanged(validSeconds)
        }
    }

    private fun onCacheTtlChanged(raw: String) {
        val current = _state.value
        val inputChanged = current.cacheTtlInput != raw
        val parsedMinutes = raw.trim().toLongOrNull()
        val validMinutes = parsedMinutes?.takeIf { it > 0 }
        val ttlMs = validMinutes?.times(60 * 1000L)
        val logicalChanged = ttlMs != null && current.cacheTtlMs != ttlMs

        if (!inputChanged && !logicalChanged) return

        _state.value = if (logicalChanged) {
            current.copy(
                cacheTtlInput = raw,
                cacheTtlMs = ttlMs!!
            )
        } else {
            current.copy(cacheTtlInput = raw)
        }

        if (logicalChanged) {
            repository.saveCacheTtlMs(ttlMs!!)
            logger.logCacheTtlChanged(ttlMs)
        }
    }

    private fun emitEffects(vararg effects: SettingsEffect) {
        viewModelScope.launch {
            effects.forEach { effect ->
                _effects.emit(effect)
            }
        }
    }
}
