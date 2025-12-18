package com.yahorzabotsin.openvpnclient.core.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.yahorzabotsin.openvpnclient.core.ApiConstants
import java.util.Locale

data class UserSettings(
    val language: LanguageOption = LanguageOption.SYSTEM,
    val theme: ThemeOption = ThemeOption.SYSTEM,
    val serverSource: ServerSource = ServerSource.DEFAULT,
    val customServerUrl: String = "",
    val cacheTtlMs: Long = UserSettingsStore.DEFAULT_CACHE_TTL_MS,
    val autoSwitchWithinCountry: Boolean = true
)

enum class LanguageOption { SYSTEM, ENGLISH, RUSSIAN, POLISH }
enum class ThemeOption { SYSTEM, LIGHT, DARK }
enum class ServerSource { DEFAULT, VPNGATE, CUSTOM }

object UserSettingsStore {
    private const val PREFS_NAME = "user_settings"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_THEME = "theme"
    private const val KEY_SERVER_SOURCE = "server_source"
    private const val KEY_CUSTOM_SERVER_URL = "custom_server_url"
    private const val KEY_CACHE_TTL_MS = "cache_ttl_ms"
    private const val KEY_AUTO_SWITCH_WITHIN_COUNTRY = "auto_switch_within_country"
    private const val MIN_CACHE_TTL_MS = 60_000L
    const val DEFAULT_CACHE_TTL_MS = 20 * 60 * 1000L

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): UserSettings {
        val p = prefs(ctx)
        val language = LanguageOption.values()
            .firstOrNull { it.name == p.getString(KEY_LANGUAGE, null) } ?: LanguageOption.SYSTEM
        val theme = ThemeOption.values()
            .firstOrNull { it.name == p.getString(KEY_THEME, null) } ?: ThemeOption.SYSTEM
        val serverSource = ServerSource.values()
            .firstOrNull { it.name == p.getString(KEY_SERVER_SOURCE, null) } ?: ServerSource.DEFAULT
        val customUrl = p.getString(KEY_CUSTOM_SERVER_URL, "") ?: ""
        val cacheTtl = p.getLong(KEY_CACHE_TTL_MS, DEFAULT_CACHE_TTL_MS).coerceAtLeast(MIN_CACHE_TTL_MS)
        val autoSwitch = p.getBoolean(KEY_AUTO_SWITCH_WITHIN_COUNTRY, true)
        return UserSettings(language, theme, serverSource, customUrl, cacheTtl, autoSwitch)
    }

    fun save(ctx: Context, settings: UserSettings) {
        prefs(ctx).edit()
            .putString(KEY_LANGUAGE, settings.language.name)
            .putString(KEY_THEME, settings.theme.name)
            .putString(KEY_SERVER_SOURCE, settings.serverSource.name)
            .putString(KEY_CUSTOM_SERVER_URL, settings.customServerUrl)
            .putLong(KEY_CACHE_TTL_MS, settings.cacheTtlMs.coerceAtLeast(MIN_CACHE_TTL_MS))
            .putBoolean(KEY_AUTO_SWITCH_WITHIN_COUNTRY, settings.autoSwitchWithinCountry)
            .apply()
    }

    fun saveLanguage(ctx: Context, language: LanguageOption) =
        prefs(ctx).edit().putString(KEY_LANGUAGE, language.name).apply()

    fun saveTheme(ctx: Context, theme: ThemeOption) =
        prefs(ctx).edit().putString(KEY_THEME, theme.name).apply()

    fun saveServerSource(ctx: Context, source: ServerSource) =
        prefs(ctx).edit().putString(KEY_SERVER_SOURCE, source.name).apply()

    fun saveCustomServerUrl(ctx: Context, url: String) =
        prefs(ctx).edit().putString(KEY_CUSTOM_SERVER_URL, url).apply()

    fun saveCacheTtlMs(ctx: Context, ttlMs: Long) =
        prefs(ctx).edit().putLong(KEY_CACHE_TTL_MS, ttlMs.coerceAtLeast(MIN_CACHE_TTL_MS)).apply()

    fun saveAutoSwitchWithinCountry(ctx: Context, enabled: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_AUTO_SWITCH_WITHIN_COUNTRY, enabled).apply()

    fun applyThemeAndLocale(ctx: Context) {
        val settings = load(ctx)
        AppCompatDelegate.setDefaultNightMode(
            when (settings.theme) {
                ThemeOption.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeOption.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeOption.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )

        val locales = when (settings.language) {
            LanguageOption.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
            LanguageOption.ENGLISH -> LocaleListCompat.create(Locale("en"))
            LanguageOption.RUSSIAN -> LocaleListCompat.create(Locale("ru"))
            LanguageOption.POLISH -> LocaleListCompat.create(Locale("pl"))
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun resolveServerUrls(settings: UserSettings): List<String> = when (settings.serverSource) {
        ServerSource.DEFAULT -> listOf(ApiConstants.PRIMARY_SERVERS_URL, ApiConstants.FALLBACK_SERVERS_URL)
        ServerSource.VPNGATE -> listOf(ApiConstants.FALLBACK_SERVERS_URL)
        ServerSource.CUSTOM -> settings.customServerUrl.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }
}
