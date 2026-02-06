package com.yahorzabotsin.openvpnclientgate.features.settings.presentation

import com.yahorzabotsin.openvpnclientgate.core.base.UiIntent
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption

sealed class SettingsIntent: UiIntent {
    data object OnLoadSettings : SettingsIntent()
    data class OnChangeLanguage(val language: LanguageOption) : SettingsIntent()
    data class OnChangeTheme(val theme: ThemeOption) : SettingsIntent()
    data class OnChangeServerSource(val source: ServerSource) : SettingsIntent()
    data class OnUpdateCustomServerUrl(val url: String) : SettingsIntent()
    data class OnUpdateStatusTimer(val seconds: Int) : SettingsIntent()
    data class OnUpdateCachedServersTimer(val ttlMs: Long) : SettingsIntent()
    data class OnToggleAutoSwitch(val isEnabled: Boolean) : SettingsIntent()
}