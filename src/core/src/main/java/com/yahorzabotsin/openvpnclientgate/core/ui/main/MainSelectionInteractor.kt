package com.yahorzabotsin.openvpnclientgate.core.ui.main

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectionBootstrap
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository

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
    private val serverRepository: ServerRepository
) : MainSelectionInteractor {
    override suspend fun loadInitialSelection(cacheOnly: Boolean): InitialSelection? {
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
}
