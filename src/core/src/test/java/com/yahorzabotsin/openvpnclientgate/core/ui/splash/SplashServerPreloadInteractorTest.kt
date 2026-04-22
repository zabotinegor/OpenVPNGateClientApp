package com.yahorzabotsin.openvpnclientgate.core.ui.splash

import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionSyncCoordinator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SplashServerPreloadInteractorTest {

    @Test
    fun preload_servers_delegates_to_sync_coordinator_with_expected_flags() = runBlocking {
        val coordinator = FakeServerSelectionSyncCoordinator()
        val interactor = DefaultSplashServerPreloadInteractor(coordinator)

        interactor.preloadServers(cacheOnly = true)

        assertEquals(1, coordinator.callCount)
        assertEquals(false, coordinator.lastForceRefresh)
        assertEquals(true, coordinator.lastCacheOnly)
        assertEquals(false, coordinator.lastClearCacheBeforeRefresh)
    }

    private class FakeServerSelectionSyncCoordinator : ServerSelectionSyncCoordinator {
        var callCount: Int = 0
        var lastForceRefresh: Boolean? = null
        var lastCacheOnly: Boolean? = null
        var lastClearCacheBeforeRefresh: Boolean? = null

        override suspend fun sync(
            forceRefresh: Boolean,
            cacheOnly: Boolean,
            clearCacheBeforeRefresh: Boolean
        ): List<Server> {
            callCount += 1
            lastForceRefresh = forceRefresh
            lastCacheOnly = cacheOnly
            lastClearCacheBeforeRefresh = clearCacheBeforeRefresh
            return emptyList()
        }
    }
}
