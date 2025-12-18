package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context

object SelectionBootstrap {
    suspend fun ensureSelection(
        context: Context,
        getServers: suspend () -> List<Server>,
        loadConfigs: suspend (List<Server>) -> Map<Int, String>,
        apply: (country: String, city: String, config: String, countryCode: String?, ip: String?) -> Unit
    ) {
        val stored = SelectedCountryStore.currentServer(context)
        if (stored != null) {
            val country = SelectedCountryStore.getSelectedCountry(context) ?: return
            apply(country, stored.city, stored.config, stored.countryCode, stored.ip)
            return
        }

        val servers = getServers()
        if (servers.isEmpty()) return
        val configs = loadConfigs(servers)

        var targetCountry: String? = null
        var first: Server? = null
        val countryServers = mutableListOf<Server>()

        for (s in servers) {
            if (targetCountry == null) {
                targetCountry = s.country.name
                first = s
            }
            if (s.country.name == targetCountry) {
                countryServers.add(s)
            }
        }

        val country = targetCountry ?: return
        val firstServer = first ?: return
        val resolved = countryServers.map { it.copy(configData = configs[it.lineIndex].orEmpty()) }
        SelectedCountryStore.saveSelection(context, country, resolved)
        val firstResolved = resolved.firstOrNull() ?: return
        apply(country, firstResolved.city, firstResolved.configData, firstResolved.country.code, firstResolved.ip)
    }
}
