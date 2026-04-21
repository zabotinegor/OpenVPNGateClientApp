package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog

class SelectedCountryServerSync(
    private val appContext: Context,
    private val serverRepository: ServerRepository
) {

    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "SelectedCountryServerSync"

    suspend fun syncAfterRefresh(freshServers: List<Server>) {
        val selectedCountry = SelectedCountryStore.getSelectedCountry(appContext)
        if (selectedCountry.isNullOrBlank()) return

        val countryServers = freshServers.filter { it.country.name == selectedCountry }
        if (countryServers.isEmpty()) {
            AppLog.w(tag, "Skipping selected country sync: country not found in fresh list")
            return
        }

        val configs = serverRepository.loadConfigs(appContext, countryServers)
        if (configs.isEmpty()) {
            AppLog.w(tag, "Skipping selected country sync: configs could not be loaded")
            return
        }

        val resolved = countryServers.mapNotNull { server ->
            configs[server.lineIndex]
                ?.takeIf { it.isNotBlank() }
                ?.let { config ->
                    server.copy(configData = config)
                }
        }
        if (resolved.isEmpty()) {
            AppLog.w(tag, "Skipping selected country sync: no valid server configs resolved")
            return
        }

        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx = appContext,
            country = selectedCountry,
            servers = resolved
        )

        AppLog.i(tag, "Selected country sync completed. country=$selectedCountry, servers=${resolved.size}")
    }
}
