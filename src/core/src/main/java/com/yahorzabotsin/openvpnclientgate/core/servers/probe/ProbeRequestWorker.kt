package com.yahorzabotsin.openvpnclientgate.core.servers.probe

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext
import java.io.IOException

/**
 * WorkManager [CoroutineWorker] that issues POST /api/v2/servers/{id}/probe.
 *
 * HTTP response mapping:
 * - 202 Accepted     → [Result.success()]
 * - 429 Too Many Req → [Result.retry()]  (exponential backoff; respects server rate limit)
 * - 404 / 422        → [Result.failure()] (non-retryable; server rejected the request)
 * - Other HTTP error → [Result.retry()]   (treat unknown server errors as transient)
 * - Network error    → [Result.retry()]
 * - DI unavailable   → [Result.failure()] (permanent config error; retrying cannot help)
 */
class ProbeRequestWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val serverId = inputData.getInt(KEY_SERVER_ID, INVALID_SERVER_ID)
        if (serverId == INVALID_SERVER_ID) {
            AppLog.e(TAG, "ProbeRequestWorker started without a valid serverId — failing permanently")
            return Result.failure()
        }

        val probeApi = runCatching {
            GlobalContext.get().get<ProbeApi>()
        }.getOrElse { error ->
            AppLog.e(TAG, "Failed to resolve ProbeApi from Koin — permanent configuration error", error)
            return Result.failure()
        }

        return try {
            val response = probeApi.probe(serverId)
            val code = response.code()
            when {
                code == HTTP_ACCEPTED -> {
                    AppLog.i(TAG, "Probe succeeded: serverId=$serverId, status=$code")
                    Result.success()
                }
                code == HTTP_TOO_MANY_REQUESTS -> {
                    AppLog.w(TAG, "Probe rate-limited: serverId=$serverId, status=$code — scheduling retry")
                    Result.retry()
                }
                code == HTTP_NOT_FOUND || code == HTTP_UNPROCESSABLE_ENTITY -> {
                    AppLog.w(TAG, "Probe rejected permanently: serverId=$serverId, status=$code")
                    Result.failure()
                }
                else -> {
                    AppLog.w(TAG, "Probe received unexpected status: serverId=$serverId, status=$code — scheduling retry")
                    Result.retry()
                }
            }
        } catch (e: IOException) {
            AppLog.w(TAG, "Probe network error: serverId=$serverId — scheduling retry", e)
            Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Probe unexpected error: serverId=$serverId — scheduling retry", e)
            Result.retry()
        }
    }

    companion object {
        private val TAG = LogTags.APP + ':' + "ProbeRequestWorker"

        const val KEY_SERVER_ID = "probe_server_id"
        const val INVALID_SERVER_ID = -1

        private const val HTTP_ACCEPTED = 202
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_UNPROCESSABLE_ENTITY = 422
    }
}
