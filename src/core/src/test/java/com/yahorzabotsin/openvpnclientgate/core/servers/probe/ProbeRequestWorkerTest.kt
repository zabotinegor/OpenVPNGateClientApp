package com.yahorzabotsin.openvpnclientgate.core.servers.probe

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response as RetrofitResponse

/**
 * Unit tests for [ProbeRequestWorker] and [WorkManagerProbeRequestQueue] covering SUB-02:
 *
 * - AC-3: HTTP 202 → Result.success()
 * - AC-4: HTTP 429 → Result.retry()
 * - AC-5: HTTP 404 → Result.failure(), HTTP 422 → Result.failure()
 * - AC-6: WorkManager KEEP policy prevents duplicate in-flight requests for same serverId
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ProbeRequestWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        runCatching { stopKoin() }
    }

    // AC-3 — HTTP 202 Accepted → worker returns success
    @Test
    fun `doWork returns success on HTTP 202`() = runBlocking {
        val api = FakeProbeApi(statusCode = 202)
        startKoinWithApi(api)

        val worker = buildWorker(serverId = 1)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, api.callCount)
    }

    // AC-4 — HTTP 429 Too Many Requests → worker returns retry
    @Test
    fun `doWork returns retry on HTTP 429`() = runBlocking {
        val api = FakeProbeApi(statusCode = 429)
        startKoinWithApi(api)

        val worker = buildWorker(serverId = 2)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
        assertEquals(1, api.callCount)
    }

    // AC-5a — HTTP 404 Not Found → worker returns failure (no retry)
    @Test
    fun `doWork returns failure on HTTP 404`() = runBlocking {
        val api = FakeProbeApi(statusCode = 404)
        startKoinWithApi(api)

        val worker = buildWorker(serverId = 3)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(1, api.callCount)
    }

    // AC-5b — HTTP 422 Unprocessable Entity → worker returns failure (no retry)
    @Test
    fun `doWork returns failure on HTTP 422`() = runBlocking {
        val api = FakeProbeApi(statusCode = 422)
        startKoinWithApi(api)

        val worker = buildWorker(serverId = 4)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(1, api.callCount)
    }

    // Other HTTP errors (500) → worker returns retry
    @Test
    fun `doWork returns retry on HTTP 500`() = runBlocking {
        val api = FakeProbeApi(statusCode = 500)
        startKoinWithApi(api)

        val worker = buildWorker(serverId = 5)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    // Network error → worker returns retry
    @Test
    fun `doWork returns retry on network IOException`() = runBlocking {
        val api = FakeProbeApi(throwError = java.io.IOException("network down"))
        startKoinWithApi(api)

        val worker = buildWorker(serverId = 6)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    // DI unavailable → worker returns retry
    @Test
    fun `doWork returns retry when Koin is not started`() = runBlocking {
        stopKoin()
        val worker = buildWorker(serverId = 7)
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    // Missing serverId in input data → permanent failure
    @Test
    fun `doWork returns failure when serverId is missing from input`() = runBlocking {
        val api = FakeProbeApi(statusCode = 202)
        startKoinWithApi(api)

        val worker = TestListenableWorkerBuilder<ProbeRequestWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(0, api.callCount)
    }

    // AC-6 — KEEP policy: second enqueue for same serverId uses same unique name + KEEP policy
    @Test
    fun `WorkManagerProbeRequestQueue uses KEEP policy for same serverId`() {
        val enqueuer = FakeOneTimeWorkEnqueuer()
        val queue = WorkManagerProbeRequestQueue(enqueuer)

        val serverId = 42
        queue.enqueue(serverId)
        queue.enqueue(serverId)

        // Both calls should use the same unique name
        val names = enqueuer.calls.map { it.uniqueWorkName }
        assertEquals(2, names.size)
        assertEquals(WorkManagerProbeRequestQueue.uniqueWorkName(serverId), names[0])
        assertEquals(WorkManagerProbeRequestQueue.uniqueWorkName(serverId), names[1])

        // All calls must use KEEP policy
        enqueuer.calls.forEach { call ->
            assertEquals(ExistingWorkPolicy.KEEP, call.existingWorkPolicy)
        }
    }

    // AC-6 — Different server IDs produce different unique work names
    @Test
    fun `WorkManagerProbeRequestQueue uses different unique names for different serverIds`() {
        val enqueuer = FakeOneTimeWorkEnqueuer()
        val queue = WorkManagerProbeRequestQueue(enqueuer)

        queue.enqueue(10)
        queue.enqueue(20)

        val names = enqueuer.calls.map { it.uniqueWorkName }
        assertEquals(WorkManagerProbeRequestQueue.uniqueWorkName(10), names[0])
        assertEquals(WorkManagerProbeRequestQueue.uniqueWorkName(20), names[1])
    }

    // AC-6 — Verify correct unique name format
    @Test
    fun `WorkManagerProbeRequestQueue unique name format is stable`() {
        assertEquals("probe-server-42", WorkManagerProbeRequestQueue.uniqueWorkName(42))
        assertEquals("probe-server-0", WorkManagerProbeRequestQueue.uniqueWorkName(0))
    }

    // AC-6 — Verify worker class and input data propagated correctly
    @Test
    fun `WorkManagerProbeRequestQueue sets correct worker class and input data`() {
        val enqueuer = FakeOneTimeWorkEnqueuer()
        val queue = WorkManagerProbeRequestQueue(enqueuer)

        queue.enqueue(99)

        val call = enqueuer.calls.single()
        assertNotNull(call.work)
        assertEquals(
            ProbeRequestWorker::class.java.name,
            call.work.workSpec.workerClassName
        )
        assertEquals(
            99,
            call.work.workSpec.input.getInt(ProbeRequestWorker.KEY_SERVER_ID, -1)
        )
        assertEquals(
            WorkManagerProbeRequestQueue.WORK_TAG,
            call.work.tags.first { it == WorkManagerProbeRequestQueue.WORK_TAG }
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun buildWorker(serverId: Int): ProbeRequestWorker {
        return TestListenableWorkerBuilder<ProbeRequestWorker>(context)
            .setInputData(
                androidx.work.workDataOf(ProbeRequestWorker.KEY_SERVER_ID to serverId)
            )
            .build()
    }

    private fun startKoinWithApi(api: ProbeApi) {
        runCatching { stopKoin() }
        startKoin {
            modules(module {
                single<ProbeApi> { api }
            })
        }
    }

    // ── fakes ─────────────────────────────────────────────────────────────────

    private class FakeProbeApi(
        private val statusCode: Int = 202,
        private val throwError: Exception? = null
    ) : ProbeApi {

        var callCount: Int = 0
            private set

        override suspend fun probe(serverId: Int): RetrofitResponse<Unit> {
            callCount++
            throwError?.let { throw it }

            val rawResponse = okhttp3.Response.Builder()
                .request(
                    Request.Builder()
                        .url("https://example.com/api/v2/servers/$serverId/probe")
                        .build()
                )
                .protocol(Protocol.HTTP_1_1)
                .code(statusCode)
                .message(httpMessageFor(statusCode))
                .body("".toResponseBody("application/json".toMediaType()))
                .build()

            return if (statusCode in 200..299) {
                RetrofitResponse.success(null, rawResponse)
            } else {
                RetrofitResponse.error(
                    "".toResponseBody("application/json".toMediaType()),
                    rawResponse
                )
            }
        }

        private fun httpMessageFor(code: Int): String = when (code) {
            202 -> "Accepted"
            404 -> "Not Found"
            422 -> "Unprocessable Entity"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "Unknown"
        }
    }

    private data class EnqueueCall(
        val uniqueWorkName: String,
        val existingWorkPolicy: ExistingWorkPolicy,
        val work: OneTimeWorkRequest
    )

    private class FakeOneTimeWorkEnqueuer : OneTimeWorkEnqueuer {
        val calls = mutableListOf<EnqueueCall>()

        override fun enqueueUniqueWork(
            uniqueWorkName: String,
            existingWorkPolicy: ExistingWorkPolicy,
            work: OneTimeWorkRequest
        ) {
            calls += EnqueueCall(uniqueWorkName, existingWorkPolicy, work)
        }
    }
}
