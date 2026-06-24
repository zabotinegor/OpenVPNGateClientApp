package com.yahorzabotsin.openvpnclientgate.core.servers.sse

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.yahorzabotsin.openvpnclientgate.core.ApiConstants
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerSelectionSyncCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

/**
 * SSE client that connects to `GET /api/v1/servers/events` and triggers a server-list re-fetch
 * whenever a `servers-changed` event is received.
 *
 * The connection starts when the app enters the foreground and stops gracefully when it goes
 * to the background. On network errors or non-2xx responses the client silently backs off
 * with exponential backoff (initial 5 s, max 5 min). The existing WorkManager periodic
 * refresh ([ServerSelectionSyncCoordinator] via [ServerRefreshWorker]) is left untouched.
 */
class SseServerEventsClient(
    private val okHttpClient: OkHttpClient,
    private val syncCoordinator: ServerSelectionSyncCoordinator,
    sseUrlProvider: () -> String = { defaultSseUrl() },
    internal val stableConnectionResetDelayMs: Long = STABLE_CONNECTION_RESET_DELAY_MS
) : DefaultLifecycleObserver {

    private val tag = LogTags.APP + ":SseServerEventsClient"

    private val sseUrl: String by lazy { sseUrlProvider() }

    /** Coroutine scope for this client; lives while the client is started. */
    @Volatile
    private var clientScope: CoroutineScope? = null

    /** Current reconnect loop job. */
    @Volatile
    private var reconnectJob: Job? = null

    /** Active OkHttp EventSource, if any. */
    @Volatile
    private var activeEventSource: EventSource? = null

    /** Whether the client is currently "running" (foreground). */
    private val running = AtomicBoolean(false)

    /** Reconnect attempt counter; reset on a successful open. */
    internal val reconnectAttempt = AtomicInteger(0)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onStart(owner: LifecycleOwner) {
        start()
    }

    override fun onStop(owner: LifecycleOwner) {
        stop()
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Starts the SSE connection loop. Idempotent. */
    fun start() {
        if (!running.compareAndSet(false, true)) {
            AppLog.d(tag, "start() called but already running")
            return
        }
        AppLog.i(tag, "SSE client starting; url=$sseUrl")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        clientScope = scope
        reconnectJob = scope.launch { runReconnectLoop() }
    }

    /** Stops the SSE connection and cancels the reconnect loop. Idempotent. */
    fun stop() {
        if (!running.compareAndSet(true, false)) {
            AppLog.d(tag, "stop() called but not running")
            return
        }
        AppLog.i(tag, "SSE client stopping")
        cancelActiveEventSource()
        reconnectJob?.cancel()
        reconnectJob = null
        clientScope?.cancel()
        clientScope = null
        reconnectAttempt.set(0)
    }

    // ── Internal ───────────────────────────────────────────────────────────────

    private suspend fun runReconnectLoop() {
        while (running.get()) {
            val attempt = reconnectAttempt.getAndIncrement()
            if (attempt > 0) {
                val delayMs = backoffDelayMs(attempt)
                AppLog.d(tag, "SSE reconnect in ${delayMs}ms (attempt=$attempt)")
                delay(delayMs)
                if (!running.get()) break
            }

            AppLog.d(tag, "SSE connecting (attempt=$attempt)")
            try {
                connectOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(tag, "SSE connection attempt failed with an unexpected error", e)
            }
            // connectOnce() suspends until the connection closes; loop continues for retry.
        }
        AppLog.d(tag, "SSE reconnect loop exited")
    }

    /**
     * Opens one EventSource connection and suspends until it closes (either cleanly or on error).
     * Uses a Job + coroutine to bridge the callback-based OkHttp SSE API.
     */
    private suspend fun connectOnce() {
        val connectionDone = Job()
        val openedAt = AtomicLong(-1L)

        // Resets the backoff counter if the connection was alive long enough to be
        // considered stable. Called from onClosed and onFailure — avoids a background
        // coroutine and does not require an activeEventSource identity check.
        fun maybeResetBackoff() {
            val t = openedAt.get()
            if (t >= 0L && System.nanoTime() - t >= TimeUnit.MILLISECONDS.toNanos(stableConnectionResetDelayMs)) {
                reconnectAttempt.set(0)
            }
        }

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                AppLog.i(tag, "SSE connection opened (HTTP ${response.code})")
                openedAt.set(System.nanoTime())
                clientScope?.launch {
                    try {
                        syncCoordinator.sync(forceRefresh = true, cacheOnly = false)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.w(tag, "Server sync on SSE reconnect failed", e)
                    }
                }
            }

            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String
            ) {
                val eventType = type ?: ""
                AppLog.d(tag, "SSE event received: type='$eventType' id='$id'")
                if (eventType == EVENT_SERVERS_CHANGED) {
                    handleServersChangedEvent()
                }
            }

            override fun onClosed(eventSource: EventSource) {
                AppLog.i(tag, "SSE connection closed")
                maybeResetBackoff()
                connectionDone.complete()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?
            ) {
                val code = response?.code ?: -1
                if (t != null) {
                    AppLog.d(tag, "SSE connection failure (HTTP $code): ${t.message}")
                } else {
                    AppLog.d(tag, "SSE connection failure (HTTP $code)")
                }
                maybeResetBackoff()
                connectionDone.complete()
            }
        }

        val request = Request.Builder()
            .url(sseUrl)
            .header("Accept", "text/event-stream")
            .build()

        // Use a read-timeout-free client for SSE long-polling
        val sseOkHttpClient = okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .build()

        val factory = EventSources.createFactory(sseOkHttpClient)
        val eventSource = factory.newEventSource(request, listener)
        activeEventSource = eventSource

        try {
            connectionDone.join()
        } finally {
            eventSource.cancel()
            activeEventSource = null
        }
    }

    private fun handleServersChangedEvent() {
        AppLog.i(tag, "servers-changed event received; triggering server re-fetch")
        // Launch on a new coroutine so we don't block the OkHttp callback thread
        clientScope?.launch {
            try {
                syncCoordinator.sync(forceRefresh = true, cacheOnly = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(tag, "Server re-fetch triggered by SSE event failed", e)
            }
        }
    }

    private fun cancelActiveEventSource() {
        activeEventSource?.cancel()
        activeEventSource = null
    }

    // ── Backoff ────────────────────────────────────────────────────────────────

    /**
     * Exponential backoff: 5 s * 2^(attempt-1), capped at [MAX_BACKOFF_MS].
     * Attempt 1 → 5 s, 2 → 10 s, 3 → 20 s … cap at 5 min.
     */
    internal fun backoffDelayMs(attempt: Int): Long {
        if (attempt <= 0) return INITIAL_BACKOFF_MS
        val raw = INITIAL_BACKOFF_MS * 2.0.pow((attempt - 1).toDouble())
        return min(raw.toLong(), MAX_BACKOFF_MS)
    }

    companion object {
        internal const val EVENT_SERVERS_CHANGED = "servers-changed"
        internal const val INITIAL_BACKOFF_MS = 5_000L
        internal const val MAX_BACKOFF_MS = 5 * 60 * 1_000L // 5 minutes
        internal const val STABLE_CONNECTION_RESET_DELAY_MS = 10_000L

        /**
         * Derives the SSE endpoint URL from the same build-property chain used for all other
         * v1/v2 server endpoints (PRIMARY_SERVERS_URL → fallback).
         */
        fun defaultSseUrl(): String =
            com.yahorzabotsin.openvpnclientgate.core.PrimaryDomainRoutes.sseServersEventsUrl(
                ApiConstants.PRIMARY_SERVERS_URL
            ) ?: "https://openvpnclientgate.local/api/v1/servers/events"
    }
}
