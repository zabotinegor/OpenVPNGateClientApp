package com.yahorzabotsin.openvpnclientgate.core.servers.refresh

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ServerRefreshSchedulerTest {

    @Test
    fun `schedule enqueues unique periodic work with update policy`() {
        val enqueuer = FakePeriodicWorkEnqueuer()
        val scheduler = DefaultServerRefreshScheduler(enqueuer, FakeServerCacheTtlProvider(ttlMs = TimeUnit.MINUTES.toMillis(20)))

        scheduler.schedulePeriodicRefresh()

        assertEquals(DefaultServerRefreshScheduler.UNIQUE_WORK_NAME, enqueuer.uniqueWorkName)
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, enqueuer.policy)
    }

    @Test
    fun `schedule configures request with ttl-based interval worker tag and network constraint`() {
        val enqueuer = FakePeriodicWorkEnqueuer()
        val scheduler = DefaultServerRefreshScheduler(enqueuer, FakeServerCacheTtlProvider(ttlMs = TimeUnit.MINUTES.toMillis(20)))

        scheduler.schedulePeriodicRefresh()

        val request = requireNotNull(enqueuer.request)
        assertTrue(request.tags.contains(DefaultServerRefreshScheduler.WORK_TAG))
        assertEquals(ServerRefreshWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(TimeUnit.MINUTES.toMillis(20), request.workSpec.intervalDuration)
    }

    @Test
    fun `schedule clamps interval to workmanager minimum`() {
        val enqueuer = FakePeriodicWorkEnqueuer()
        val scheduler = DefaultServerRefreshScheduler(enqueuer, FakeServerCacheTtlProvider(ttlMs = TimeUnit.MINUTES.toMillis(1)))

        scheduler.schedulePeriodicRefresh()

        val request = requireNotNull(enqueuer.request)
        assertEquals(
            TimeUnit.MINUTES.toMillis(DefaultServerRefreshScheduler.MIN_PERIODIC_INTERVAL_MINUTES),
            request.workSpec.intervalDuration
        )
    }

    private class FakePeriodicWorkEnqueuer : PeriodicWorkEnqueuer {
        var uniqueWorkName: String? = null
        var policy: ExistingPeriodicWorkPolicy? = null
        var request: PeriodicWorkRequest? = null

        override fun enqueueUniquePeriodicWork(
            uniqueWorkName: String,
            policy: ExistingPeriodicWorkPolicy,
            request: PeriodicWorkRequest
        ) {
            this.uniqueWorkName = uniqueWorkName
            this.policy = policy
            this.request = request
        }
    }

    private class FakeServerCacheTtlProvider(
        private val ttlMs: Long
    ) : ServerCacheTtlProvider {
        override fun cacheTtlMs(): Long = ttlMs
    }
}
