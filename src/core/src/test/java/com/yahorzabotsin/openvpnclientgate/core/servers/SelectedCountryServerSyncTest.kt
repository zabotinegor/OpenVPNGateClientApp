package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectedCountryServerSyncTest {

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
    fun `syncAfterRefresh updates selected country list and preserves current config`() = runBlocking {
        val servers = listOf(
            makeServer(name = "srv-1", city = "City1", lineIndex = 1, ip = "10.0.0.1", config = "config1"),
            makeServer(name = "srv-2", city = "City2", lineIndex = 2, ip = "10.0.0.2", config = "config2"),
            makeServer(name = "srv-3", city = "City3", lineIndex = 3, ip = "10.0.0.3", config = "config3")
        )
        val repository = ServerRepository(FixedApi(sampleCsv(servers)))

        SelectedCountryStore.saveSelection(
            context,
            "Country",
            listOf(
                makeServer(name = "old-1", city = "OldCity1", lineIndex = 1, ip = "10.0.0.1", config = "config1"),
                makeServer(name = "old-2", city = "OldCity2", lineIndex = 2, ip = "10.0.0.2", config = "config2")
            )
        )
        SelectedCountryStore.setCurrentIndex(context, 1)

        val freshServers = repository.getServers(context, forceRefresh = true, cacheOnly = false)
        val sync = SelectedCountryServerSync(context, repository)

        sync.syncAfterRefresh(freshServers)

        assertEquals(3, SelectedCountryStore.getServers(context).size)
        assertEquals((2 to 3), SelectedCountryStore.getCurrentPosition(context))
        val current = SelectedCountryStore.currentServer(context)
        assertNotNull(current)
        assertEquals("config2", current?.config)
        assertEquals("10.0.0.2", current?.ip)
    }

    @Test
    fun `syncAfterRefresh does nothing when selected country missing from fresh list`() = runBlocking {
        val servers = listOf(
            makeServer(name = "srv-1", city = "City1", lineIndex = 1, country = "OtherCountry", ip = "10.1.0.1", config = "config1")
        )
        val repository = ServerRepository(FixedApi(sampleCsv(servers)))

        SelectedCountryStore.saveSelection(
            context,
            "Country",
            listOf(
                makeServer(name = "old-1", city = "OldCity1", lineIndex = 1, ip = "10.0.0.1", config = "config1"),
                makeServer(name = "old-2", city = "OldCity2", lineIndex = 2, ip = "10.0.0.2", config = "config2")
            )
        )
        SelectedCountryStore.setCurrentIndex(context, 1)

        val freshServers = repository.getServers(context, forceRefresh = true, cacheOnly = false)
        val sync = SelectedCountryServerSync(context, repository)

        sync.syncAfterRefresh(freshServers)

        assertEquals(2, SelectedCountryStore.getServers(context).size)
        assertEquals((2 to 2), SelectedCountryStore.getCurrentPosition(context))
    }

    @Test
    fun `syncAfterRefresh relocalizes by country code when stored name is stale`() = runBlocking {
        val servers = listOf(
            makeServer(name = "srv-au-1", city = "Sydney", lineIndex = 1, country = "Australia", countryCode = "AU", ip = "10.2.0.1", config = "config-au-1"),
            makeServer(name = "srv-au-2", city = "Melbourne", lineIndex = 2, country = "Australia", countryCode = "AU", ip = "10.2.0.2", config = "config-au-2")
        )
        val repository = ServerRepository(FixedApi(sampleCsv(servers)))

        SelectedCountryStore.saveSelection(
            context,
            "Australia",
            listOf(
                makeServer(name = "old-1", city = "OldSydney", lineIndex = 1, country = "Australia", countryCode = "AU", ip = "10.2.0.1", config = "config-au-1"),
                makeServer(name = "old-2", city = "OldMelbourne", lineIndex = 2, country = "Australia", countryCode = "AU", ip = "10.2.0.2", config = "config-au-2")
            )
        )
        SelectedCountryStore.setCurrentIndex(context, 1)

        val freshServers = repository.getServers(context, forceRefresh = true, cacheOnly = false)
        val sync = SelectedCountryServerSync(context, repository)

        // Simulate the stored name being stale while the stable country code remains the same.
        SelectedCountryStore.updateSelectedCountryName(context, "Australia-old")
        sync.syncAfterRefresh(freshServers)

        assertEquals("Australia", SelectedCountryStore.getSelectedCountry(context))
        assertEquals((2 to 2), SelectedCountryStore.getCurrentPosition(context))
        val current = SelectedCountryStore.currentServer(context)
        assertNotNull(current)
        assertEquals("config-au-2", current?.config)
        assertEquals("10.2.0.2", current?.ip)
    }

    @Test
    fun `syncAfterRefresh matches selected country by name case-insensitively when code missing`() = runBlocking {
        val servers = listOf(
            makeServer(name = "srv-1", city = "City1", lineIndex = 1, country = "Australia", ip = "10.3.0.1", config = "config-1"),
            makeServer(name = "srv-2", city = "City2", lineIndex = 2, country = "Australia", ip = "10.3.0.2", config = "config-2")
        )
        val repository = ServerRepository(FixedApi(sampleCsv(servers)))

        SelectedCountryStore.saveSelection(
            context,
            "australia",
            listOf(
                makeServer(name = "old-1", city = "Old1", lineIndex = 1, country = "australia", ip = "10.3.0.1", config = "config-1")
            )
        )

        val freshServers = repository.getServers(context, forceRefresh = true, cacheOnly = false)
        val sync = SelectedCountryServerSync(context, repository)

        sync.syncAfterRefresh(freshServers)

        assertEquals("Australia", SelectedCountryStore.getSelectedCountry(context))
        assertEquals(2, SelectedCountryStore.getServers(context).size)
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
        city: String,
        lineIndex: Int,
        country: String = "Country",
        countryCode: String? = null,
        ip: String,
        config: String
    ) = Server(
        lineIndex = lineIndex,
        name = name,
        city = city,
        country = if (countryCode.isNullOrBlank()) Country(country) else Country(country, countryCode),
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

    private class FixedApi(private val body: String) : VpnServersApi {
        override suspend fun getServers(url: String): ResponseBody {
            return body.toResponseBody("text/plain".toMediaType())
        }
    }
}
