package com.yahorzabotsin.openvpnclientgate.core.ui.splash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryV2
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionSyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2SyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettings
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SplashServerPreloadInteractorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun preload_servers_legacy_delegates_to_sync_coordinator_with_expected_flags() = runBlocking {
        UserSettingsStore.save(context, UserSettings(serverSource = ServerSource.LEGACY))
        val coordinator = FakeServerSelectionSyncCoordinator()
        val v2Coordinator = FakeServersV2SyncCoordinator()
        val interactor = DefaultSplashServerPreloadInteractor(coordinator, v2Coordinator, context)

        interactor.preloadServers(cacheOnly = true)

        assertEquals(1, coordinator.callCount)
        assertEquals(false, coordinator.lastForceRefresh)
        assertEquals(true, coordinator.lastCacheOnly)
        assertEquals(false, coordinator.lastClearCacheBeforeRefresh)
        assertEquals(0, v2Coordinator.callCount)
    }

    @Test
    fun preload_servers_default_v2_delegates_to_v2_coordinator() = runBlocking {
        UserSettingsStore.save(context, UserSettings(serverSource = ServerSource.DEFAULT_V2))
        val coordinator = FakeServerSelectionSyncCoordinator()
        val v2Coordinator = FakeServersV2SyncCoordinator()
        val interactor = DefaultSplashServerPreloadInteractor(coordinator, v2Coordinator, context)

        interactor.preloadServers(cacheOnly = false)

        assertEquals(0, coordinator.callCount)
        assertEquals(1, v2Coordinator.callCount)
        assertEquals(false, v2Coordinator.lastForceRefresh)
        assertEquals(false, v2Coordinator.lastCacheOnly)
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

    private class FakeServersV2SyncCoordinator : ServersV2SyncCoordinator {
        var callCount: Int = 0
        var lastForceRefresh: Boolean? = null
        var lastCacheOnly: Boolean? = null

        override suspend fun syncCountries(
            context: Context,
            forceRefresh: Boolean,
            cacheOnly: Boolean
        ): List<CountryV2> {
            callCount += 1
            lastForceRefresh = forceRefresh
            lastCacheOnly = cacheOnly
            return emptyList()
        }

        override suspend fun syncSelectedCountryServers(
            context: Context,
            forceRefresh: Boolean,
            cacheOnly: Boolean
        ) = Unit

        override suspend fun clearCaches(context: Context) = Unit
    }
}
