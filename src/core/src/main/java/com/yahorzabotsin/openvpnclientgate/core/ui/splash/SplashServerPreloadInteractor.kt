package com.yahorzabotsin.openvpnclientgate.core.ui.splash

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository

interface SplashServerPreloadInteractor {
    suspend fun preloadServers(cacheOnly: Boolean)
}

class DefaultSplashServerPreloadInteractor(
    private val appContext: Context,
    private val serverRepository: ServerRepository
) : SplashServerPreloadInteractor {
    override suspend fun preloadServers(cacheOnly: Boolean) {
        serverRepository.getServers(
            context = appContext,
            forceRefresh = false,
            cacheOnly = cacheOnly
        )
    }
}