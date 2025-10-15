package com.yahorzabotsin.openvpnclient.core.servers

class ServerRepository {
    fun getServers(): List<Server> {
        return listOf(
            Server("Auto server", "Auto", Country("Auto"), 49, SignalStrength.STRONG),
            Server("Netherlands", "Amsterdam", Country("Netherlands"), 53, SignalStrength.STRONG),
            Server("Germany", "Falkenstein", Country("Germany"), 60, SignalStrength.MEDIUM),
            Server("Austria", "Vienna", Country("Austria"), 50, SignalStrength.WEAK)
        )
    }
}