package com.yahorzabotsin.openvpnclientgate.features.settings.presentation

import com.yahorzabotsin.openvpnclientgate.core.base.UiState
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption

sealed class SettingsState : UiState {

    data class Success(
        val language: LanguageOption = LanguageOption.SYSTEM,
        val theme: ThemeOption = ThemeOption.SYSTEM,
        val serverSource: ServerSource = ServerSource.DEFAULT,
        val customServerUrl: String = "",
        val cacheTtlMs: Long = 1L,
        val statusStallTimeoutSeconds: Int = 5,
        val isAutoSwitchEnabled: Boolean = false,
        val languageSummary: String = "",
        val themeSummary: String = ""
    ) : SettingsState()

    data class Error(val message: String) : SettingsState()
    data object IsLoading : SettingsState()
}