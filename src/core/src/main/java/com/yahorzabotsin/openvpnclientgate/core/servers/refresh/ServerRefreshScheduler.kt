package com.yahorzabotsin.openvpnclientgate.core.servers.refresh

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import java.util.concurrent.TimeUnit

interface ServerRefreshScheduler {
    fun schedulePeriodicRefresh()
}

internal interface ServerCacheTtlProvider {
    fun cacheTtlMs(): Long
}

internal class SettingsServerCacheTtlProvider(
    private val appContext: Context,
    private val settingsStore: UserSettingsStore = UserSettingsStore
) : ServerCacheTtlProvider {
    override fun cacheTtlMs(): Long = settingsStore.load(appContext).cacheTtlMs
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
    private val workEnqueuer: PeriodicWorkEnqueuer,
    private val cacheTtlProvider: ServerCacheTtlProvider
) : ServerRefreshScheduler {

    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ServerRefreshScheduler"

    override fun schedulePeriodicRefresh() {
        val rawTtlMs = cacheTtlProvider.cacheTtlMs()
        val rawIntervalMinutes = TimeUnit.MILLISECONDS.toMinutes(rawTtlMs)
        val intervalMinutes = rawIntervalMinutes
            .coerceAtLeast(MIN_PERIODIC_INTERVAL_MINUTES)
        val clamped = intervalMinutes != rawIntervalMinutes

        logInfo("Preparing periodic server refresh schedule. ttl_ms=$rawTtlMs, interval_min=$intervalMinutes, clamped_to_min=$clamped")

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<ServerRefreshWorker>(
            intervalMinutes,
            TimeUnit.MINUTES
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
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )

        logInfo(
            "Periodic server refresh scheduled. work=$UNIQUE_WORK_NAME, policy=${ExistingPeriodicWorkPolicy.UPDATE}, interval_min=$intervalMinutes, backoff_min=$REFRESH_BACKOFF_MINUTES"
        )
    }

    private fun logInfo(message: String) {
        runCatching { AppLog.i(tag, message) }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "server-list-periodic-refresh"
        const val WORK_TAG = "server-list-refresh"
        val MIN_PERIODIC_INTERVAL_MINUTES =
            TimeUnit.MILLISECONDS.toMinutes(PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)
        const val REFRESH_BACKOFF_MINUTES = 30L
    }
}
