package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.google.gson.Gson
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryVersionSignal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import org.junit.Assert.assertNotEquals

@RunWith(RobolectricTestRunner::class)
class ServersV2SyncCoordinatorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit()
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("v2_") && it.extension == "json"
        }?.forEach { it.delete() }
    }

    // UT-3.1 — delegates to repository and returns result
    @Test
    fun syncCountries_delegates_to_repository() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":14}]"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        val result = coordinator.syncCountries(context, forceRefresh = true)

        assertEquals(1, result.size)
        assertEquals("JP", result[0].code)
        assertEquals(1, api.countriesCallCount)
    }

    // UT-3.2 — cacheOnly uses file cache, no network call
    @Test
    fun syncCountries_cacheOnly_uses_existing_cache() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"DE","name":"Germany","serverCount":5}]"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        // Populate cache first
        coordinator.syncCountries(context, forceRefresh = true)
        val callsAfterInit = api.countriesCallCount

        // cacheOnly should not make a network call
        coordinator.syncCountries(context, cacheOnly = true)

        assertEquals(callsAfterInit, api.countriesCallCount)
    }

    // UT-3.3 — exception from API propagates out
    @Test(expected = IOException::class)
    fun syncCountries_propagates_exception(): Unit = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = "[]",
            throwOnCountries = IOException("oops")
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        coordinator.syncCountries(context, forceRefresh = true)
    }

    // TS-3 (store parity): syncSelectedCountryServers fetches and saves servers for the
    // currently selected country, preserving the current server index.
    @Test
    fun syncSelectedCountryServers_updates_store_for_selected_country() = runBlocking {
        // Pre-populate selected country store
        val originalServers = listOf(
            makeServer("conf-jp-1", "JP", "Japan", "1.0.0.1"),
            makeServer("conf-jp-2", "JP", "Japan", "1.0.0.2")
        )
        SelectedCountryStore.saveSelection(context, "Japan", originalServers)
        SelectedCountryStore.setCurrentIndex(context, 1)

        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":3}]""",
            serversJson = """{"items":[{"ip":"1.0.0.1","countryCode":"JP","countryName":"Japan","configData":"conf-jp-1"},{"ip":"1.0.0.2","countryCode":"JP","countryName":"Japan","configData":"conf-jp-2"},{"ip":"1.0.0.3","countryCode":"JP","countryName":"Japan","configData":"conf-jp-3"}],"total":3}"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        coordinator.syncSelectedCountryServers(context, forceRefresh = true)

        assertEquals(3, SelectedCountryStore.getServers(context).size)
        // Index should be preserved: previous current was index 1 (conf-jp-2 / 1.0.0.2)
        val current = SelectedCountryStore.currentServer(context)
        assertNotNull(current)
        assertEquals("conf-jp-2", current!!.config)
        assertEquals("1.0.0.2", current.ip)
    }

    // R-3 parity: when country names diverge, selected-country sync resolves by stored country
    // code and still refreshes the selected-country server list.
    @Test
    fun syncSelectedCountryServers_resolves_country_by_code_when_name_differs() = runBlocking {
        val originalServers = listOf(
            makeServer("conf-us-1", "US", "United States", "2.2.2.1")
        )
        SelectedCountryStore.saveSelection(context, "United States", originalServers)

        val api = FakeServersV2Api(
            countriesJson = """[{"code":"US","name":"United States of America","serverCount":2}]""",
            serversJson = """{"items":[{"ip":"2.2.2.1","countryCode":"US","countryName":"United States of America","configData":"conf-us-1"},{"ip":"2.2.2.2","countryCode":"US","countryName":"United States of America","configData":"conf-us-2"}],"total":2}"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        coordinator.syncSelectedCountryServers(context, forceRefresh = true)

        assertEquals(2, SelectedCountryStore.getServers(context).size)
        assertEquals("United States", SelectedCountryStore.getSelectedCountry(context))
    }

    // AC-4.5 parity: cacheOnly sync must not force a network fetch for countries.
    @Test
    fun syncSelectedCountryServers_cache_only_uses_stale_cached_countries() = runBlocking {
        UserSettingsStore.saveCacheTtlMs(context, 0L)
        val originalServers = listOf(
            makeServer("conf-jp-1", "JP", "Japan", "1.0.0.1")
        )
        SelectedCountryStore.saveSelection(context, "Japan", originalServers)

        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":1}]""",
            serversJson = """{"items":[{"ip":"1.0.0.1","countryCode":"JP","countryName":"Japan","configData":"conf-jp-1"}],"total":1}"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        // Prime countries cache, then expire it via TTL=0 and switch to failing network.
        coordinator.syncCountries(context, forceRefresh = true)
        val callsAfterPrime = api.countriesCallCount
        api.throwOnCountries = IOException("offline")

        coordinator.syncSelectedCountryServers(context, cacheOnly = true)

        assertEquals(callsAfterPrime, api.countriesCallCount)
        assertEquals(1, SelectedCountryStore.getServers(context).size)
    }

    // TS-4 (store parity): syncSelectedCountryServers falls back safely when the previously
    // selected server is no longer present after refresh.
    @Test
    fun syncSelectedCountryServers_handles_removed_server_safely() = runBlocking {
        // Pre-populate with a server that will disappear
        val originalServers = listOf(
            makeServer("conf-jp-old", "JP", "Japan", "1.0.0.99"),
            makeServer("conf-jp-2", "JP", "Japan", "1.0.0.2")
        )
        SelectedCountryStore.saveSelection(context, "Japan", originalServers)
        SelectedCountryStore.setCurrentIndex(context, 0) // selecting conf-jp-old

        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":1}]""",
            // conf-jp-old is no longer in the list
            serversJson = """{"items":[{"ip":"1.0.0.2","countryCode":"JP","countryName":"Japan","configData":"conf-jp-2"}],"total":1}"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        coordinator.syncSelectedCountryServers(context, forceRefresh = true)

        val servers = SelectedCountryStore.getServers(context)
        assertEquals(1, servers.size)
        // Index must be valid (no crash, no invalid index)
        val currentIndex = SelectedCountryStore.getCurrentIndex(context)
        assertNotNull(currentIndex)
        assertTrue("Index must be within bounds", currentIndex!! in servers.indices)
    }

    // syncSelectedCountryServers no-ops when no country is selected
    @Test
    fun syncSelectedCountryServers_noops_when_no_selected_country() = runBlocking {
        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":1}]"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        // Should complete without exception or store modification
        coordinator.syncSelectedCountryServers(context, forceRefresh = true)

        assertTrue(SelectedCountryStore.getServers(context).isEmpty())
    }

    // TS-8: forceRefresh=true bypasses the per-country server cache and re-fetches from the
    // network, even when the cache is still within its TTL.
    @Test
    fun syncSelectedCountryServers_forceRefresh_bypasses_server_cache_and_refetches() = runBlocking {
        // Large TTL so cache doesn't expire naturally during the test
        UserSettingsStore.saveCacheTtlMs(context, UserSettingsStore.DEFAULT_CACHE_TTL_MS)

        val originalServers = listOf(
            makeServer("conf-jp-1", "JP", "Japan", "1.0.0.1")
        )
        SelectedCountryStore.saveSelection(context, "Japan", originalServers)

        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":1}]""",
            serversJson = """{"items":[{"ip":"1.0.0.1","countryCode":"JP","countryName":"Japan","configData":"conf-jp-1"}],"total":1}"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        // Prime both countries and server caches (first call — network required)
        coordinator.syncSelectedCountryServers(context, forceRefresh = false)
        val serversCallsAfterPrime = api.serversCallCount
        val countriesCallsAfterPrime = api.countriesCallCount
        assertNotEquals(0, serversCallsAfterPrime)

        // Second call with forceRefresh=true must bypass the still-valid cache
        coordinator.syncSelectedCountryServers(context, forceRefresh = true)

        assertEquals(
            "Countries API must be called again when forceRefresh=true",
            countriesCallsAfterPrime + 1, api.countriesCallCount
        )
        assertEquals(
            "Servers API must be called again when forceRefresh=true, bypassing per-country cache",
            serversCallsAfterPrime + 1, api.serversCallCount
        )
    }

    // TS-9: After DEFAULT_V2 alignment writes refreshed servers to the store,
    // SelectedCountryVersionSignal.version is incremented exactly once per sync call.
    @Test
    fun syncSelectedCountryServers_bumps_version_signal_exactly_once() = runBlocking {
        val originalServers = listOf(
            makeServer("conf-jp-1", "JP", "Japan", "1.0.0.1")
        )
        SelectedCountryStore.saveSelection(context, "Japan", originalServers)

        val initialVersion = SelectedCountryVersionSignal.version.value
        SelectedCountryVersionSignal.restoreForTesting(0L)

        val api = FakeServersV2Api(
            countriesJson = """[{"code":"JP","name":"Japan","serverCount":2}]""",
            serversJson = """{"items":[{"ip":"1.0.0.1","countryCode":"JP","countryName":"Japan","configData":"conf-jp-1"},{"ip":"1.0.0.2","countryCode":"JP","countryName":"Japan","configData":"conf-jp-2"}],"total":2}"""
        )
        val repo = ServersV2Repository(api)
        val coordinator = DefaultServersV2SyncCoordinator(repo)

        coordinator.syncSelectedCountryServers(context, forceRefresh = true)

        assertEquals(
            "SelectedCountryVersionSignal.version must be bumped exactly once after sync",
            1L, SelectedCountryVersionSignal.version.value
        )

        // Restore signal to its pre-test state to avoid affecting other tests
        SelectedCountryVersionSignal.restoreForTesting(initialVersion)
    }

    // --------------- helpers ---------------

    private class FakeServersV2Api(
        private val countriesJson: String = "[]",
        private val serversJson: String = "{\"items\":[]}",
        var throwOnCountries: Exception? = null,
        var throwOnServers: Exception? = null
    ) : ServersV2Api {
        var countriesCallCount = 0
        var serversCallCount = 0

        override suspend fun getCountries(): List<CountryV2> {
            throwOnCountries?.let { throw it }
            countriesCallCount++
            return Gson().fromJson(countriesJson, Array<CountryV2>::class.java).toList()
        }

        override suspend fun getServers(
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): ServersPageResponse {
            throwOnServers?.let { throw it }
            serversCallCount++
            return Gson().fromJson(serversJson, ServersPageResponse::class.java)
        }
    }

    private fun makeServer(config: String, countryCode: String, countryName: String, ip: String) =
        Server(
            lineIndex = 0,
            name = ip,
            city = "",
            country = Country(countryName, countryCode),
            ping = 0,
            signalStrength = SignalStrength.WEAK,
            ip = ip,
            score = 0,
            speed = 0L,
            numVpnSessions = 0,
            uptime = 0L,
            totalUsers = 0L,
            totalTraffic = 0L,
            logType = "",
            operator = "",
            message = "",
            configData = config
        )
}
