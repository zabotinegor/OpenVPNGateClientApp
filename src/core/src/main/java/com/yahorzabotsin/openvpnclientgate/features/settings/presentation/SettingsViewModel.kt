package com.yahorzabotsin.openvpnclientgate.features.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.yahorzabotsin.openvpnclientgate.core.base.BaseViewModel
import com.yahorzabotsin.openvpnclientgate.features.settings.domain.SettingsRepository

class SettingsViewModel(private val settingsRepository: SettingsRepository) :
    BaseViewModel<SettingsState, SettingsIntent>(
        SettingsState.IsLoading
    ) {

    companion object {
        fun provideFactory(repository: SettingsRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>) =
                    SettingsViewModel(repository) as T

            }
    }

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val settings = settingsRepository.load()
        updateState {
            SettingsState.Success(
                language = settings.language,
                theme = settings.theme,
                serverSource = settings.serverSource,
                customServerUrl = settings.customServerUrl,
                cacheTtlMs = settings.cacheTtlMs,
                statusStallTimeoutSeconds = settings.statusStallTimeoutSeconds,
                isAutoSwitchEnabled = settings.autoSwitchWithinCountry
            )
        }
    }

    override fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            is SettingsIntent.OnLoadSettings -> {
                updateState {
                    SettingsState.IsLoading
                }
            }

            is SettingsIntent.OnChangeLanguage -> {
                settingsRepository.saveLanguage(intent.language)
                settingsRepository.applyThemeAndLocale()
                updateState {
                    (state.value as SettingsState.Success).copy(language = intent.language)
                }
            }

            is SettingsIntent.OnChangeTheme -> {
                settingsRepository.saveTheme(intent.theme)
                settingsRepository.applyThemeAndLocale()
                updateState {
                    (state.value as SettingsState.Success).copy(theme = intent.theme)
                }
            }

            is SettingsIntent.OnChangeServerSource -> {
                settingsRepository.saveServerSource(intent.source)
                updateState {
                    (state.value as SettingsState.Success).copy(serverSource = intent.source)
                }
            }

            is SettingsIntent.OnUpdateCustomServerUrl -> {
                settingsRepository.saveCustomServerUrl(intent.url)
                updateState {
                    (state.value as SettingsState.Success).copy(customServerUrl = intent.url)
                }
            }

            is SettingsIntent.OnUpdateStatusTimer -> {
                settingsRepository.saveStatusStallTimeoutSeconds(intent.seconds)
                updateState {
                    (state.value as SettingsState.Success).copy(statusStallTimeoutSeconds = intent.seconds)
                }
            }

            is SettingsIntent.OnUpdateCachedServersTimer -> {
                settingsRepository.saveCacheTtlMs(intent.ttlMs)
                updateState {
                    (state.value as SettingsState.Success).copy(cacheTtlMs = intent.ttlMs)
                }
            }

            is SettingsIntent.OnToggleAutoSwitch -> {
                settingsRepository.saveAutoSwitchWithinCountry(intent.isEnabled)
                updateState {
                    (state.value as SettingsState.Success).copy(isAutoSwitchEnabled = intent.isEnabled)
                }
            }
        }
    }
}