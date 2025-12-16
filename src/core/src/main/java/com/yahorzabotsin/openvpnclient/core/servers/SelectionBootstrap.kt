package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context

object SelectionBootstrap {
    suspend fun ensureSelection(
        context: Context,
        getServers: suspend () -> List<Server>,
        apply: (country: String, city: String, config: String, countryCode: String?) -> Unit
    ) {
        val stored = SelectedCountryStore.currentServer(context)
        if (stored != null) {
            val country = SelectedCountryStore.getSelectedCountry(context) ?: return
            apply(country, stored.city, stored.config, stored.countryCode)
            return
        }

        val servers = getServers()
        if (servers.isEmpty()) return

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
        SelectedCountryStore.saveSelection(context, country, countryServers)
        apply(country, firstServer.city, firstServer.configData, firstServer.country.code)
    }
}
