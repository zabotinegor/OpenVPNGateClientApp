package com.yahorzabotsin.openvpnclientgate.core.servers.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryServerSync
import kotlinx.coroutines.CancellationException
import org.koin.core.context.GlobalContext

class ServerRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repository = runCatching {
            GlobalContext.get().get<ServerRepository>()
        }.getOrElse { error ->
            AppLog.w(TAG, "Periodic refresh failed to resolve dependencies", error)
            return Result.retry()
        }
        val selectedCountrySync = runCatching {
            GlobalContext.get().get<SelectedCountryServerSync>()
        }.getOrElse { error ->
            AppLog.w(TAG, "Selected country sync dependency is unavailable; refresh will continue without sync", error)
            null
        }

        val additionalRetryCount = inputData
            .getInt(KEY_ADDITIONAL_RETRY_COUNT, DEFAULT_ADDITIONAL_RETRY_COUNT)
            .coerceAtLeast(0)
        val attempts = additionalRetryCount + 1

        var lastError: Exception? = null
        repeat(attempts) { attemptIndex ->
            try {
                val freshServers = repository.getServers(
                    context = applicationContext,
                    forceRefresh = true,
                    cacheOnly = false
                )
                if (selectedCountrySync != null) {
                    runCatching {
                        selectedCountrySync.syncAfterRefresh(freshServers)
                    }.onFailure { syncError ->
                        if (syncError is CancellationException) {
                            throw syncError
                        }
                        AppLog.w(TAG, "Selected country sync failed after refresh", syncError)
                    }
                }
                AppLog.i(TAG, "Periodic server refresh completed")
                return Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attemptIndex < attempts - 1) {
                    AppLog.w(
                        TAG,
                        "Periodic server refresh attempt ${attemptIndex + 1}/$attempts failed, retrying",
                        e
                    )
                }
            }
        }

        if (lastError != null) {
            AppLog.w(TAG, "Periodic server refresh failed after attempts=$attempts", lastError)
        } else {
            AppLog.w(TAG, "Periodic server refresh failed after attempts=$attempts")
        }
        return Result.success()
    }

    companion object {
        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ServerRefreshWorker"
        const val KEY_ADDITIONAL_RETRY_COUNT = "server_refresh_additional_retry_count"
        const val DEFAULT_ADDITIONAL_RETRY_COUNT = 2

        fun retryInputData(additionalRetryCount: Int) =
            workDataOf(KEY_ADDITIONAL_RETRY_COUNT to additionalRetryCount)
    }
}
