package com.yahorzabotsin.openvpnclientgate.core.servers.sse

import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionSyncCoordinator
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun `stop called from a different thread than start correctly resets state`() {
        val client = buildClient()

        val startThread = Thread { client.start() }
        startThread.start()
        startThread.join()

        // Simulate accumulated reconnect attempts
        client.reconnectAttempt.set(5)

        val stopThread = Thread { client.stop() }
        stopThread.start()
        stopThread.join()

        // If @Volatile is absent, the stop thread might read a stale null for clientScope/
        // reconnectJob and silently skip cancellation. The reset of reconnectAttempt to 0
        // in stop() is the observable side-effect we can verify.
        assertEquals(0, client.reconnectAttempt.get())
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

    @Test
    fun `stop resets URL index and failure count to zero`() {
        val client = buildClient()
        client.start()
        client.currentUrlIndex.set(1)
        client.failuresOnCurrentUrl.set(2)

        client.stop()

        assertEquals(0, client.currentUrlIndex.get())
        assertEquals(0, client.failuresOnCurrentUrl.get())
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
            sseUrlsProvider = { listOf(url) }
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
    fun `unrecognized event type does not trigger sync beyond onOpen`() {
        val server = MockWebServer()
        val syncCallCount = java.util.concurrent.atomic.AtomicInteger(0)
        val firstSyncLatch = CountDownLatch(1)

        val fakeCoordinator = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(
                forceRefresh: Boolean,
                cacheOnly: Boolean,
                clearCacheBeforeRefresh: Boolean
            ): List<Server> {
                syncCallCount.incrementAndGet()
                firstSyncLatch.countDown()
                return emptyList()
            }

            override suspend fun syncSelectedCountryServersForRelocalization(
                forceRefresh: Boolean,
                cacheOnly: Boolean
            ) = Unit
        }

        val sseBody = buildString {
            append("event: unknown-event\n")
            append("data: test\n")
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
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlsProvider = { listOf(url) }
        )

        try {
            client.start()
            // Wait for the onOpen sync (always fires once on connect), then check no second call
            val openSyncFired = firstSyncLatch.await(5, TimeUnit.SECONDS)
            assertTrue("onOpen sync must fire on connection open", openSyncFired)
            Thread.sleep(500) // let any spurious event-triggered sync surface
            assertEquals(
                "unknown event must not trigger a second sync beyond the onOpen sync",
                1, syncCallCount.get()
            )
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
            sseUrlsProvider = { listOf("http://127.0.0.1:19999/api/v1/servers/events") }
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

    // ── Fix validations ──────────────────────────────────────────────────────

    @Test
    fun `onOpen triggers sync coordinator on connection open without waiting for events`() {
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

        // HTTP 200 with a keepalive comment — no servers-changed event
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(": keep\n\n")
        )

        server.start()
        val url = server.url("/api/v1/servers/events").toString()
        val okHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlsProvider = { listOf(url) }
        )

        try {
            client.start()
            val received = syncLatch.await(10, TimeUnit.SECONDS)
            assertTrue("sync must be called from onOpen without waiting for a servers-changed event", received)
            assertTrue(syncCalled)
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `servers-changed event on short-lived connection does not reset backoff counter`() {
        val server = MockWebServer()

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

        // servers-changed event followed by immediate close (elapsed << STABLE_CONNECTION_RESET_DELAY_MS)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: servers-changed\ndata: {}\n\n")
        )
        // Second response for the reconnect attempt
        server.enqueue(MockResponse().setResponseCode(503))

        server.start()
        val url = server.url("/api/v1/servers/events").toString()
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlsProvider = { listOf(url) }
        )

        try {
            client.start()
            val firstRequest = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("First SSE connection must be established", firstRequest)
            // If the backoff counter were incorrectly reset by onEvent, the client would
            // reconnect immediately (attempt=0 → no delay). With backoff intact, attempt=1
            // gives INITIAL_BACKOFF_MS=5s, so no second request arrives within 2 seconds.
            val secondRequest = server.takeRequest(2, TimeUnit.SECONDS)
            assertNull(
                "Second reconnect must not be immediate — backoff counter must not be reset by servers-changed on a short-lived connection",
                secondRequest
            )
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `CancellationException from sync is not swallowed as a warning`() {
        val server = MockWebServer()
        var warningLoggedForCancellation = false

        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == android.util.Log.WARN && t is CancellationException) {
                    warningLoggedForCancellation = true
                }
            }
        })

        val syncLatch = CountDownLatch(1)
        val fakeCoordinator = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(
                forceRefresh: Boolean,
                cacheOnly: Boolean,
                clearCacheBeforeRefresh: Boolean
            ): List<Server> {
                syncLatch.countDown()
                throw CancellationException("test cancellation — must not be swallowed")
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
        val okHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlsProvider = { listOf(url) }
        )

        try {
            client.start()
            syncLatch.await(10, TimeUnit.SECONDS)
            Thread.sleep(500) // let any spurious warning log flush
            assertFalse(
                "CancellationException must be rethrown, not logged as a warning",
                warningLoggedForCancellation
            )
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `unexpected exception from connectOnce is caught and loop continues`() {
        var unexpectedErrorLogged = false

        Timber.plant(object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                if (priority == android.util.Log.WARN &&
                    message.contains("SSE connection attempt failed with an unexpected error")
                ) {
                    unexpectedErrorLogged = true
                }
            }
        })

        // An invalid URL causes Request.Builder.url() to throw IllegalArgumentException
        // synchronously inside connectOnce(), simulating an unexpected runtime exception.
        val client = SseServerEventsClient(
            okHttpClient = OkHttpClient(),
            syncCoordinator = buildClient().let {
                object : ServerSelectionSyncCoordinator {
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
            },
            sseUrlsProvider = { listOf("this is not a valid url") }
        )

        try {
            client.start()
            Thread.sleep(400) // enough time for one attempt to throw and be caught
            assertTrue(
                "Expected warning log when connectOnce() throws unexpectedly",
                unexpectedErrorLogged
            )
        } finally {
            client.stop()
        }
    }

    @Test
    fun `stable connection triggers quick reconnect — counter was reset after close`() {
        val server = MockWebServer()

        // First response: a small body throttled to ~120 ms total, simulating a healthy
        // SSE server that ran for longer than stableConnectionResetDelayMs (50 ms).
        // On close, maybeResetBackoff() sees elapsed ≥ 50 ms and sets counter to 0.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(": k\n")           // 4 bytes
                .throttleBody(1, 30, TimeUnit.MILLISECONDS) // 1 byte/30 ms → ~120 ms
        )
        // Second response for the immediate reconnect (any response is fine here).
        server.enqueue(MockResponse().setResponseCode(503))

        server.start()
        val url = server.url("/api/v1/servers/events").toString()
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

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

        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlsProvider = { listOf(url) },
            stableConnectionResetDelayMs = 50L // short threshold for testing
        )

        try {
            client.start()
            // Wait for the first request to be received by MockWebServer
            val firstRequest = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull("First connection should have been made", firstRequest)

            // After the throttled body completes (~120 ms), onClosed fires: elapsed ≥ 50 ms
            // → counter reset to 0 → reconnect loop issues attempt=0 with NO backoff delay.
            // The second request should therefore arrive well within 2 s. If the counter was
            // NOT reset (stayed at 1), the backoff would be 5 000 ms and the assertion fails.
            val secondRequest = server.takeRequest(2, TimeUnit.SECONDS)
            assertNotNull(
                "Second reconnect must be immediate (< 2 s) after a stable connection, " +
                    "confirming backoff counter was reset to 0 on close",
                secondRequest
            )
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `reconnect counter is not reset on open without an event — short-lived 200 still backs off`() {
        val server = MockWebServer()

        // Respond with HTTP 200 but close the stream immediately (no events)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("") // empty body → onClosed fires immediately after onOpen
        )

        server.start()
        val url = server.url("/api/v1/servers/events").toString()
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()

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

        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlsProvider = { listOf(url) }
        )

        try {
            client.start()
            // After the first attempt completes (HTTP 200 + close), the counter must not
            // be zero — resetting it in onOpen with no event would allow a hot loop.
            Thread.sleep(600)
            assertTrue(
                "reconnectAttempt must be > 0 after a short-lived 200 so backoff is applied",
                client.reconnectAttempt.get() > 0
            )
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    // ── Debounce collapse (AC-2 / AC-5) ─────────────────────────────────────

    @Test
    fun `burst of rapid servers-changed events collapses to a single debounced sync call`() {
        val server = MockWebServer()
        val syncCount = java.util.concurrent.atomic.AtomicInteger(0)

        val fakeCoordinator = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(
                forceRefresh: Boolean,
                cacheOnly: Boolean,
                clearCacheBeforeRefresh: Boolean
            ): List<Server> {
                syncCount.incrementAndGet()
                return emptyList()
            }

            override suspend fun syncSelectedCountryServersForRelocalization(
                forceRefresh: Boolean,
                cacheOnly: Boolean
            ) = Unit
        }

        // 20 rapid servers-changed events in a single HTTP response body
        val eventsBody = buildString {
            repeat(20) { append("event: servers-changed\ndata: {}\n\n") }
        }
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(eventsBody)
        )

        server.start()
        val url = server.url("/api/v1/servers/events").toString()
        val okHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlsProvider = { listOf(url) },
            debounceMs = 100L
        )

        try {
            client.start()
            // Comfortably after both onOpen sync (immediate) and debounce window (100 ms).
            // Reconnect backoff is 5 000 ms, so no second onOpen fires within this window.
            Thread.sleep(1_000)
            assertEquals(
                "20 rapid events must collapse to 1 debounced sync; plus 1 onOpen = 2 total",
                2,
                syncCount.get()
            )
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    @Test
    fun `single servers-changed event still triggers sync within debounce window plus network RTT`() {
        val server = MockWebServer()
        val syncCount = java.util.concurrent.atomic.AtomicInteger(0)
        val twoSyncsLatch = CountDownLatch(2)

        val fakeCoordinator = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(
                forceRefresh: Boolean,
                cacheOnly: Boolean,
                clearCacheBeforeRefresh: Boolean
            ): List<Server> {
                syncCount.incrementAndGet()
                twoSyncsLatch.countDown()
                return emptyList()
            }

            override suspend fun syncSelectedCountryServersForRelocalization(
                forceRefresh: Boolean,
                cacheOnly: Boolean
            ) = Unit
        }

        val sseBody = "event: servers-changed\ndata: {}\n\n"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        server.start()
        val url = server.url("/api/v1/servers/events").toString()
        val okHttpClient = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlsProvider = { listOf(url) },
            debounceMs = 100L
        )

        try {
            client.start()
            // Expect 2 syncs: 1 from onOpen (direct) + 1 from the single debounced event.
            val bothFired = twoSyncsLatch.await(5, TimeUnit.SECONDS)
            assertTrue(
                "Isolated servers-changed event must trigger exactly 1 debounced sync (plus onOpen); " +
                    "only ${syncCount.get()} sync(s) received within timeout",
                bothFired
            )
        } finally {
            client.stop()
            server.shutdown()
        }
    }

    // ── Fallback URL rotation (AC-1 / AC-5) ─────────────────────────────────

    @Test
    fun `primary URL failure switches to fallback after threshold`() {
        val primaryServer = MockWebServer()
        val fallbackServer = MockWebServer()
        val syncLatch = CountDownLatch(1)

        val fakeCoordinator = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(
                forceRefresh: Boolean,
                cacheOnly: Boolean,
                clearCacheBeforeRefresh: Boolean
            ): List<Server> {
                syncLatch.countDown()
                return emptyList()
            }

            override suspend fun syncSelectedCountryServersForRelocalization(
                forceRefresh: Boolean,
                cacheOnly: Boolean
            ) = Unit
        }

        // Primary always fails immediately (503 triggers onFailure)
        primaryServer.enqueue(MockResponse().setResponseCode(503))

        // Fallback responds with a valid SSE stream → onOpen fires → sync is called
        fallbackServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(": keep\n\n")
        )

        primaryServer.start()
        fallbackServer.start()

        val primaryUrl = primaryServer.url("/api/v1/servers/events").toString()
        val fallbackUrl = fallbackServer.url("/api/v1/servers/events").toString()

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinator,
            sseUrlsProvider = { listOf(primaryUrl, fallbackUrl) },
            urlFailureThreshold = 1 // switch after the very first failure for a fast test
        )

        try {
            client.start()
            // After 1 primary failure the client advances to the fallback URL.
            // reconnectAttempt is NOT reset, so the loop backs off before the fallback attempt.
            // The 10-second timeout accommodates the initial 5-second backoff + network latency.
            val fallbackConnected = syncLatch.await(12, TimeUnit.SECONDS)
            assertTrue(
                "sync must be called via fallback URL after primary URL fails",
                fallbackConnected
            )
            // Confirm the fallback server actually received the request
            val fallbackRequest = fallbackServer.takeRequest(1, TimeUnit.SECONDS)
            assertNotNull("Fallback server must have received an SSE request", fallbackRequest)
        } finally {
            client.stop()
            primaryServer.shutdown()
            fallbackServer.shutdown()
        }
    }

    @Test
    fun `onOpen does not reset failure count — only a stable connection does, on close`() {
        // Regression: onOpen must NOT reset failuresOnCurrentUrl unconditionally. A URL that
        // accepts the connection (HTTP 200) but drops it immediately every time would otherwise
        // never accumulate failures past 0 and the client would never rotate to a fallback.
        // Use a single URL with urlFailureThreshold=2: the first attempt fails (counter → 1),
        // the second attempt succeeds and stays open long enough to be "stable"
        // (stableConnectionResetDelayMs=50 ms), then closes — only then must the counter reset.
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/event-stream")
                .setBody(": k\n")           // 4 bytes
                .throttleBody(1, 30, TimeUnit.MILLISECONDS) // 1 byte/30 ms → ~120 ms, > 50 ms stable window
        )
        // Third response for the reconnect after the stable close.
        server.enqueue(MockResponse().setResponseCode(503))
        server.start()

        val url = server.url("/api/v1/servers/events").toString()

        val openLatch = CountDownLatch(1)
        val fakeCoordinatorWithLatch = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(
                forceRefresh: Boolean,
                cacheOnly: Boolean,
                clearCacheBeforeRefresh: Boolean
            ): List<Server> {
                openLatch.countDown()
                return emptyList()
            }

            override suspend fun syncSelectedCountryServersForRelocalization(
                forceRefresh: Boolean,
                cacheOnly: Boolean
            ) = Unit
        }

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val client = SseServerEventsClient(
            okHttpClient = okHttpClient,
            syncCoordinator = fakeCoordinatorWithLatch,
            sseUrlsProvider = { listOf(url) },
            urlFailureThreshold = 2,
            stableConnectionResetDelayMs = 50L
        )

        try {
            client.start()
            val firstRequest = server.takeRequest(3, TimeUnit.SECONDS)
            assertNotNull("First request (503) should have been made", firstRequest)
            // The reconnect after a failure applies backoff (initial delay ~5 s), so allow
            // enough time for the second request to arrive.
            val secondRequest = server.takeRequest(10, TimeUnit.SECONDS)
            assertNotNull("Second request (200, throttled body) should have been made", secondRequest)

            // Wait for onOpen on the second attempt (triggers sync → latch)
            val opened = openLatch.await(15, TimeUnit.SECONDS)
            assertTrue("Connection must open on second attempt after first 503", opened)
            assertEquals(
                "failuresOnCurrentUrl must NOT be reset immediately by onOpen",
                1, client.failuresOnCurrentUrl.get()
            )

            // Wait for the throttled body to finish (~120 ms > 50 ms stable window), the
            // connection to close (triggering maybeResetBackoff()), and the immediate reconnect
            // (counter was reset, so no backoff delay) to reach the server as a third request.
            val thirdRequest = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("Third request (immediate reconnect after stable close) must arrive", thirdRequest)
            assertEquals(
                "failuresOnCurrentUrl must be reset to 0 only after a stable connection closes",
                0, client.failuresOnCurrentUrl.get()
            )
        } finally {
            client.stop()
            server.shutdown()
        }
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

    @Test
    fun `DEBOUNCE_MS constant is 500 milliseconds`() {
        assertEquals(500L, SseServerEventsClient.DEBOUNCE_MS)
    }

    @Test
    fun `debounceMs default matches DEBOUNCE_MS constant`() {
        val client = buildClient()
        assertEquals(SseServerEventsClient.DEBOUNCE_MS, client.debounceMs)
    }

    @Test
    fun `reconnectAttempt is not reset on URL rotation so backoff accumulates during outage`() {
        // With threshold=1, after the primary fails the URL rotates to the fallback.
        // reconnectAttempt must NOT be reset so the loop backs off before the fallback attempt,
        // preventing a tight loop during a complete outage.
        val primaryServer = MockWebServer()
        primaryServer.enqueue(MockResponse().setResponseCode(503))
        primaryServer.start()

        val primaryUrl = primaryServer.url("/api/v1/servers/events").toString()
        val fallbackUrl = "http://192.0.2.1/sse" // unroutable, never reached in this test

        val fakeCoordinator = object : ServerSelectionSyncCoordinator {
            override suspend fun sync(forceRefresh: Boolean, cacheOnly: Boolean, clearCacheBeforeRefresh: Boolean) = emptyList<Server>()
            override suspend fun syncSelectedCountryServersForRelocalization(forceRefresh: Boolean, cacheOnly: Boolean) = Unit
        }

        val client = SseServerEventsClient(
            okHttpClient = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build(),
            syncCoordinator = fakeCoordinator,
            sseUrlsProvider = { listOf(primaryUrl, fallbackUrl) },
            urlFailureThreshold = 1
        )

        try {
            client.start()
            // Wait for URL rotation: poll until currentUrlIndex advances from 0 to 1.
            val rotationDeadline = System.currentTimeMillis() + 3_000L
            while (client.currentUrlIndex.get() == 0 && System.currentTimeMillis() < rotationDeadline) {
                Thread.sleep(50)
            }
            assertEquals("URL must have rotated to index 1 after primary failure", 1, client.currentUrlIndex.get())
            // reconnectAttempt must still be > 0; it was incremented by the loop before the first
            // connectOnce() call and must not have been reset by the URL rotation.
            assertTrue(
                "reconnectAttempt must not be reset on URL rotation; got ${client.reconnectAttempt.get()}",
                client.reconnectAttempt.get() > 0
            )
        } finally {
            client.stop()
            primaryServer.shutdown()
        }
    }

    @Test
    fun `URL_FAILURE_THRESHOLD constant is 3`() {
        assertEquals(3, SseServerEventsClient.URL_FAILURE_THRESHOLD)
    }

    @Test
    fun `urlFailureThreshold default matches URL_FAILURE_THRESHOLD constant`() {
        val client = buildClient()
        assertEquals(SseServerEventsClient.URL_FAILURE_THRESHOLD, client.urlFailureThreshold)
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
            sseUrlsProvider = { listOf("http://localhost/sse") }
        )
    }
}
