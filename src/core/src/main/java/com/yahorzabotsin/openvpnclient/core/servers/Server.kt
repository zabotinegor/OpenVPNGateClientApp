package com.yahorzabotsin.openvpnclient.core.servers

data class Server(
    val name: String,
    val city: String,
    val country: Country,
    val ping: Int,
    val signalStrength: SignalStrength
)