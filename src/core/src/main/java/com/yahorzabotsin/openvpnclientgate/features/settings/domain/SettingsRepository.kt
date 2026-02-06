package com.yahorzabotsin.openvpnclientgate.features.settings.domain

import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettings
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption

interface SettingsRepository {
    fun load(): UserSettings
    fun saveLanguage(language: LanguageOption)
    fun saveTheme(theme: ThemeOption)
    fun saveServerSource(source: ServerSource)
    fun saveCustomServerUrl(url: String)
    fun saveCacheTtlMs(ttlMs: Long)
    fun saveAutoSwitchWithinCountry(enabled: Boolean)
    fun saveStatusStallTimeoutSeconds(seconds: Int)
    fun applyThemeAndLocale()
}