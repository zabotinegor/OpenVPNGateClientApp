package com.yahorzabotsin.openvpnclientgate.core.ui.settings

import android.net.Uri
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
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
        AppLog.i(
            tag,
            "Settings screen opened: language=${state.language.name}, theme=${state.theme.name}, " +
                "serverSource=${state.serverSource.name}, customUrl=${redactUrl(state.customServerUrl)}, " +
                "autoSwitch=${state.autoSwitchWithinCountry}, statusTimeout=${state.statusStallTimeoutSeconds}, " +
                "cacheTtlMs=${state.cacheTtlMs}"
        )
    }

    override fun logLanguageChanged(old: LanguageOption, selected: LanguageOption) {
        AppLog.i(tag, "Settings language changed: ${old.name} -> ${selected.name}")
    }

    override fun logThemeChanged(old: ThemeOption, selected: ThemeOption) {
        AppLog.i(tag, "Settings theme changed: ${old.name} -> ${selected.name}")
    }

    override fun logServerSourceChanged(old: ServerSource, selected: ServerSource) {
        AppLog.i(tag, "Settings server source changed: ${old.name} -> ${selected.name}")
    }

    override fun logCustomServerUrlChanged(value: String) {
        AppLog.i(tag, "Settings custom server url changed: ${redactUrl(value)}")
    }

    override fun logAutoSwitchChanged(enabled: Boolean) {
        AppLog.i(tag, "Settings auto switch changed: enabled=$enabled")
    }

    override fun logStatusStallTimeoutChanged(seconds: Int) {
        AppLog.i(tag, "Settings status stall timeout changed: seconds=$seconds")
    }

    override fun logCacheTtlChanged(ttlMs: Long) {
        AppLog.i(tag, "Settings cache ttl changed: ttlMs=$ttlMs")
    }

    private fun redactUrl(value: String): String {
        if (value.isBlank()) return "<empty>"
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return "<invalid>"
        val scheme = uri.scheme ?: "<no-scheme>"
        val host = uri.host ?: "<no-host>"
        return "$scheme://$host"
    }
}

