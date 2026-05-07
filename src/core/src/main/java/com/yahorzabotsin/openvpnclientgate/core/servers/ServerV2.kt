package com.yahorzabotsin.openvpnclientgate.core.servers

data class ServerV2(
    val ip: String,
    val countryCode: String,
    val countryName: String,
    val configData: String
)

/** Maps a [ServerV2] to the legacy [Server] shape so it can be stored in [SelectedCountryStore]. */
fun ServerV2.toLegacyServer(): Server = Server(
    lineIndex = 0,
    name = ip,
    city = "",
    country = Country(name = countryName, code = countryCode),
    ping = 0,
    signalStrength = SignalStrength.WEAK,
    ip = ip,
    score = 0,
    speed = 0L,
    numVpnSessions = 0,
    uptime = 0L,
    totalUsers = 0L,
    totalTraffic = 0L,
    logType = "",
    operator = "",
    message = "",
    configData = configData
)
