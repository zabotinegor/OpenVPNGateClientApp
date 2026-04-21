package com.yahorzabotsin.openvpnclientgate.core.servers.refresh

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryServerSync
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.servers.VpnServersApi
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ServerRefreshWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        clearServerState()
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
        clearServerState()
    }

    @Test
    fun `doWork returns success when repository refresh succeeds`() = runBlocking {
        val api = FixedApi.Success(sampleCsv(listOf(makeServer("srv-refresh"))))
        val repository = ServerRepository(api)

        startKoin {
            modules(module {
                single { repository }
            })
        }

        val worker = createWorker()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, api.callCount)
    }

    @Test
    fun `doWork returns success when repository refresh exhausts internal retries`() = runBlocking {
        val api = FixedApi.Failure(IOException("network down"))
        val repository = ServerRepository(api)

        startKoin {
            modules(module {
                single { repository }
            })
        }

        val worker = createWorker()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals((ServerRefreshWorker.DEFAULT_ADDITIONAL_RETRY_COUNT + 1) * 2, api.callCount)
    }

    @Test
    fun `doWork supports configurable additional retry count`() = runBlocking {
        val api = FixedApi.Failure(IOException("network down"))
        val repository = ServerRepository(api)

        startKoin {
            modules(module {
                single { repository }
            })
        }

        val worker = createWorker(
            inputData = ServerRefreshWorker.retryInputData(additionalRetryCount = 0)
        )

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(2, api.callCount)
    }

    @Test
    fun `doWork returns retry when dependencies are unavailable`() = runBlocking {
        stopKoin()
        val worker = createWorker()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork applies selected country sync when dependency is available`() = runBlocking {
        val servers = listOf(
            makeServer(name = "srv-1", lineIndex = 1, city = "City1", ip = "10.0.0.1", config = "config1"),
            makeServer(name = "srv-2", lineIndex = 2, city = "City2", ip = "10.0.0.2", config = "config2"),
            makeServer(name = "srv-3", lineIndex = 3, city = "City3", ip = "10.0.0.3", config = "config3")
        )
        val api = FixedApi.Success(sampleCsv(servers))
        val repository = ServerRepository(api)
        val selectedCountrySync = SelectedCountryServerSync(context, repository)

        SelectedCountryStore.saveSelection(
            context,
            "Country",
            listOf(
                makeServer(name = "old-1", lineIndex = 1, city = "OldCity1", ip = "10.0.0.1", config = "config1"),
                makeServer(name = "old-2", lineIndex = 2, city = "OldCity2", ip = "10.0.0.2", config = "config2")
            )
        )
        SelectedCountryStore.setCurrentIndex(context, 1)

        startKoin {
            modules(module {
                single { repository }
                single { selectedCountrySync }
            })
        }

        val worker = createWorker()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, api.callCount)
        assertEquals(3, SelectedCountryStore.getServers(context).size)
        assertEquals((2 to 3), SelectedCountryStore.getCurrentPosition(context))
        val current = SelectedCountryStore.currentServer(context)
        assertNotNull(current)
        assertEquals("config2", current?.config)
        assertEquals("10.0.0.2", current?.ip)
    }

    private fun createWorker(inputData: Data = Data.EMPTY): ServerRefreshWorker {
        return TestListenableWorkerBuilder<ServerRefreshWorker>(context)
            .setInputData(inputData)
            .build()
    }

    private fun clearServerState() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("server_cache", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("servers_") }?.forEach { it.delete() }
        UserSettingsStore.saveServerSource(context, ServerSource.DEFAULT)
        UserSettingsStore.saveCacheTtlMs(context, UserSettingsStore.DEFAULT_CACHE_TTL_MS)
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
                "CC",
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
        lineIndex: Int = 1,
        city: String = "City",
        ip: String = "1.2.3.4",
        config: String = "cfg"
    ) = Server(
        lineIndex = lineIndex,
        name = name,
        city = city,
        country = Country("Country"),
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

    private sealed class FixedApi : VpnServersApi {
        var callCount: Int = 0
            protected set

        class Success(private val body: String) : FixedApi() {
            override suspend fun getServers(url: String): okhttp3.ResponseBody {
                callCount++
                return body.toResponseBody("text/plain".toMediaType())
            }
        }

        class Failure(private val error: Exception) : FixedApi() {
            override suspend fun getServers(url: String): okhttp3.ResponseBody {
                callCount++
                throw error
            }
        }
    }
}
