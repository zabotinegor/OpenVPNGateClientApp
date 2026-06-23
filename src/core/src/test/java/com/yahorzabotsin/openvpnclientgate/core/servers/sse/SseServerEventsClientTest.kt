package com.yahorzabotsin.openvpnclientgate.core.servers.sse

import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionSyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SseServerEventsClientTest {

    @Before
    fun setUp() {
        // Plant a no-op Timber tree so AppLog routes through Timber instead of android.util.Log
        if (Timber.forest().isEmpty()) {
            Timber.plant(Timber.DebugTree())
        }
    }

    @After
    fun tearDown() {
        Timber.uprootAll()
    }

    // ── Backoff unit tests (pure, no network) ────────────────────────────────

    @Test
    fun `backoffDelayMs returns INITIAL_BACKOFF_MS for attempt 0`() {
        val client = buildClient()
        assertEquals(
            SseServerEventsClient.INITIAL_BACKOFF_MS,
            client.backoffDelayMs(0)
        )
    }

    @Test
    fun `backoffDelayMs returns INITIAL_BACKOFF_MS for attempt 1`() {
        val client = buildClient()
        // attempt 1: 5000 * 2^0 = 5000
        assertEquals(
            SseServerEventsClient.INITIAL_BACKOFF_MS,
            client.backoffDelayMs(1)
        )
    }

    @Test
    fun `backoffDelayMs doubles for each additional attempt`() {
        val client = buildClient()
        assertEquals(
            SseServerEventsClient.INITIAL_BACKOFF_MS * 2,
            client.backoffDelayMs(2)
        )
        assertEquals(
            SseServerEventsClient.INITIAL_BACKOFF_MS * 4,
            client.backoffDelayMs(3)
        )
        assertEquals(
            SseServerEventsClient.INITIAL_BACKOFF_MS * 8,
            client.backoffDelayMs(4)
        )
    }

    @Test
    fun `backoffDelayMs is capped at MAX_BACKOFF_MS`() {
        val client = buildClient()
        val delay = client.backoffDelayMs(100)
        assertTrue(
            "Expected delay <= MAX_BACKOFF_MS but was $delay",
            delay <= SseServerEventsClient.MAX_BACKOFF_MS
        )
        assertEquals(SseServerEventsClient.MAX_BACKOFF_MS, delay)
    }

    @Test
    fun `backoffDelayMs cap is exactly 5 minutes`() {
        assertEquals(300_000L, SseServerEventsClient.MAX_BACKOFF_MS)
    }

    // ── Lifecycle start/stop ─────────────────────────────────────────────────

    @Test
    fun `start and stop are idempotent`() {
        val client = buildClient()

        // Multiple start calls should not throw
        client.start()
        client.start() // second call should be a no-op

        // Multiple stop calls should not throw
        client.stop()
        client.stop()
    }

    @Test
    fun `stop after stop does not throw`() {
        val client = buildClient()
        client.stop()
        client.stop()
    }

    @Test
    fun `start then stop resets reconnect attempt counter`() {
        val client = buildClient()
        client.start()

        // Increment attempt counter manually to simulate reconnects
        client.reconnectAttempt.set(5)

        client.stop()

        // After stop the counter is reset
        assertEquals(0, client.reconnectAttempt.get())
    }

    // ── SSE event integration via MockWebServer ───────────────────────────────

    @Test
    fun `servers-changed event triggers sync coordinator`() {
        val server = MockWebServer()
        val syncLatch = CountDownLatch(1)
        var syncCalled = false

        val fakeCoordinator = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(
                forceRefresh: Boolean,
                cacheOnly: Boolean,
                clearCacheBeforeRefresh: Boolean
            ): List<Server> {
                syncCalled = true
                syncLatch.countDown()
                return emptyList()
            }

            override suspend fun syncSelectedCountryServersForRelocalization(
                forceRefresh: Boolean,
                cacheOnly: Boolean
            ) = Unit
        }

        val sseBody = buildString {
            append("event: servers-changed\n")
            append("data: {}\n")
            append("\n")
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sseBody, 256)
        )

        server.start()
        val url = server.url("/api/v1/servers/events").toString()

        val okHttpClient = OkHttpClient.Builder()
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlProvider = { url }
        )

        try {
            client.start()
            val received = syncLatch.await(10, TimeUnit.SECONDS)
            assertTrue("sync coordinator was not called within 10s", received)
            assertTrue(syncCalled)
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `unrecognized event type does not trigger sync`() {
        val server = MockWebServer()
        var syncCalled = false

        val fakeCoordinator = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(
                forceRefresh: Boolean,
                cacheOnly: Boolean,
                clearCacheBeforeRefresh: Boolean
            ): List<Server> {
                syncCalled = true
                return emptyList()
            }

            override suspend fun syncSelectedCountryServersForRelocalization(
                forceRefresh: Boolean,
                cacheOnly: Boolean
            ) = Unit
        }

        // Send an unrecognized event type; connection then closes normally
        val sseBody = buildString {
            append("event: unknown-event\n")
            append("data: test\n")
            append("\n")
        }
        // Enqueue a second response that hangs so the client stays connected briefly
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setChunkedBody(sseBody, 256)
        )

        server.start()
        val url = server.url("/api/v1/servers/events").toString()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlProvider = { url }
        )

        try {
            client.start()
            // Give the client time to connect and process the single event
            Thread.sleep(1_500)
            assertFalse("sync should NOT be called for unknown event type", syncCalled)
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `connection failure does not surface exception to caller`() {
        val fakeCoordinator = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(
                forceRefresh: Boolean,
                cacheOnly: Boolean,
                clearCacheBeforeRefresh: Boolean
            ): List<Server> = emptyList()

            override suspend fun syncSelectedCountryServersForRelocalization(
                forceRefresh: Boolean,
                cacheOnly: Boolean
            ) = Unit
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(300, TimeUnit.MILLISECONDS)
            .build()

        // Point to a port that will refuse connections
        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlProvider = { "http://127.0.0.1:19999/api/v1/servers/events" }
        )

        // The client should start without throwing, handle the connection failure
        // silently, and keep trying in the background with backoff
        try {
            client.start()
            // Wait briefly; no exception should surface to the caller
            Thread.sleep(600)
        } finally {
            client.stop()
        }
        // Reaching here without an uncaught exception means the test passes
    }

    // ── Constant sanity checks ───────────────────────────────────────────────

    @Test
    fun `EVENT_SERVERS_CHANGED constant is correct`() {
        assertEquals("servers-changed", SseServerEventsClient.EVENT_SERVERS_CHANGED)
    }

    @Test
    fun `INITIAL_BACKOFF_MS is 5 seconds`() {
        assertEquals(5_000L, SseServerEventsClient.INITIAL_BACKOFF_MS)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildClient(): SseServerEventsClient {
        val fakeCoordinator = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(
                forceRefresh: Boolean,
                cacheOnly: Boolean,
                clearCacheBeforeRefresh: Boolean
            ): List<Server> = emptyList()

            override suspend fun syncSelectedCountryServersForRelocalization(
                forceRefresh: Boolean,
                cacheOnly: Boolean
            ) = Unit
        }

        return SseServerEventsClient(
            okHttpClient = OkHttpClient(),
            syncCoordinator = fakeCoordinator,
            sseUrlProvider = { "http://localhost/sse" }
        )
    }
}
