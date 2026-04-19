package com.yahorzabotsin.openvpnclientgate.core.servers.refresh

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

interface ServerRefreshScheduler {
    fun schedulePeriodicRefresh()
}

internal interface PeriodicWorkEnqueuer {
    fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest
    )
}

internal class WorkManagerPeriodicWorkEnqueuer(
    private val workManager: WorkManager
) : PeriodicWorkEnqueuer {
    override fun enqueueUniquePeriodicWork(
        uniqueWorkName: String,
        policy: ExistingPeriodicWorkPolicy,
        request: PeriodicWorkRequest
    ) {
        workManager.enqueueUniquePeriodicWork(uniqueWorkName, policy, request)
    }
}

internal class DefaultServerRefreshScheduler(
    private val workEnqueuer: PeriodicWorkEnqueuer
) : ServerRefreshScheduler {

    override fun schedulePeriodicRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ServerRefreshWorker>(
            REFRESH_REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                REFRESH_BACKOFF_MINUTES,
                TimeUnit.MINUTES
            )
            .addTag(WORK_TAG)
            .build()

        workEnqueuer.enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "server-list-periodic-refresh"
        const val WORK_TAG = "server-list-refresh"
        const val REFRESH_REPEAT_INTERVAL_HOURS = 6L
        const val REFRESH_BACKOFF_MINUTES = 30L
    }
}
