package com.yahorzabotsin.openvpnclientgate.core.ui.splash

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionSyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2SyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore

interface SplashServerPreloadInteractor {
    suspend fun preloadServers(cacheOnly: Boolean)
}

class DefaultSplashServerPreloadInteractor(
    private val serverSyncCoordinator: ServerSelectionSyncCoordinator,
    private val serversV2SyncCoordinator: ServersV2SyncCoordinator,
    private val appContext: Context
) : SplashServerPreloadInteractor {
    override suspend fun preloadServers(cacheOnly: Boolean) {
        val source = UserSettingsStore.load(appContext).serverSource
        if (source == ServerSource.DEFAULT_V2) {
            serversV2SyncCoordinator.syncCountries(
                context = appContext,
                forceRefresh = false,
                cacheOnly = cacheOnly
            )
        } else {
            serverSyncCoordinator.sync(
                forceRefresh = false,
                cacheOnly = cacheOnly,
                clearCacheBeforeRefresh = false
            )
        }
    }
}