package com.yahorzabotsin.openvpnclientgate.features.settings.data

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettings
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.features.settings.domain.SettingsRepository
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption

class SettingsRepositoryImpl(val context: Context) : SettingsRepository {
    override fun load(): UserSettings {
        return UserSettingsStore.load(context)
    }

    override fun saveLanguage(language: LanguageOption) {
        return UserSettingsStore.saveLanguage(context, language)
    }

    override fun saveTheme(theme: ThemeOption) {
        return UserSettingsStore.saveTheme(context, theme)
    }

    override fun saveServerSource(source: ServerSource) {
        return UserSettingsStore.saveServerSource(context, source)
    }

    override fun saveCustomServerUrl(url: String) {
        return UserSettingsStore.saveCustomServerUrl(context, url)
    }

    override fun saveCacheTtlMs(ttlMs: Long) {
        return UserSettingsStore.saveCacheTtlMs(context, ttlMs)
    }

    override fun saveAutoSwitchWithinCountry(enabled: Boolean) {
        return UserSettingsStore.saveAutoSwitchWithinCountry(context, enabled)
    }

    override fun saveStatusStallTimeoutSeconds(seconds: Int) {
        return UserSettingsStore.saveStatusStallTimeoutSeconds(context, seconds)
    }

    override fun applyThemeAndLocale() {
        return UserSettingsStore.applyThemeAndLocale(context)
    }

}