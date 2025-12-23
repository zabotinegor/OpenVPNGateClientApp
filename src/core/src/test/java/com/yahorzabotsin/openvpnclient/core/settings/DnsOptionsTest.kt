package com.yahorzabotsin.openvpnclient.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsOptionsTest {

    @Test
    fun resolve_server_option_disables_override() {
        val config = DnsOptions.resolve(DnsOption.SERVER)
        assertTrue(!config.overrideDns)
    }

    @Test
    fun resolve_providers_match_registry() {
        DnsOptions.providers.forEach { provider ->
            val config = DnsOptions.resolve(provider.option)
            assertTrue(config.overrideDns)
            assertEquals(provider.primary, config.primary)
            assertEquals(provider.secondary, config.secondary)
        }
    }
}
