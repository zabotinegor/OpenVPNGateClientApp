package com.yahorzabotsin.openvpnclientgate.core.servers.refresh

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
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

        return try {
            repository.getServers(
                context = applicationContext,
                forceRefresh = false,
                cacheOnly = false
            )
            AppLog.i(TAG, "Periodic server refresh completed")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Periodic server refresh failed", e)
            Result.retry()
        }
    }

    private companion object {
        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ServerRefreshWorker"
    }
}
