package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
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
        val chosenIndex = listOf(
            resolvedServers.indexOfFirst { it.lineIndex == selectedServer.lineIndex && it.ip == selectedServer.ip },
            resolvedServers.indexOfFirst { it.ip == selectedServer.ip },
            resolvedServers.indexOfFirst { it.lineIndex == selectedServer.lineIndex },
            resolvedServers.indexOfFirst { it.configData == selectedServer.configData && it.city == selectedServer.city }
        ).firstOrNull { it >= 0 } ?: 0

        runCatching { SelectedCountryStore.setCurrentIndex(appContext, chosenIndex) }
        val chosenResolved = resolvedServers[chosenIndex]

        return ServerSelectionResult(
            countryName = countryName,
            countryCode = countryCode,
            city = chosenResolved.city,
            config = chosenResolved.configData,
            ip = chosenResolved.ip
        )
    }
}
