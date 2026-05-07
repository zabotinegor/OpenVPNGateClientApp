package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import kotlinx.coroutines.CancellationException

interface ServersV2SyncCoordinator {
    /** Fetches and caches the country list. */
    suspend fun syncCountries(
        context: Context,
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    ): List<CountryV2>
}

class DefaultServersV2SyncCoordinator(
    private val serversV2Repository: ServersV2Repository
) : ServersV2SyncCoordinator {

    private val tag = LogTags.APP + ":ServersV2SyncCoordinator"

    override suspend fun syncCountries(
        context: Context,
        forceRefresh: Boolean,
        cacheOnly: Boolean
    ): List<CountryV2> {
        AppLog.d(tag, "syncCountries(forceRefresh=$forceRefresh, cacheOnly=$cacheOnly)")
        return try {
            serversV2Repository.getCountries(
                context = context,
                forceRefresh = forceRefresh,
                cacheOnly = cacheOnly
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(tag, "syncCountries failed", e)
            throw e
        }
    }
}
