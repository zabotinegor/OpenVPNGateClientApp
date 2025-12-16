package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yahorzabotsin.openvpnclient.core.settings.ServerSource
import com.yahorzabotsin.openvpnclient.core.settings.UserSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ServerRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class SequenceApi(private val responses: List<() -> String>) : VpnServersApi {
        var callCount: Int = 0
            private set
        val calledUrls: MutableList<String> = mutableListOf()

        override suspend fun getServers(url: String): String {
            calledUrls += url
            val idx = callCount
            callCount += 1
            val block = responses.getOrElse(idx) { { throw IOException("No more responses") } }
            return block()
        }
    }

    @Before
    fun setUp() {
        // Reset user settings and cache between tests
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().apply()
        context.getSharedPreferences("server_cache", Context.MODE_PRIVATE).edit().clear().apply()
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

    private fun makeServer(name: String) = Server(
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

    @Test
    fun uses_primary_when_successful() = runBlocking {
        val expected = makeServer("srv-primary")
        val api = SequenceApi(
            listOf({ sampleCsv(listOf(expected)) }, { sampleCsv(emptyList()) })
        )

        val repo = ServerRepository(api)
        val result = repo.getServers(context, forceRefresh = true)

        assertEquals(1, api.callCount)
        assertEquals(1, result.size)
        assertEquals(expected.name, result[0].name)
        assertTrue("Primary URL should be called first", api.calledUrls.isNotEmpty())
    }

    @Test
    fun falls_back_when_primary_fails_and_switches_source() = runBlocking {
        val expected = makeServer("srv-fallback")
        val api = SequenceApi(
            listOf({ throw IOException("primary down") }, { sampleCsv(listOf(expected)) })
        )

        val repo = ServerRepository(api)
        val result = repo.getServers(context, forceRefresh = true)

        assertEquals(2, api.callCount)
        assertEquals(1, result.size)
        assertEquals(expected.name, result[0].name)
        // After fallback, source should persist as VPN Gate
        assertEquals(ServerSource.VPNGATE, UserSettingsStore.load(context).serverSource)
    }

    @Test
    fun uses_cache_when_fresh() = runBlocking {
        val initial = makeServer("cached")
        val api = SequenceApi(listOf({ sampleCsv(listOf(initial)) }, { throw IOException("should not be called") }))
        val repo = ServerRepository(api)

        val first = repo.getServers(context, forceRefresh = true)
        assertEquals(1, api.callCount)
        assertEquals("cached", first.single().name)

        val second = repo.getServers(context, forceRefresh = false)
        assertEquals("cached", second.single().name)
        // callCount stays 1 because cache served the second call
        assertEquals(1, api.callCount)
    }

    @Test
    fun force_refresh_bypasses_cache_and_updates_it() = runBlocking {
        val initial = makeServer("old")
        val updated = makeServer("new")
        val api = SequenceApi(
            listOf(
                { sampleCsv(listOf(initial)) },
                { sampleCsv(listOf(updated)) }
            )
        )
        val repo = ServerRepository(api)

        val first = repo.getServers(context, forceRefresh = true)
        assertEquals("old", first.single().name)
        assertEquals(1, api.callCount)

        val second = repo.getServers(context, forceRefresh = true)
        assertEquals("new", second.single().name)
        assertEquals(2, api.callCount)

        // Cache now contains updated; a non-forced call should hit cache
        val third = repo.getServers(context, forceRefresh = false)
        assertEquals("new", third.single().name)
        assertEquals(2, api.callCount)
    }

    @Test
    fun throws_when_both_primary_and_fallback_fail() = runBlocking {
        val api = SequenceApi(listOf({ throw IOException("fail") }, { throw IOException("fail2") }))
        val repo = ServerRepository(api)

        try {
            repo.getServers(context, forceRefresh = true)
            fail("Expected IOException when both primary and fallback fail")
        } catch (e: IOException) {
            // expected
        }

        assertEquals(2, api.callCount)
    }

    @Test
    fun propagates_error_after_fallback_attempts() = runBlocking {
        val api = SequenceApi(listOf({ throw IllegalStateException("boom") }, { throw IOException("fallback fail") }))
        val repo = ServerRepository(api)

        try {
            repo.getServers(context, forceRefresh = true)
            fail("Expected failure to propagate after retries")
        } catch (e: Exception) {
            // expected
        }

        // Both URLs attempted
        assertEquals(2, api.callCount)
    }
}
