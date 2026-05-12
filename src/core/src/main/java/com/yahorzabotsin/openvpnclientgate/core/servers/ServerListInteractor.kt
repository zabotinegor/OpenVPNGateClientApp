package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore

interface ServerListInteractor {
    suspend fun getServers(forceRefresh: Boolean, cacheOnly: Boolean): List<Server>
    suspend fun getCountriesV2(forceRefresh: Boolean, cacheOnly: Boolean): List<CountryV2>
    fun isDefaultV2Source(): Boolean
    suspend fun resolveSelection(
        countryName: String,
        countryCode: String?,
        server: Server,
        countryServers: List<Server>
    ): ServerSelectionResult
}

class DefaultServerListInteractor(
    private val appContext: Context,
    private val serverRepository: ServerRepository,
    private val serversV2Repository: ServersV2Repository? = null
) : ServerListInteractor {

    override fun isDefaultV2Source(): Boolean =
        UserSettingsStore.load(appContext).serverSource == ServerSource.DEFAULT_V2

    override suspend fun getServers(forceRefresh: Boolean, cacheOnly: Boolean): List<Server> =
        serverRepository.getServers(appContext, forceRefresh, cacheOnly)

    override suspend fun getCountriesV2(forceRefresh: Boolean, cacheOnly: Boolean): List<CountryV2> {
        val repo = serversV2Repository
            ?: throw UnsupportedOperationException("ServersV2Repository not injected")
        return repo.getCountries(appContext, forceRefresh, cacheOnly)
    }

    override suspend fun resolveSelection(
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
