package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryV2
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2SyncCoordinator
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerSelectionSyncCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("server_cache", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().apply()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("servers_") }?.forEach { it.delete() }
        UserSettingsStore.saveServerSource(context, ServerSource.LEGACY)
        UserSettingsStore.saveCacheTtlMs(context, UserSettingsStore.DEFAULT_CACHE_TTL_MS)
    }

    @Test
    fun sync_returns_servers_and_updates_selected_country() = runBlocking {
        val servers = listOf(
            makeServer(name = "srv-1", city = "City1", lineIndex = 1, ip = "10.0.0.1", config = "config1"),
            makeServer(name = "srv-2", city = "City2", lineIndex = 2, ip = "10.0.0.2", config = "config2")
        )
        val repository = ServerRepository(FixedApi(sampleCsv(servers)))
        val selectedCountrySync = SelectedCountryServerSync(context, repository)
        val coordinator = DefaultServerSelectionSyncCoordinator(context, repository, selectedCountrySync, NoOpV2SyncCoordinator)

        SelectedCountryStore.saveSelection(
            context,
            "Country",
            listOf(
                makeServer(name = "old-1", city = "OldCity1", lineIndex = 1, ip = "10.0.0.1", config = "config1")
            )
        )

        val fresh = coordinator.sync(
            forceRefresh = true,
            cacheOnly = false,
            clearCacheBeforeRefresh = false
        )

        assertEquals(2, fresh.size)
        val selectedCurrent = SelectedCountryStore.currentServer(context)
        assertNotNull(selectedCurrent)
        assertEquals("10.0.0.1", selectedCurrent?.ip)
    }

    @Test
    fun sync_clears_old_cache_before_refresh_when_requested() = runBlocking {
        val oldRepository = ServerRepository(FixedApi(sampleCsv(listOf(makeServer("old", lineIndex = 1)))))
        oldRepository.getServers(context, forceRefresh = true)

        val cacheFilesBefore = context.cacheDir.listFiles()?.filter { it.name.startsWith("servers_") } ?: emptyList()
        assertTrue(cacheFilesBefore.isNotEmpty())

        val newRepository = ServerRepository(FixedApi(sampleCsv(listOf(makeServer("new", lineIndex = 1)))))
        val coordinator = DefaultServerSelectionSyncCoordinator(
            context,
            newRepository,
            SelectedCountryServerSync(context, newRepository),
            NoOpV2SyncCoordinator
        )

        coordinator.sync(
            forceRefresh = true,
            cacheOnly = false,
            clearCacheBeforeRefresh = true
        )

        val cacheFilesAfter = context.cacheDir.listFiles()?.filter { it.name.startsWith("servers_") } ?: emptyList()
        assertEquals(1, cacheFilesAfter.size)
    }

    // TS-3: DEFAULT_V2 sync calls syncSelectedCountryServers with the same forceRefresh flag.
    @Test
    fun sync_default_v2_delegates_selected_country_sync_with_matching_flags() = runBlocking {
        UserSettingsStore.saveServerSource(context, ServerSource.DEFAULT_V2)
        val v2Coordinator = TrackingV2SyncCoordinator()
        val repository = ServerRepository(FixedApi(sampleCsv(emptyList())))
        val coordinator = DefaultServerSelectionSyncCoordinator(
            context, repository, SelectedCountryServerSync(context, repository), v2Coordinator
        )

        coordinator.sync(forceRefresh = true, cacheOnly = false, clearCacheBeforeRefresh = false)

        assertEquals(1, v2Coordinator.syncCountriesCallCount)
        assertEquals(1, v2Coordinator.syncSelectedCountryCallCount)
        assertEquals(true, v2Coordinator.lastSelectedCountryForceRefresh)
        assertEquals(false, v2Coordinator.lastSelectedCountryCacheOnly)
    }

    // TS-4: DEFAULT_V2 sync with clearCacheBeforeRefresh=true forwards forceRefresh=true to
    // syncSelectedCountryServers.
    @Test
    fun sync_default_v2_clears_cache_flag_propagated_to_selected_country_sync() = runBlocking {
        UserSettingsStore.saveServerSource(context, ServerSource.DEFAULT_V2)
        val v2Coordinator = TrackingV2SyncCoordinator()
        val repository = ServerRepository(FixedApi(sampleCsv(emptyList())))
        val coordinator = DefaultServerSelectionSyncCoordinator(
            context, repository, SelectedCountryServerSync(context, repository), v2Coordinator
        )

        coordinator.sync(forceRefresh = false, cacheOnly = false, clearCacheBeforeRefresh = true)

        // clearCacheBeforeRefresh=true must propagate as forceRefresh=true to both country and
        // selected-country sync calls (AC-4.5 cache-clearing rule)
        assertEquals(true, v2Coordinator.lastCountriesForceRefresh)
        assertEquals(true, v2Coordinator.lastSelectedCountryForceRefresh)
    }

    // TS-7: DEFAULT_V2 selected-country sync failure does not propagate to the caller; the
    // coordinator returns emptyList() gracefully (Legacy CSV tests unaffected).
    @Test
    fun sync_default_v2_selected_country_sync_failure_does_not_throw() = runBlocking {
        UserSettingsStore.saveServerSource(context, ServerSource.DEFAULT_V2)
        val v2Coordinator = ThrowingSelectedCountryV2SyncCoordinator()
        val repository = ServerRepository(FixedApi(sampleCsv(emptyList())))
        val coordinator = DefaultServerSelectionSyncCoordinator(
            context, repository, SelectedCountryServerSync(context, repository), v2Coordinator
        )

        val result = coordinator.sync(forceRefresh = true, cacheOnly = false, clearCacheBeforeRefresh = false)

        assertEquals(emptyList<Server>(), result)
    }

    // TS-7 (Legacy regression): Legacy CSV source is unaffected by the DEFAULT_V2 path.
    @Test
    fun sync_legacy_source_does_not_call_v2_selected_country_sync() = runBlocking {
        UserSettingsStore.saveServerSource(context, ServerSource.LEGACY)
        val v2Coordinator = TrackingV2SyncCoordinator()
        val servers = listOf(makeServer(name = "srv-1", lineIndex = 1, ip = "10.0.0.1", config = "c1"))
        val repository = ServerRepository(FixedApi(sampleCsv(servers)))
        val coordinator = DefaultServerSelectionSyncCoordinator(
            context, repository, SelectedCountryServerSync(context, repository), v2Coordinator
        )

        coordinator.sync(forceRefresh = true, cacheOnly = false, clearCacheBeforeRefresh = false)

        assertEquals(0, v2Coordinator.syncCountriesCallCount)
        assertEquals(0, v2Coordinator.syncSelectedCountryCallCount)
    }

    private fun sampleCsv(servers: List<Server>): String {
        val header = "TITLE, SAMPLE\nHEADER, IGNORE\n"
        val body = servers.joinToString(separator = "\n") { s ->
            listOf(
                s.name,
                s.ip,
                s.score.toString(),
                s.ping.toString(),
                s.speed.toString(),
                s.country.name,
                s.country.code ?: "CC",
                s.numVpnSessions.toString(),
                s.uptime.toString(),
                s.totalUsers.toString(),
                s.totalTraffic.toString(),
                s.logType,
                s.operator,
                s.message,
                s.configData
            ).joinToString(",")
        }
        return header + body
    }

    private fun makeServer(
        name: String,
        city: String = "City",
        lineIndex: Int,
        country: String = "Country",
        ip: String = "1.2.3.4",
        config: String = "cfg"
    ) = Server(
        lineIndex = lineIndex,
        name = name,
        city = city,
        country = Country(country),
        ping = 50,
        signalStrength = SignalStrength.STRONG,
        ip = ip,
        score = 100,
        speed = 1000,
        numVpnSessions = 1,
        uptime = 10,
        totalUsers = 5,
        totalTraffic = 500,
        logType = "L",
        operator = "op",
        message = "msg",
        configData = config
    )

    private object NoOpV2SyncCoordinator : ServersV2SyncCoordinator {
        override suspend fun syncCountries(
            context: Context,
            forceRefresh: Boolean,
            cacheOnly: Boolean
        ): List<CountryV2> = emptyList()

        override suspend fun syncSelectedCountryServers(
            context: Context,
            forceRefresh: Boolean,
            cacheOnly: Boolean
        ) = Unit

        override suspend fun clearCaches(context: Context) = Unit
    }

    private class TrackingV2SyncCoordinator : ServersV2SyncCoordinator {
        var syncCountriesCallCount = 0
        var syncSelectedCountryCallCount = 0
        var lastCountriesForceRefresh: Boolean? = null
        var lastSelectedCountryForceRefresh: Boolean? = null
        var lastSelectedCountryCacheOnly: Boolean? = null

        override suspend fun syncCountries(
            context: Context,
            forceRefresh: Boolean,
            cacheOnly: Boolean
        ): List<CountryV2> {
            syncCountriesCallCount++
            lastCountriesForceRefresh = forceRefresh
            return emptyList()
        }

        override suspend fun syncSelectedCountryServers(
            context: Context,
            forceRefresh: Boolean,
            cacheOnly: Boolean
        ) {
            syncSelectedCountryCallCount++
            lastSelectedCountryForceRefresh = forceRefresh
            lastSelectedCountryCacheOnly = cacheOnly
        }

        override suspend fun clearCaches(context: Context) = Unit
    }

    private class ThrowingSelectedCountryV2SyncCoordinator : ServersV2SyncCoordinator {
        override suspend fun syncCountries(
            context: Context,
            forceRefresh: Boolean,
            cacheOnly: Boolean
        ): List<CountryV2> = emptyList()

        override suspend fun syncSelectedCountryServers(
            context: Context,
            forceRefresh: Boolean,
            cacheOnly: Boolean
        ) {
            throw RuntimeException("simulated sync failure")
        }

        override suspend fun clearCaches(context: Context) = Unit
    }

    private class FixedApi(private val body: String) : VpnServersApi {
        override suspend fun getServers(url: String): ResponseBody {
            return body.toResponseBody("text/plain".toMediaType())
        }
    }
}
