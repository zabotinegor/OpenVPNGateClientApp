package com.yahorzabotsin.openvpnclient.core.servers

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class ServerRepositoryTest {

    private class FakeVpnServersApi(
        private val primaryResponse: String?,
        private val fallbackResponse: String
    ) : VpnServersApi {

        var callCount: Int = 0
            private set

        val calledUrls: MutableList<String> = mutableListOf()

        override suspend fun getServers(url: String): String {
            callCount += 1
            calledUrls += url

            // First call simulates primary, second call simulates fallback.
            return if (callCount == 1) {
                primaryResponse ?: throw IOException("Primary failed")
            } else {
                fallbackResponse
            }
        }
    }

    private fun sampleCsv(servers: List<Server>): String {
        // Mimic vpngate CSV structure: first two lines are headers, then comma-separated values.
        val header = "TITLE, SAMPLE\n" +
                "HEADER, IGNORE\n"
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

    @Test
    fun uses_primary_when_successful() = runBlocking {
        val expectedServer = Server(
            name = "srv-primary",
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

        val api = FakeVpnServersApi(
            primaryResponse = sampleCsv(listOf(expectedServer)),
            fallbackResponse = sampleCsv(emptyList())
        )

        val repo = ServerRepository(api)
        val result = repo.getServers()

        assertEquals(1, api.callCount)
        assertTrue("Primary URL should be called first", api.calledUrls.isNotEmpty())
        // We don't assert exact URL string, only that at least one server is parsed.
        assertEquals(1, result.size)
        assertEquals(expectedServer.name, result[0].name)
        assertEquals(expectedServer.ip, result[0].ip)
    }

    @Test
    fun falls_back_when_primary_fails() = runBlocking {
        val expectedServer = Server(
            name = "srv-fallback",
            city = "CityF",
            country = Country("CountryF"),
            ping = 70,
            signalStrength = SignalStrength.STRONG,
            ip = "5.6.7.8",
            score = 80,
            speed = 2000,
            numVpnSessions = 2,
            uptime = 20,
            totalUsers = 10,
            totalTraffic = 1000,
            logType = "L2",
            operator = "op2",
            message = "msg2",
            configData = "cfg2"
        )

        val api = FakeVpnServersApi(
            primaryResponse = null, // first call throws
            fallbackResponse = sampleCsv(listOf(expectedServer))
        )

        val repo = ServerRepository(api)
        val result = repo.getServers()

        assertEquals(2, api.callCount)
        assertEquals(1, result.size)
        assertEquals(expectedServer.name, result[0].name)
        assertEquals(expectedServer.ip, result[0].ip)
    }
}
