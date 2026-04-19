package com.yahorzabotsin.openvpnclientgate.core.servers.refresh

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRefreshSchedulerTest {

    @Test
    fun `schedule enqueues unique periodic work with keep policy`() {
        val enqueuer = FakePeriodicWorkEnqueuer()
        val scheduler = DefaultServerRefreshScheduler(enqueuer)

        scheduler.schedulePeriodicRefresh()

        assertEquals(DefaultServerRefreshScheduler.UNIQUE_WORK_NAME, enqueuer.uniqueWorkName)
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, enqueuer.policy)
    }

    @Test
    fun `schedule configures request with expected worker tag and network constraint`() {
        val enqueuer = FakePeriodicWorkEnqueuer()
        val scheduler = DefaultServerRefreshScheduler(enqueuer)

        scheduler.schedulePeriodicRefresh()

        val request = requireNotNull(enqueuer.request)
        assertTrue(request.tags.contains(DefaultServerRefreshScheduler.WORK_TAG))
        assertEquals(ServerRefreshWorker::class.java.name, request.workSpec.workerClassName)
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
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
}
