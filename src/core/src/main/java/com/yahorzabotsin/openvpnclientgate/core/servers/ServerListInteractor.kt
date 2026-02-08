package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context

open class ServerListInteractor(
    private val appContext: Context,
    private val serverRepository: ServerRepository
) {
    open suspend fun getServers(forceRefresh: Boolean, cacheOnly: Boolean): List<Server> =
        serverRepository.getServers(appContext, forceRefresh, cacheOnly)

    open suspend fun resolveSelection(
        countryName: String,
        countryCode: String?,
        server: Server,
        countryServers: List<Server>
    ): ServerSelectionResult {
        val configs = serverRepository.loadConfigs(appContext, countryServers)
        val resolvedServers = countryServers.map { srv ->
            srv.copy(configData = configs[srv.lineIndex].orEmpty())
        }
        SelectedCountryStore.saveSelection(appContext, countryName, resolvedServers)
        val resolved = resolvedServers.firstOrNull { it.lineIndex == server.lineIndex } ?: resolvedServers.first()
        return ServerSelectionResult(
            countryName = countryName,
            countryCode = countryCode,
            city = resolved.city,
            config = resolved.configData,
            ip = resolved.ip
        )
    }
}
