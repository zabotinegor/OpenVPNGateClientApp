package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.util.Log

interface CountryServersLogger {
    fun logLoadSuccess(countryName: String, count: Int)
    fun logLoadError(countryName: String, error: Exception)
    fun logNoServers(countryName: String)
    fun logSelectionError(serverIp: String?, error: Exception)
}

class DefaultCountryServersLogger : CountryServersLogger {
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "CountryServersViewModel"

    override fun logLoadSuccess(countryName: String, count: Int) {
        Log.i(tag, "Loaded $count servers for $countryName")
    }

    override fun logLoadError(countryName: String, error: Exception) {
        Log.e(tag, "Error loading servers for $countryName", error)
    }

    override fun logNoServers(countryName: String) {
        Log.w(tag, "No servers for $countryName")
    }

    override fun logSelectionError(serverIp: String?, error: Exception) {
        Log.e(tag, "Error selecting server ip=${serverIp ?: "<none>"}", error)
    }
}
