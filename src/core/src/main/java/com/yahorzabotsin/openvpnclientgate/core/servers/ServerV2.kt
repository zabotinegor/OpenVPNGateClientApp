package com.yahorzabotsin.openvpnclientgate.core.servers

import com.google.gson.annotations.SerializedName

data class ServerV2(
    @SerializedName("ip") val ip: String = "",
    @SerializedName("countryCode") val countryCode: String = "",
    @SerializedName("countryName") val countryName: String = "",
    @SerializedName("configData") val configData: String = "",
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
    signalStrength = ping.toSignalStrength(),
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

/**
 * Reconstructs the [ServerV2] shape from a legacy [Server] originally produced by
 * [ServerV2.toLegacyServer] (US-23 F1): the silent background backfill
 * (`CountryServersInteractor.launchSilentBackfill`) seeds its merged accumulator from servers
 * the foreground screen already held before the user selected one -- those are only available
 * as legacy [Server], but the on-disk full-list cache it must persist via
 * [ServersV2Repository.persistFullServerList] is a [ServerV2] list. [fallbackCountryCode]/
 * [fallbackCountryName] back the country fields on the rare chance [Server.country] itself is
 * incomplete (never expected in practice for a V2-sourced server).
 */
fun Server.toServerV2(fallbackCountryCode: String, fallbackCountryName: String): ServerV2 = ServerV2(
    ip = ip,
    countryCode = country.code?.takeIf { it.isNotBlank() } ?: fallbackCountryCode,
    countryName = country.name.ifBlank { fallbackCountryName },
    configData = configData,
    city = city.takeIf { it.isNotBlank() },
    utc = utc,
    id = id,
    ping = ping
)
