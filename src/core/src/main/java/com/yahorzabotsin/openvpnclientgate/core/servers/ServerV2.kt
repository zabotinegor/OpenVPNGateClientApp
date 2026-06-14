package com.yahorzabotsin.openvpnclientgate.core.servers

import com.google.gson.annotations.SerializedName

data class ServerV2(
    @SerializedName("ip") val ip: String,
    @SerializedName("countryCode") val countryCode: String,
    @SerializedName("countryName") val countryName: String,
    @SerializedName("configData") val configData: String,
    @SerializedName("city") val city: String? = null,
    @SerializedName("utc") val utc: String? = null,
    @SerializedName("id") val id: Int = 0,
    @SerializedName("ping") val ping: Int = 0
)

/** Maps a [ServerV2] to the legacy [Server] shape so it can be stored in [SelectedCountryStore]. */
fun ServerV2.toLegacyServer(): Server = Server(
    lineIndex = 0,
    name = ip,
    city = city?.takeIf { it.isNotBlank() } ?: "",
    country = Country(name = countryName, code = countryCode),
    ping = ping,
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
    configData = configData,
    utc = utc,
    id = id
)
