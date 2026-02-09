package com.yahorzabotsin.openvpnclientgate.core.ui.main

import android.content.Context
import android.util.Log
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore

data class PreparedConnectionStart(
    val config: String,
    val country: String?,
    val ip: String?
)

interface MainConnectionInteractor {
    fun prepareStart(
        selectedServer: MainSelectedServer?,
        preferUserSelection: Boolean
    ): PreparedConnectionStart?
}

class DefaultMainConnectionInteractor(
    private val appContext: Context
) : MainConnectionInteractor {

    private val tag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "MainConnectionInteractor"

    override fun prepareStart(
        selectedServer: MainSelectedServer?,
        preferUserSelection: Boolean
    ): PreparedConnectionStart? {
        val currentConfig = selectedServer?.config
        if (currentConfig.isNullOrBlank()) return null

        val autoSwitchEnabled = try {
            UserSettingsStore.load(appContext).autoSwitchWithinCountry
        } catch (_: Exception) {
            true
        }

        val selectedConfig = runCatching { SelectedCountryStore.currentServer(appContext)?.config }.getOrNull()
        val lastSuccessfulConfig = runCatching { SelectedCountryStore.getLastSuccessfulConfigForSelected(appContext) }.getOrNull()
        val shouldUseLastSuccessful = lastSuccessfulConfig != null &&
            (!preferUserSelection || selectedConfig == lastSuccessfulConfig)

        val configToUse = if (shouldUseLastSuccessful) {
            if (autoSwitchEnabled) {
                runCatching {
                    SelectedCountryStore.prepareAutoSwitchFromStart(appContext)
                    SelectedCountryStore.ensureIndexForConfig(
                        appContext,
                        lastSuccessfulConfig,
                        resolveIpForConfig(lastSuccessfulConfig, selectedServer.ip)
                    )
                }.onFailure { e -> Log.e(tag, "Failed to prepare index for auto-switch from start", e) }
            }
            lastSuccessfulConfig
        } else {
            if (autoSwitchEnabled) {
                runCatching {
                    SelectedCountryStore.ensureIndexForConfig(
                        appContext,
                        currentConfig,
                        resolveIpForConfig(currentConfig, selectedServer.ip)
                    )
                }.onFailure { e -> Log.e(tag, "Failed to align server index with current selection", e) }
            }
            currentConfig
        } ?: return null

        val ipForConfig = resolveIpForConfig(configToUse, selectedServer.ip)
        runCatching {
            SelectedCountryStore.saveLastStartedConfig(appContext, selectedServer.country, configToUse, ipForConfig)
        }.onFailure { e -> Log.w(tag, "Failed to persist last started config", e) }

        return PreparedConnectionStart(
            config = configToUse,
            country = selectedServer.country,
            ip = ipForConfig
        )
    }

    private fun resolveIpForConfig(config: String?, fallbackIp: String?): String? {
        if (config.isNullOrBlank()) return fallbackIp
        val current = runCatching { SelectedCountryStore.currentServer(appContext) }.getOrNull()
        if (current?.config == config && !current.ip.isNullOrBlank()) return current.ip
        val lastSuccessfulIp = runCatching { SelectedCountryStore.getLastSuccessfulIpForSelected(appContext) }.getOrNull()
        val lastSuccessfulConfig = runCatching { SelectedCountryStore.getLastSuccessfulConfigForSelected(appContext) }.getOrNull()
        if (!lastSuccessfulIp.isNullOrBlank() && lastSuccessfulConfig == config) return lastSuccessfulIp
        return runCatching { SelectedCountryStore.getIpForConfig(appContext, config) }.getOrNull()
            ?: fallbackIp
    }
}

