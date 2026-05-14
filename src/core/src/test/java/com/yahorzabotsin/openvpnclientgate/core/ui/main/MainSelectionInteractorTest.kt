package com.yahorzabotsin.openvpnclientgate.core.ui.main

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryV2
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersPageResponse
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2Api
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2Repository
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerV2
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.VpnServersApi
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettings
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for DEFAULT_V2 initial selection parity (AC-1, TS-1, TS-2).
 */
@RunWith(RobolectricTestRunner::class)
class MainSelectionInteractorTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("servers_v2_cache", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().apply()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("v2_") }?.forEach { it.delete() }
        UserSettingsStore.save(context, UserSettings(serverSource = ServerSource.DEFAULT_V2))
    }

    // TS-1: DEFAULT_V2 startup with empty store loads countries, hydrates first country server
    // list, saves selection, and returns a usable initial selection for the main screen.
    @Test
    fun loadInitialSelection_v2_empty_store_returns_first_country_first_server() = runBlocking {
        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("JP", "Japan", 2)),
            serversPerCountry = mapOf(
                "JP" to listOf(
                    ServerV2("1.2.3.4", "JP", "Japan", "config-jp-1"),
                    ServerV2("1.2.3.5", "JP", "Japan", "config-jp-2")
                )
            )
        )
        val v2Repo = ServersV2Repository(v2Api)
        val serverRepo = ServerRepository(EmptyCsvApi())
        val interactor = DefaultMainSelectionInteractor(context, serverRepo, v2Repo)

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        assertEquals("Japan", result!!.country)
        assertEquals("config-jp-1", result.config)
        assertEquals("JP", result.countryCode)
        assertEquals("1.2.3.4", result.ip)

        // Store must be populated so VPN and auto-switch flows can use it
        val stored = SelectedCountryStore.currentServer(context)
        assertNotNull(stored)
        assertEquals("config-jp-1", stored!!.config)
        assertEquals(2, SelectedCountryStore.getServers(context).size)
        assertEquals("Japan", SelectedCountryStore.getSelectedCountry(context))
    }

    // TS-2: DEFAULT_V2 startup with an already stored current server reuses the stored
    // selection without replacing it with the first country / first server.
    @Test
    fun loadInitialSelection_v2_with_stored_selection_reuses_stored_server() = runBlocking {
        // Pre-populate store as if a previous session had selected the second server
        SelectedCountryStore.saveSelection(
            context, "Germany",
            listOf(
                makeStoredServer("config-de-1", "DE", "1.0.0.1"),
                makeStoredServer("config-de-2", "DE", "1.0.0.2")
            )
        )
        SelectedCountryStore.setCurrentIndex(context, 1)

        // The API would return Japan as first country, but we should NOT reset to it
        val v2Api = FakeServersV2Api(
            countries = listOf(CountryV2("JP", "Japan", 1)),
            serversPerCountry = mapOf("JP" to listOf(ServerV2("9.9.9.9", "JP", "Japan", "config-jp-1")))
        )
        val v2Repo = ServersV2Repository(v2Api)
        val serverRepo = ServerRepository(EmptyCsvApi())
        val interactor = DefaultMainSelectionInteractor(context, serverRepo, v2Repo)

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNotNull(result)
        // Must reuse stored selection, not the API first country
        assertEquals("Germany", result!!.country)
        assertEquals("config-de-2", result.config)
        assertEquals("1.0.0.2", result.ip)

        // Store index must remain at the second server
        assertEquals(1, SelectedCountryStore.getCurrentIndex(context))
    }

    // Verify that null is returned gracefully when no countries are available (AC-1.3 edge case)
    @Test
    fun loadInitialSelection_v2_empty_countries_returns_null() = runBlocking {
        val v2Api = FakeServersV2Api(countries = emptyList(), serversPerCountry = emptyMap())
        val v2Repo = ServersV2Repository(v2Api)
        val serverRepo = ServerRepository(EmptyCsvApi())
        val interactor = DefaultMainSelectionInteractor(context, serverRepo, v2Repo)

        val result = interactor.loadInitialSelection(cacheOnly = false)

        assertNull(result)
    }

    // --------------- helpers ---------------

    private fun makeStoredServer(
        config: String,
        countryCode: String,
        ip: String
    ) = com.yahorzabotsin.openvpnclientgate.core.servers.Server(
        lineIndex = 0,
        name = ip,
        city = "",
        country = com.yahorzabotsin.openvpnclientgate.core.servers.Country("Germany", countryCode),
        ping = 0,
        signalStrength = com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength.WEAK,
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

    private class FakeServersV2Api(
        private val countries: List<CountryV2>,
        private val serversPerCountry: Map<String, List<ServerV2>>
    ) : ServersV2Api {
        override suspend fun getCountries(locale: String): List<CountryV2> = countries
        override suspend fun getServers(
            locale: String,
            countryCode: String,
            isActive: Boolean,
            skip: Int,
            take: Int
        ): ServersPageResponse {
            val items = serversPerCountry[countryCode.uppercase()] ?: emptyList()
            return ServersPageResponse(items = items, total = items.size)
        }
    }

    private class EmptyCsvApi : VpnServersApi {
        override suspend fun getServers(url: String) =
            "TITLE\nHEADER\n".toResponseBody("text/plain".toMediaType())
    }
}
