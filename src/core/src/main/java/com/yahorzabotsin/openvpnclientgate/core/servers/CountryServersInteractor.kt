package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import java.io.IOException

interface CountryServersInteractor {
    suspend fun getServersForCountry(countryName: String, cacheOnly: Boolean): List<Server>
    suspend fun resolveSelection(
        countryName: String,
        countryCode: String?,
        servers: List<Server>,
        selectedServer: Server
    ): ServerSelectionResult
}

class DefaultCountryServersInteractor(
    private val appContext: Context,
    private val serverRepository: ServerRepository
) : CountryServersInteractor {
    private companion object {
        private val TAG = LogTags.APP + ":CountryServersInteractor"
    }

    override suspend fun getServersForCountry(countryName: String, cacheOnly: Boolean): List<Server> {
        val allServers = serverRepository.getServers(
            context = appContext,
            forceRefresh = false,
            cacheOnly = cacheOnly
        )
        return allServers.filter { it.country.name == countryName }
    }

    override suspend fun resolveSelection(
        countryName: String,
        countryCode: String?,
        servers: List<Server>,
        selectedServer: Server
    ): ServerSelectionResult {
        if (servers.isEmpty()) throw IOException("No servers available for $countryName")

        val configs = serverRepository.loadConfigs(appContext, servers)
        val resolvedServers = servers.map { server ->
            server.copy(configData = configs[server.lineIndex].orEmpty())
        }

        SelectedCountryStore.saveSelection(appContext, countryName, resolvedServers)
        val chosenIndex = resolveSelectedIndex(
            selectedServer = selectedServer,
            inputServers = servers,
            resolvedServers = resolvedServers
        )

        runCatching { SelectedCountryStore.setCurrentIndex(appContext, chosenIndex) }
        val chosenResolved = resolvedServers[chosenIndex]
        val currentPos = runCatching { SelectedCountryStore.getCurrentPosition(appContext) }.getOrNull()
        val currentPosText = currentPos?.let { "${it.first}/${it.second}" } ?: "unknown"
        AppLog.i(
            TAG,
            "Selection resolved: country=$countryName selectedIp=${selectedServer.ip ?: "<none>"} selectedLine=${selectedServer.lineIndex} chosenIndex=${chosenIndex + 1}/${resolvedServers.size} chosenIp=${chosenResolved.ip ?: "<none>"} currentPos=$currentPosText"
        )

        return ServerSelectionResult(
            countryName = countryName,
            countryCode = countryCode,
            city = chosenResolved.city,
            config = chosenResolved.configData,
            ip = chosenResolved.ip
        )
    }

    private fun resolveSelectedIndex(
        selectedServer: Server,
        inputServers: List<Server>,
        resolvedServers: List<Server>
    ): Int {
        val selectedIndexInInput = inputServers.indexOfFirst { it === selectedServer }
            .takeIf { it >= 0 }
            ?: inputServers.indexOf(selectedServer).takeIf { it >= 0 }

        return listOf(
            selectedIndexInInput ?: -1,
            resolvedServers.indexOfFirst { it.lineIndex == selectedServer.lineIndex && it.ip == selectedServer.ip },
            resolvedServers.indexOfFirst { it.ip == selectedServer.ip },
            resolvedServers.indexOfFirst { it.lineIndex == selectedServer.lineIndex },
            resolvedServers.indexOfFirst { it.configData == selectedServer.configData && it.city == selectedServer.city }
        ).firstOrNull { it >= 0 } ?: 0
    }
}

