package com.yahorzabotsin.openvpnclientgate.core.servers.probe

import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import java.util.concurrent.TimeUnit

/**
 * Injectable abstraction over [WorkManager.enqueueUniqueWork] for one-time work.
 * Decouples [WorkManagerProbeRequestQueue] from a real [WorkManager] for unit testing.
 */
internal interface OneTimeWorkEnqueuer {
    fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        work: OneTimeWorkRequest
    )
}

internal class WorkManagerOneTimeWorkEnqueuer(
    private val workManager: WorkManager
) : OneTimeWorkEnqueuer {
    override fun enqueueUniqueWork(
        uniqueWorkName: String,
        existingWorkPolicy: ExistingWorkPolicy,
        work: OneTimeWorkRequest
    ) {
        workManager.enqueueUniqueWork(uniqueWorkName, existingWorkPolicy, work)
    }
}

/**
 * WorkManager-backed implementation of [ProbeRequestQueue].
 *
 * Uses [ExistingWorkPolicy.KEEP] to deduplicate concurrent requests for the
 * same server: if a pending/running work unit with the same unique name already
 * exists, the new enqueue call is a no-op.
 *
 * Exponential backoff starts at [INITIAL_BACKOFF_SECONDS] seconds; the worker
 * itself maps HTTP 429 to [Result.retry()], so the system respects server-side
 * rate limiting automatically.
 *
 * The [WorkManager] constructor is the public API; the internal [OneTimeWorkEnqueuer]
 * constructor is used only in tests.
 */
class WorkManagerProbeRequestQueue internal constructor(
    private val workEnqueuer: OneTimeWorkEnqueuer
) : ProbeRequestQueue {

    constructor(workManager: WorkManager) : this(WorkManagerOneTimeWorkEnqueuer(workManager))

    override fun enqueue(serverId: Int) {
        val uniqueName = uniqueWorkName(serverId)
        val inputData = workDataOf(ProbeRequestWorker.KEY_SERVER_ID to serverId)

        val request = OneTimeWorkRequestBuilder<ProbeRequestWorker>()
            .setInputData(inputData)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                INITIAL_BACKOFF_SECONDS,
                TimeUnit.SECONDS
            )
            .addTag(WORK_TAG)
            .build()

        workEnqueuer.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.KEEP, request)

        AppLog.d(TAG, "Probe enqueued: serverId=$serverId, uniqueName=$uniqueName")
    }

    companion object {
        private val TAG = LogTags.APP + ':' + "WorkManagerProbeRequestQueue"
        const val WORK_TAG = "server-probe"
        const val INITIAL_BACKOFF_SECONDS = 30L

        fun uniqueWorkName(serverId: Int): String = "probe-server-$serverId"
    }
}
