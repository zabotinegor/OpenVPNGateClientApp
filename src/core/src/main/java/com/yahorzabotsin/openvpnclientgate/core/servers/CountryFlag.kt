package com.yahorzabotsin.openvpnclientgate.core.servers

import java.util.Locale

fun countryFlagEmoji(code: String?): String? {
    if (code == null) return null
    val upper = code.trim().uppercase(Locale.US)
    if (upper.length != 2) return null
    if (!upper.all { it in 'A'..'Z' }) return null
    val base = 0x1F1E6
    val first = base + (upper[0] - 'A')
    val second = base + (upper[1] - 'A')
    return String(intArrayOf(first, second), 0, 2)
}


