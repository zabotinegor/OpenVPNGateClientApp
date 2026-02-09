package com.yahorzabotsin.openvpnclientgate.core.ui.settings

import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore

data class SettingsUiState(
    val language: LanguageOption = LanguageOption.SYSTEM,
    val theme: ThemeOption = ThemeOption.SYSTEM,
    val serverSource: ServerSource = ServerSource.DEFAULT,
    val customServerUrl: String = "",
    val autoSwitchWithinCountry: Boolean = true,
    val statusStallTimeoutSeconds: Int = UserSettingsStore.DEFAULT_STATUS_STALL_TIMEOUT_SECONDS,
    val statusStallTimeoutInput: String = UserSettingsStore.DEFAULT_STATUS_STALL_TIMEOUT_SECONDS.toString(),
    val cacheTtlMs: Long = UserSettingsStore.DEFAULT_CACHE_TTL_MS,
    val cacheTtlInput: String = (UserSettingsStore.DEFAULT_CACHE_TTL_MS / 60000L).toString()
)

sealed interface SettingsAction {
    data class SelectLanguage(val option: LanguageOption) : SettingsAction
    data class SelectTheme(val option: ThemeOption) : SettingsAction
    data class SelectServerSource(val source: ServerSource) : SettingsAction
    data class SetCustomServerUrl(val value: String) : SettingsAction
    data class SetAutoSwitchWithinCountry(val enabled: Boolean) : SettingsAction
    data class SetStatusStallTimeoutInput(val value: String) : SettingsAction
    data class SetCacheTtlInput(val value: String) : SettingsAction
}

sealed interface SettingsEffect {
    data object ApplyThemeAndLocale : SettingsEffect
    data object RefreshNotification : SettingsEffect
}
