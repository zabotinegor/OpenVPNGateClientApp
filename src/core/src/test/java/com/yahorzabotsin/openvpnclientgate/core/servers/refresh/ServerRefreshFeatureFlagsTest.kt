package com.yahorzabotsin.openvpnclientgate.core.servers.refresh

import org.junit.Assert.assertFalse
import org.junit.Test

class ServerRefreshFeatureFlagsTest {

    @Test
    fun `cache only when vpn connected is disabled`() {
        assertFalse(ServerRefreshFeatureFlags.CACHE_ONLY_WHEN_VPN_CONNECTED)
        assertFalse(ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(true))
        assertFalse(ServerRefreshFeatureFlags.shouldUseCacheOnlyWhenVpnConnected(false))
    }
}
