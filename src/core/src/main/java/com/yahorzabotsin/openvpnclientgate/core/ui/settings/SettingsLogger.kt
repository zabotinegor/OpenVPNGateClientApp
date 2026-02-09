package com.yahorzabotsin.openvpnclientgate.core.ui.settings

import android.util.Log
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption

interface SettingsLogger {
    fun logScreenOpened(state: SettingsUiState)
    fun logLanguageChanged(old: LanguageOption, selected: LanguageOption)
    fun logThemeChanged(old: ThemeOption, selected: ThemeOption)
    fun logServerSourceChanged(old: ServerSource, selected: ServerSource)
    fun logCustomServerUrlChanged(value: String)
    fun logAutoSwitchChanged(enabled: Boolean)
    fun logStatusStallTimeoutChanged(seconds: Int)
    fun logCacheTtlChanged(ttlMs: Long)
}

class DefaultSettingsLogger : SettingsLogger {
    private val tag = LogTags.APP + ':' + "SettingsActivity"

    override fun logScreenOpened(state: SettingsUiState) {
        Log.i(
            tag,
            "Settings screen opened: language=${state.language.name}, theme=${state.theme.name}, " +
                "serverSource=${state.serverSource.name}, customUrl=${state.customServerUrl}, " +
                "autoSwitch=${state.autoSwitchWithinCountry}, statusTimeout=${state.statusStallTimeoutSeconds}, " +
                "cacheTtlMs=${state.cacheTtlMs}"
        )
    }

    override fun logLanguageChanged(old: LanguageOption, selected: LanguageOption) {
        Log.i(tag, "Settings language changed: ${old.name} -> ${selected.name}")
    }

    override fun logThemeChanged(old: ThemeOption, selected: ThemeOption) {
        Log.i(tag, "Settings theme changed: ${old.name} -> ${selected.name}")
    }

    override fun logServerSourceChanged(old: ServerSource, selected: ServerSource) {
        Log.i(tag, "Settings server source changed: ${old.name} -> ${selected.name}")
    }

    override fun logCustomServerUrlChanged(value: String) {
        Log.i(tag, "Settings custom server url changed: ${value}")
    }

    override fun logAutoSwitchChanged(enabled: Boolean) {
        Log.i(tag, "Settings auto switch changed: enabled=$enabled")
    }

    override fun logStatusStallTimeoutChanged(seconds: Int) {
        Log.i(tag, "Settings status stall timeout changed: seconds=$seconds")
    }

    override fun logCacheTtlChanged(ttlMs: Long) {
        Log.i(tag, "Settings cache ttl changed: ttlMs=$ttlMs")
    }
}
