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

    /**
     * Fetches and saves the server list for the currently selected country.
     *
     * Reads the active selection from [SelectedCountryStore], resolves the corresponding country
     * in the v2 country list, fetches its servers, and calls
     * [SelectedCountryStore.saveSelectionPreservingIndex] to align the store without discarding
     * the current server index.
     *
     * Returns without saving when no country is selected, the selected country is not found in
     * the cached country list, or the server fetch returns an empty result. Each early-return
     * case logs a diagnostic message at debug or warning level.
     */
    suspend fun syncSelectedCountryServers(
        context: Context,
        forceRefresh: Boolean = false,
        cacheOnly: Boolean = false
    )

    /** Clears all v2 cache files and timestamps. */
    suspend fun clearCaches(context: Context)
}

class DefaultServersV2SyncCoordinator(
    private val serversV2Repository: ServersV2Repository
) : ServersV2SyncCoordinator {

    private val tag = LogTags.APP + ":ServersV2SyncCoordinator"

    override suspend fun clearCaches(context: Context) {
        serversV2Repository.clearCountriesCache(context)
        serversV2Repository.clearAllServersCaches(context)
    }

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

    override suspend fun syncSelectedCountryServers(
        context: Context,
        forceRefresh: Boolean,
        cacheOnly: Boolean
    ) {
        val rawSelectedCountry = SelectedCountryStore.getSelectedCountry(context)
        val selectedCountry = rawSelectedCountry?.trim()
        if (selectedCountry.isNullOrBlank()) {
            AppLog.d(tag, "syncSelectedCountryServers: no selected country, skipping")
            return
        }

        val selectedCountryCode = SelectedCountryStore.currentServer(context)?.countryCode
            ?: SelectedCountryStore.getServers(context).firstOrNull()?.countryCode

        val countries = try {
            serversV2Repository.getCountries(
                context = context,
                forceRefresh = forceRefresh,
                cacheOnly = cacheOnly
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(tag, "syncSelectedCountryServers: failed to get country list", e)
            return
        }

        val countryV2 = selectedCountryCode?.let { code ->
            countries.firstOrNull { it.code.equals(code, ignoreCase = true) }
        } ?: countries.firstOrNull { it.name.equals(selectedCountry, ignoreCase = true) }

        if (countryV2 == null) {
            AppLog.w(
                tag,
                "syncSelectedCountryServers: country '$selectedCountry' (code=${selectedCountryCode ?: "<none>"}) not in country list, skipping"
            )
            return
        }

        val v2Servers = try {
            serversV2Repository.getServersForCountry(
                context = context,
                countryCode = countryV2.code,
                serverCount = countryV2.serverCount,
                forceRefresh = forceRefresh,
                cacheOnly = cacheOnly
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(tag, "syncSelectedCountryServers: failed to get servers for '${countryV2.code}'", e)
            return
        }

        if (v2Servers.isEmpty()) {
            AppLog.w(tag, "syncSelectedCountryServers: no servers returned for '${countryV2.code}', skipping")
            return
        }

        val legacyServers = v2Servers.map { it.toLegacyServer() }
        SelectedCountryStore.saveSelectionPreservingIndex(context, rawSelectedCountry!!, legacyServers)
        AppLog.i(tag, "syncSelectedCountryServers: synced country=$selectedCountry servers=${legacyServers.size}")
    }
}
