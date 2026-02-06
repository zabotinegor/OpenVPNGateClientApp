package com.yahorzabotsin.openvpnclientgate.features.settings.presentation.utils

import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.settings.LanguageOption
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.ThemeOption

internal fun LanguageOption.toId(): Int = when (this) {
    LanguageOption.SYSTEM -> R.id.language_system
    LanguageOption.ENGLISH -> R.id.language_en
    LanguageOption.RUSSIAN -> R.id.language_ru
    LanguageOption.POLISH -> R.id.language_pl
}

internal fun Int.toLanguageOption(): LanguageOption = when (this) {
    R.id.language_en -> LanguageOption.ENGLISH
    R.id.language_ru -> LanguageOption.RUSSIAN
    R.id.language_pl -> LanguageOption.POLISH
    else -> LanguageOption.SYSTEM
}

internal fun ThemeOption.toId(): Int = when (this) {
    ThemeOption.LIGHT -> R.id.theme_light
    ThemeOption.DARK -> R.id.theme_dark
    else -> R.id.theme_system
}

internal fun Int.toThemeOption(): ThemeOption = when (this) {
    R.id.theme_light -> ThemeOption.LIGHT
    R.id.theme_dark -> ThemeOption.DARK
    else -> ThemeOption.SYSTEM
}

internal fun ServerSource.toId(): Int = when (this) {
    ServerSource.VPNGATE -> R.id.server_vpngate
    ServerSource.CUSTOM -> R.id.server_custom
    else -> R.id.server_default
}

internal fun Int.toServerSource(): ServerSource = when (this) {
    R.id.server_vpngate -> ServerSource.VPNGATE
    R.id.server_custom -> ServerSource.CUSTOM
    else -> ServerSource.DEFAULT
}

internal fun formatFromMinutes(ttlMs: Long): Long {
    return (ttlMs * 60000).coerceAtLeast(1)
}