package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

internal object ServerDisplayFormatter {
    private val utcPattern = Regex("^(?:UTC|GMT)?\\s*([+-])(\\d{1,2})(?::?(\\d{2}))?$", RegexOption.IGNORE_CASE)

    fun formatCityWithUtc(city: String?, utc: String?): String? {
        val cityText = city?.trim().orEmpty()
        if (cityText.isEmpty()) return null

        val utcText = formatUtc(utc)
        return if (utcText != null) "$cityText ($utcText)" else cityText
    }

    fun formatUtc(utc: String?): String? {
        val normalized = utc?.trim().orEmpty()
        if (normalized.isEmpty()) return null

        val match = utcPattern.matchEntire(normalized) ?: return null
        val sign = match.groupValues[1]
        val hours = match.groupValues[2].toIntOrNull() ?: return null
        val minutes = match.groupValues[3].ifBlank { "00" }.toIntOrNull() ?: return null
        if (hours !in 0..23 || minutes !in 0..59) return null

        return String.format("%s%02d:%02d UTC", sign, hours, minutes)
    }
}