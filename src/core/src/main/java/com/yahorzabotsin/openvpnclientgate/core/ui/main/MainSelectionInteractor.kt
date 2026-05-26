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
        SelectedCountryStore.saveSelection(appContext, country.name, legacyServers)

        val selectedIndex = legacyServers.indexOfFirst { srv ->
            (!selectedIp.isNullOrBlank() && srv.ip == selectedIp) ||
                (!selectedConfig.isNullOrBlank() && srv.configData == selectedConfig)
        }.takeIf { it >= 0 } ?: 0
        SelectedCountryStore.setCurrentIndex(appContext, selectedIndex)

        val selected = legacyServers[selectedIndex]
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
