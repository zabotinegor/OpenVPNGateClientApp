package com.yahorzabotsin.openvpnclientgate.core.settings

interface SettingsRepository {
    fun load(): UserSettings
    fun saveLanguage(language: LanguageOption)
    fun saveTheme(theme: ThemeOption)
    fun saveServerSource(source: ServerSource)
    fun saveCacheTtlMs(ttlMs: Long)
    fun saveAutoSwitchWithinCountry(enabled: Boolean)
    fun saveStatusStallTimeoutSeconds(seconds: Int)
}
