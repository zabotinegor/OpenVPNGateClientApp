package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.CancellationException

interface ServerSelectionSyncCoordinator {
    suspend fun sync(
        forceRefresh: Boolean,
        cacheOnly: Boolean,
        clearCacheBeforeRefresh: Boolean = false
    ): List<Server>
}

class DefaultServerSelectionSyncCoordinator(
    private val appContext: Context,
    private val serverRepository: ServerRepository,
    private val selectedCountrySync: SelectedCountryServerSync,
    private val serversV2SyncCoordinator: ServersV2SyncCoordinator
) : ServerSelectionSyncCoordinator {

    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ServerSelectionSyncCoordinator"

    override suspend fun sync(
        forceRefresh: Boolean,
        cacheOnly: Boolean,
        clearCacheBeforeRefresh: Boolean
    ): List<Server> {
        val source = UserSettingsStore.load(appContext).serverSource
        if (source == ServerSource.DEFAULT_V2) {
            serversV2SyncCoordinator.syncCountries(
                context = appContext,
                forceRefresh = forceRefresh || clearCacheBeforeRefresh,
                cacheOnly = cacheOnly
            )
            return emptyList()
        }

        if (clearCacheBeforeRefresh) {
            runCatching { serverRepository.clearServerCache(appContext) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    AppLog.w(tag, "Failed to clear server cache before sync", e)
                }
        }

        val servers = serverRepository.getServers(
            context = appContext,
            forceRefresh = forceRefresh,
            cacheOnly = cacheOnly
        )

        runCatching { selectedCountrySync.syncAfterRefresh(servers) }
            .onFailure { syncError ->
                if (syncError is CancellationException) {
                    throw syncError
                }
                AppLog.w(tag, "Selected country sync failed after server refresh", syncError)
            }

        return servers
    }
}