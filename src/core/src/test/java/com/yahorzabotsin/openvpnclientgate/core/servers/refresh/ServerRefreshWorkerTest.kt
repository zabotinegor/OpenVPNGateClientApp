package com.yahorzabotsin.openvpnclientgate.core.servers.refresh

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
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
import org.junit.Assert.assertTrue
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
    fun `doWork returns retry when repository refresh throws`() = runBlocking {
        val api = FixedApi.Failure(IOException("network down"))
        val repository = ServerRepository(api)

        startKoin {
            modules(module {
                single { repository }
            })
        }

        val worker = createWorker()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue(api.callCount >= 1)
    }

    @Test
    fun `doWork returns retry when dependencies are unavailable`() = runBlocking {
        stopKoin()
        val worker = createWorker()

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    private fun createWorker(): ServerRefreshWorker {
        return TestListenableWorkerBuilder<ServerRefreshWorker>(context).build()
    }

    private fun clearServerState() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("server_cache", Context.MODE_PRIVATE).edit().clear().apply()
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

    private fun makeServer(name: String, lineIndex: Int = 1) = Server(
        lineIndex = lineIndex,
        name = name,
        city = "City",
        country = Country("Country"),
        ping = 50,
        signalStrength = SignalStrength.STRONG,
        ip = "1.2.3.4",
        score = 100,
        speed = 1000,
        numVpnSessions = 1,
        uptime = 10,
        totalUsers = 5,
        totalTraffic = 500,
        logType = "L",
        operator = "op",
        message = "msg",
        configData = "cfg"
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
