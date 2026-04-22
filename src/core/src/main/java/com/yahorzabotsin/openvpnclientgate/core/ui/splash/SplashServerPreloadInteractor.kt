package com.yahorzabotsin.openvpnclientgate.core.ui.splash

import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionSyncCoordinator

interface SplashServerPreloadInteractor {
    suspend fun preloadServers(cacheOnly: Boolean)
}

class DefaultSplashServerPreloadInteractor(
    private val serverSyncCoordinator: ServerSelectionSyncCoordinator
) : SplashServerPreloadInteractor {
    override suspend fun preloadServers(cacheOnly: Boolean) {
        serverSyncCoordinator.sync(
            forceRefresh = false,
            cacheOnly = cacheOnly,
            clearCacheBeforeRefresh = false
        )
    }
}