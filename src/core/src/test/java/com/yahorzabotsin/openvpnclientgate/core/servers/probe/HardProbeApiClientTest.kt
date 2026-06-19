package com.yahorzabotsin.openvpnclientgate.core.servers.probe

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Unit tests for [HardProbeApiClient] covering SUB-03 AC-3.
 *
 * Each test spins up a [MockWebServer], builds a real Retrofit+[ProbeApi] against it,
 * then asserts the expected [ProbeResult] is returned for each mapped HTTP status code.
 *
 * No Robolectric or Android context is required — this is a pure JVM test.
 */
class HardProbeApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HardProbeApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val api = retrofit.create(ProbeApi::class.java)
        client = HardProbeApiClient(api)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // AC-3 — HTTP 202 Accepted → ProbeResult.Queued
    @Test
    fun `probe returns Queued on HTTP 202`() = runTest {
        server.enqueue(MockResponse().setResponseCode(202))

        val result = client.probe(serverId = 1)

        assertEquals(ProbeResult.Queued, result)
    }

    // HTTP 404 Not Found → ProbeResult.NotFound
    @Test
    fun `probe returns NotFound on HTTP 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.probe(serverId = 1)

        assertEquals(ProbeResult.NotFound, result)
    }

    // HTTP 422 Unprocessable Entity → ProbeResult.NoConfigData
    @Test
    fun `probe returns NoConfigData on HTTP 422`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422))

        val result = client.probe(serverId = 1)

        assertEquals(ProbeResult.NoConfigData, result)
    }

    // HTTP 429 Too Many Requests → ProbeResult.RateLimited
    @Test
    fun `probe returns RateLimited on HTTP 429`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        val result = client.probe(serverId = 1)

        assertEquals(ProbeResult.RateLimited, result)
    }

    // HTTP 503 Service Unavailable → ProbeResult.ServiceUnavailable
    @Test
    fun `probe returns ServiceUnavailable on HTTP 503`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val result = client.probe(serverId = 1)

        assertEquals(ProbeResult.ServiceUnavailable, result)
    }

    // HTTP 500 Internal Server Error → ProbeResult.Error(500)
    @Test
    fun `probe returns Error with code on unmapped HTTP status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = client.probe(serverId = 1)

        assertEquals(ProbeResult.Error(code = 500), result)
    }

    // Unexpected 2xx (e.g. 200 OK) → ProbeResult.Error — only 202 is the accepted success code
    @Test
    fun `probe returns Error with code on unexpected 2xx status`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.probe(serverId = 1)

        assertEquals(ProbeResult.Error(code = 200), result)
    }

    // Network/IO failure → ProbeResult.Error(-1)
    @Test
    fun `probe returns Error with code -1 on network IOException`() = runTest {
        // Shut down the server so the request hits an IOException
        server.shutdown()

        val result = client.probe(serverId = 1)

        assertEquals(ProbeResult.Error(code = -1), result)
    }
}
