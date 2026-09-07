package com.yahorzabotsin.openvpnclientgate.core.ui.main

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectionBootstrap
import com.yahorzabotsin.openvpnclientgate.core.servers.ServerRepository
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2Repository
import com.yahorzabotsin.openvpnclientgate.core.servers.CountryV2
import com.yahorzabotsin.openvpnclientgate.core.servers.toLegacyServer
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface MainSelectionInteractor {
    suspend fun loadInitialSelection(cacheOnly: Boolean): InitialSelection?
}

data class InitialSelection(
    val country: String,
    val city: String,
    val config: String,
    val countryCode: String?,
    val ip: String?
)

class DefaultMainSelectionInteractor(
    private val appContext: Context,
    private val serverRepository: ServerRepository,
    private val serversV2Repository: ServersV2Repository? = null
) : MainSelectionInteractor {
    private companion object {
        private val TAG = LogTags.APP + ":MainSelectionInteractor"
        private val serverPositionTextRegex = Regex("^\\d+/\\d+$")
    }

    override suspend fun loadInitialSelection(cacheOnly: Boolean): InitialSelection? {
        return withContext(Dispatchers.IO) {
            val source = UserSettingsStore.load(appContext).serverSource
            if (source == ServerSource.DEFAULT_V2) {
                return@withContext loadInitialSelectionV2(cacheOnly)
            }
            var result: InitialSelection? = null
            SelectionBootstrap.ensureSelection(
                context = appContext,
                getServers = {
                    serverRepository.getServers(appContext, cacheOnly = cacheOnly)
                },
                loadConfigs = { servers ->
                    serverRepository.loadConfigs(appContext, servers)
                }
            ) { country, city, config, countryCode, ip ->
                result = InitialSelection(
                    country = country,
                    city = city,
                    config = config,
                    countryCode = countryCode,
                    ip = ip
                )
            }
            result
        }
    }

    private suspend fun loadInitialSelectionV2(cacheOnly: Boolean): InitialSelection? {
        val stored = SelectedCountryStore.currentServer(appContext)
        if (stored != null) {
            val country = SelectedCountryStore.getSelectedCountry(appContext) ?: return null
            if (stored.city.isBlank() || stored.city.isPositionLikeCityText()) {
                val hydrated = hydrateStoredSelectionFromV2(
                    selectedCountryName = country,
                    selectedCountryCode = stored.countryCode,
                    selectedIp = stored.ip,
                    selectedConfig = stored.config,
                    cacheOnly = cacheOnly
                )
                if (hydrated != null) return hydrated
                // Hydration returns null either because it could not resolve fresher data or
                // because the selection changed underneath it. In the latter case `stored` and
                // `country` are a snapshot of a country the user has already navigated away
                // from, and reporting them as the current selection would drift the UI (and, via
                // the caller, the connection) back to it. Fall through only while the snapshot is
                // still the live selection.
                // Country *and* server: switching to another server of the same country leaves the
                // country name unchanged, so a country-only check would let the superseded
                // `stored` snapshot through.
                if (!SelectedCountryStore.isCurrentSelection(
                        appContext,
                        country,
                        stored.config,
                        stored.ip
                    )
                ) {
                    AppLog.w(
                        TAG,
                        "loadInitialSelectionV2: selection changed while hydrating '$country', skipping stale result"
                    )
                    return null
                }
            }
            return InitialSelection(
                country = country,
                city = stored.city,
                config = stored.config,
                countryCode = stored.countryCode,
                ip = stored.ip
            )
        }
        val repo = serversV2Repository ?: return null
        val countries = loadV2CountriesOrNull(repo, cacheOnly) ?: return null
        if (countries.isEmpty()) return null
        val firstCountry = countries.first()
        val v2Servers = loadV2ServersOrNull(
            repo = repo,
            country = firstCountry,
            cacheOnly = cacheOnly
        ) ?: return null
        if (v2Servers.isEmpty()) return null
        val legacyServers = v2Servers.map { it.toLegacyServer() }
        SelectedCountryStore.saveSelection(appContext, firstCountry.name, legacyServers)
        val first = legacyServers.first()
        return InitialSelection(
            country = first.country.name,
            city = first.city,
            config = first.configData,
            countryCode = first.country.code,
            ip = first.ip
        )
    }

    private suspend fun loadV2CountriesOrNull(
        repo: ServersV2Repository,
        cacheOnly: Boolean
    ): List<CountryV2>? {
        return try {
            repo.getCountries(appContext, forceRefresh = false, cacheOnly = cacheOnly)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "DEFAULT_V2 startup country load failed", e)
            null
        }
    }

    private suspend fun loadV2ServersOrNull(
        repo: ServersV2Repository,
        country: CountryV2,
        cacheOnly: Boolean
    ): List<com.yahorzabotsin.openvpnclientgate.core.servers.ServerV2>? {
        return try {
            repo.getServersForCountry(
                context = appContext,
                countryCode = country.code,
                serverCount = country.serverCount,
                forceRefresh = false,
                cacheOnly = cacheOnly
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "DEFAULT_V2 startup server load failed for country=${country.code}", e)
            null
        }
    }

    private suspend fun hydrateStoredSelectionFromV2(
        selectedCountryName: String,
        selectedCountryCode: String?,
        selectedIp: String?,
        selectedConfig: String?,
        cacheOnly: Boolean
    ): InitialSelection? {
        val repo = serversV2Repository ?: return null
        val countries = loadV2CountriesOrNull(repo, cacheOnly) ?: return null
        val country = selectedCountryCode
            ?.takeIf { it.isNotBlank() }
            ?.let { code -> countries.firstOrNull { it.code.equals(code, ignoreCase = true) } }
            ?: countries.firstOrNull { it.name.equals(selectedCountryName, ignoreCase = true) }
            ?: return null

        val servers = loadV2ServersOrNull(repo, country, cacheOnly) ?: return null
        if (servers.isEmpty()) return null

        val legacyServers = servers.map { it.toLegacyServer() }
        // The index is resolved against `legacyServers` only -- no persisted state -- so it can
        // be computed before the write and handed to the store, which applies list and index
        // together under one lock.
        val selectedIndex = when {
            !selectedConfig.isNullOrBlank() ->
                legacyServers.indexOfFirst { it.configData == selectedConfig }
                    .takeIf { it >= 0 }
                    ?: legacyServers.indexOfFirst { !selectedIp.isNullOrBlank() && it.ip == selectedIp }
                        .takeIf { it >= 0 }
                    ?: 0
            !selectedIp.isNullOrBlank() ->
                legacyServers.indexOfFirst { it.ip == selectedIp }.takeIf { it >= 0 } ?: 0
            else -> 0
        }

        // Hydration is a *deferred* write: this path is entered from
        // MainViewModel.onStoreVersionChanged() with a selection captured before the country and
        // server loads above, so the user can pick a different country while they are in flight.
        // An unguarded write here would resurrect the captured country on top of that newer
        // choice. Guarding on the country we read at entry makes the check, the server-list write
        // and the dependent index write ONE critical section inside SelectedCountryStore's
        // selection monitor -- the same monitor the newer selection takes -- so this hydration
        // either lands whole before that selection or stands down entirely. Splitting the index
        // write out of that section would reopen the gap: the newer country's pool would be
        // persisted with an index measured against this (now discarded) list.
        val written = SelectedCountryStore.saveSelectionAndSetIndexIfCurrent(
            appContext,
            country.name,
            legacyServers,
            selectedIndex,
            expectedCountry = selectedCountryName
        )
        if (!written) {
            AppLog.w(
                TAG,
                "hydrateStoredSelectionFromV2: selection changed while hydrating '$selectedCountryName', discarding hydration"
            )
            return null
        }

        // The write landed, but a newer selection may have committed on top of it right after the
        // monitor was released. Returning this result then would push a selection the user has
        // already moved off back into the UI (and, via the caller, the connection), so revalidate
        // before handing it out. The persisted store is already consistent either way.
        //
        // The check must cover the *server*, not just the country: picking a different server
        // inside the same country only moves the index, so getSelectedCountry() stays equal and a
        // country-only check would wave the superseded pairing through -- reconnecting to the
        // server the user just moved away from. isCurrentSelection compares country and persisted
        // current-server identity as one atomic read under the selection monitor.
        val selected = legacyServers[selectedIndex]
        if (!SelectedCountryStore.isCurrentSelection(
                appContext,
                country.name,
                selected.configData,
                selected.ip
            )
        ) {
            AppLog.w(
                TAG,
                "hydrateStoredSelectionFromV2: selection changed after hydrating '${country.name}', discarding stale result"
            )
            return null
        }

        return InitialSelection(
            country = country.name,
            city = selected.city,
            config = selected.configData,
            countryCode = country.code,
            ip = selected.ip
        )
    }

    private fun String.isPositionLikeCityText(): Boolean {
        val value = trim()
        if (value.isBlank()) return false
        if (value == "\u2014/\u2014") return true
        if (value == "--/--") return true
        return serverPositionTextRegex.matches(value)
    }
}
