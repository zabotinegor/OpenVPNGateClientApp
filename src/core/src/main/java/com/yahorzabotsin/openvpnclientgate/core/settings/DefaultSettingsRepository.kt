package com.yahorzabotsin.openvpnclientgate.core.settings

import android.content.Context

class DefaultSettingsRepository(
    context: Context,
    private val store: UserSettingsStore = UserSettingsStore
) : SettingsRepository {

    private val appContext = context.applicationContext

    override fun load(): UserSettings = store.load(appContext)

    override fun saveLanguage(language: LanguageOption) {
        store.saveLanguage(appContext, language)
    }

    override fun saveTheme(theme: ThemeOption) {
        store.saveTheme(appContext, theme)
    }

    override fun saveServerSource(source: ServerSource) {
        store.saveServerSource(appContext, source)
    }

    override fun saveCustomServerUrl(url: String) {
        store.saveCustomServerUrl(appContext, url)
    }

    override fun saveCacheTtlMs(ttlMs: Long) {
        store.saveCacheTtlMs(appContext, ttlMs)
    }

    override fun saveAutoSwitchWithinCountry(enabled: Boolean) {
        store.saveAutoSwitchWithinCountry(appContext, enabled)
    }

    override fun saveStatusStallTimeoutSeconds(seconds: Int) {
        store.saveStatusStallTimeoutSeconds(appContext, seconds)
    }
}
