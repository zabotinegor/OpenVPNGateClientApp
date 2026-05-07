package com.yahorzabotsin.openvpnclientgate.core.ui.main

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectionBootstrap
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2Repository
import com.yahorzabotsin.openvpnclientgate.core.servers.toLegacyServer
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore

interface MainSelectionInteractor {
    suspend fun loadInitialSelection(cacheOnly: Boolean): InitialSelection?
}

data class InitialSelection(
    val country: String,
    val city: String,
    val config: String,
    val countryCode: String?,
    val ip: String?
)

class DefaultMainSelectionInteractor(
    private val appContext: Context,
    private val serverRepository: ServerRepository,
    private val serversV2Repository: ServersV2Repository? = null
) : MainSelectionInteractor {
    override suspend fun loadInitialSelection(cacheOnly: Boolean): InitialSelection? {
        val source = UserSettingsStore.load(appContext).serverSource
        if (source == ServerSource.DEFAULT_V2) {
            return loadInitialSelectionV2(cacheOnly)
        }
        var result: InitialSelection? = null
        SelectionBootstrap.ensureSelection(
            context = appContext,
            getServers = {
                serverRepository.getServers(appContext, cacheOnly = cacheOnly)
            },
            loadConfigs = { servers ->
                serverRepository.loadConfigs(appContext, servers)
            }
        ) { country, city, config, countryCode, ip ->
            result = InitialSelection(
                country = country,
                city = city,
                config = config,
                countryCode = countryCode,
                ip = ip
            )
        }
        return result
    }

    private suspend fun loadInitialSelectionV2(cacheOnly: Boolean): InitialSelection? {
        val stored = SelectedCountryStore.currentServer(appContext)
        if (stored != null) {
            val country = SelectedCountryStore.getSelectedCountry(appContext) ?: return null
            return InitialSelection(
                country = country,
                city = stored.city,
                config = stored.config,
                countryCode = stored.countryCode,
                ip = stored.ip
            )
        }
        val repo = serversV2Repository ?: return null
        val countries = repo.getCountries(appContext, forceRefresh = false, cacheOnly = cacheOnly)
        if (countries.isEmpty()) return null
        val firstCountry = countries.first()
        val v2Servers = repo.getServersForCountry(
            context = appContext,
            countryCode = firstCountry.code,
            serverCount = firstCountry.serverCount,
            forceRefresh = false,
            cacheOnly = cacheOnly
        )
        if (v2Servers.isEmpty()) return null
        val legacyServers = v2Servers.map { it.toLegacyServer() }
        SelectedCountryStore.saveSelection(appContext, firstCountry.name, legacyServers)
        val first = legacyServers.first()
        return InitialSelection(
            country = first.country.name,
            city = first.city,
            config = first.configData,
            countryCode = first.country.code,
            ip = first.ip
        )
    }
}
