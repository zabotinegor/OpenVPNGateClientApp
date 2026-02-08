package com.yahorzabotsin.openvpnclientgate.core.ui.serverlist

import android.util.Log

interface ServerListLogger {
    fun logLoadSuccess(count: Int)
    fun logLoadError(error: Exception)
    fun logNoServers(countryName: String)
    fun logSelectionError(countryName: String, error: Exception)
}

class DefaultServerListLogger : ServerListLogger {
    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ServerListActivity"

    override fun logLoadSuccess(count: Int) {
        Log.i(tag, "Successfully loaded $count servers.")
    }

    override fun logLoadError(error: Exception) {
        Log.e(tag, "Error getting servers", error)
    }

    override fun logNoServers(countryName: String) {
        Log.w(tag, "No servers found for selected country: $countryName")
    }

    override fun logSelectionError(countryName: String, error: Exception) {
        Log.e(tag, "Error loading configs for $countryName", error)
    }
}
