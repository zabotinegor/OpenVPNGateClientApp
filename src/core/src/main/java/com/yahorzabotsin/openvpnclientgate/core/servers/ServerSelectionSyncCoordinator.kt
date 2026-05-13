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
        val settings = UserSettingsStore.load(appContext)
        val source = settings.serverSource
        if (source == ServerSource.DEFAULT_V2) {
            try {
                if (clearCacheBeforeRefresh) {
                    serversV2SyncCoordinator.clearCaches(appContext)
                }
                serversV2SyncCoordinator.syncCountries(
                    context = appContext,
                    forceRefresh = forceRefresh || clearCacheBeforeRefresh,
                    cacheOnly = cacheOnly
                )
                // AC-4.2/AC-4.3: Refresh the currently selected country's server list so the
                // selected-country store stays aligned after every sync trigger (foreground, periodic,
                // settings-triggered) without requiring the user to reopen the country screen.
                runCatching {
                    serversV2SyncCoordinator.syncSelectedCountryServers(
                        context = appContext,
                        forceRefresh = forceRefresh || clearCacheBeforeRefresh,
                        cacheOnly = cacheOnly
                    )
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    AppLog.w(tag, "DEFAULT_V2 selected country sync failed after country list refresh", e)
                }
                return emptyList()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(tag, "DEFAULT_V2 sync failed; falling back to legacy CSV chain", e)

                if (clearCacheBeforeRefresh) {
                    runCatching { serverRepository.clearServerCache(appContext) }
                        .onFailure { clearError ->
                            if (clearError is CancellationException) throw clearError
                            AppLog.w(tag, "Failed to clear legacy cache before DEFAULT_V2 fallback", clearError)
                        }
                }

                val fallbackSettings = settings.copy(serverSource = ServerSource.LEGACY)
                val servers = serverRepository.getServers(
                    context = appContext,
                    forceRefresh = forceRefresh || clearCacheBeforeRefresh,
                    cacheOnly = cacheOnly,
                    settingsOverride = fallbackSettings
                )

                val persistedSource = UserSettingsStore.load(appContext).serverSource
                if (persistedSource == ServerSource.DEFAULT_V2) {
                    UserSettingsStore.saveServerSource(appContext, ServerSource.LEGACY)
                    AppLog.w(tag, "DEFAULT_V2 primary failed; switched persisted source to Legacy CSV fallback.")
                }

                runCatching { selectedCountrySync.syncAfterRefresh(servers) }
                    .onFailure { syncError ->
                        if (syncError is CancellationException) {
                            throw syncError
                        }
                        AppLog.w(tag, "Selected country sync failed after DEFAULT_V2 fallback refresh", syncError)
                    }

                return servers
            }
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