package com.yahorzabotsin.openvpnclientgate.core.servers

enum class SignalStrength {
    STRONG,
    MEDIUM,
    WEAK
}

fun Int.toSignalStrength(): SignalStrength = when (this) {
    in 0..99 -> SignalStrength.STRONG
    in 100..249 -> SignalStrength.MEDIUM
    else -> SignalStrength.WEAK
}
