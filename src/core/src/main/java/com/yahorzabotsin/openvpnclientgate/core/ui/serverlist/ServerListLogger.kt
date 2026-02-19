package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog

interface ServerListLogger {
    fun logLoadSuccess(count: Int)
    fun logLoadError(error: Exception)
    fun logNoServers(countryName: String)
    fun logSelectionError(countryName: String, error: Exception)
}

class DefaultServerListLogger : ServerListLogger {
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ServerListViewModel"

    override fun logLoadSuccess(count: Int) {
        AppLog.i(tag, "Successfully loaded $count servers.")
    }

    override fun logLoadError(error: Exception) {
        AppLog.e(tag, "Error getting servers", error)
    }

    override fun logNoServers(countryName: String) {
        AppLog.w(tag, "No servers found for selected country: $countryName")
    }

    override fun logSelectionError(countryName: String, error: Exception) {
        AppLog.e(tag, "Error loading configs for $countryName", error)
    }
}

