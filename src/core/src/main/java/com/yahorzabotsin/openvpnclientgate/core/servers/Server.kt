package com.yahorzabotsin.openvpnclientgate.core.servers

data class Server(
    val lineIndex: Int,
    val name: String,
    val city: String,
    val country: Country,
    val ping: Int,
    val signalStrength: SignalStrength,
    val ip: String,
    val score: Int,
    val speed: Long,
    val numVpnSessions: Int,
    val uptime: Long,
    val totalUsers: Long,
    val totalTraffic: Long,
    val logType: String,
    val operator: String,
    val message: String,
    val configData: String
)

