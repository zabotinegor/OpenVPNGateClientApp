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
        val coordinator = DefaultServerSelectionSyncCoordinator(context, repository, selectedCountrySync)

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
            SelectedCountryServerSync(context, newRepository)
        )

        coordinator.sync(
            forceRefresh = true,
            cacheOnly = false,
            clearCacheBeforeRefresh = true
        )

        val cacheFilesAfter = context.cacheDir.listFiles()?.filter { it.name.startsWith("servers_") } ?: emptyList()
        assertEquals(1, cacheFilesAfter.size)
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

    private class FixedApi(private val body: String) : VpnServersApi {
        override suspend fun getServers(url: String): ResponseBody {
            return body.toResponseBody("text/plain".toMediaType())
        }
    }
}
