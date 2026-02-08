package com.yahorzabotsin.openvpnclientgate.core.servers

data class ServerSelectionResult(
    val countryName: String,
    val countryCode: String?,
    val city: String?,
    val config: String,
    val ip: String?
)
