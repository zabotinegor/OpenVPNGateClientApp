package com.yahorzabotsin.openvpnclientgate.core.servers.refresh

object ServerRefreshFeatureFlags {
    // Temporary product toggle: when false, server list fetches from API even with active VPN.
    const val CACHE_ONLY_WHEN_VPN_CONNECTED: Boolean = false

    fun shouldUseCacheOnlyWhenVpnConnected(isVpnConnected: Boolean): Boolean {
        return CACHE_ONLY_WHEN_VPN_CONNECTED && isVpnConnected
    }
}
