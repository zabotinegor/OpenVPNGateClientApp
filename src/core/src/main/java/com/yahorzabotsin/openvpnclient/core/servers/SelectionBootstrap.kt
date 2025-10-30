package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context

object SelectionBootstrap {
    suspend fun ensureSelection(
        context: Context,
        getServers: suspend () -> List<Server>,
        apply: (country: String, city: String, config: String) -> Unit
    ) {
        val stored = SelectedCountryStore.currentServer(context)
        if (stored != null) {
            val country = SelectedCountryStore.getSelectedCountry(context) ?: return
            apply(country, stored.city, stored.config)
            return
        }

        val servers = getServers()
        val first = servers.firstOrNull() ?: return
        val country = first.country.name
        val countryServers = servers.filter { it.country.name == country }
        SelectedCountryStore.saveSelection(context, country, countryServers)
        apply(country, first.city, first.configData)
    }
}
