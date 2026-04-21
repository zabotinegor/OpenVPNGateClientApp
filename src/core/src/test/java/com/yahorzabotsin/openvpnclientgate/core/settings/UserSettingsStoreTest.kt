package com.yahorzabotsin.openvpnclientgate.core.settings

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UserSettingsStoreTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun load_uses_legacy_timeout_when_new_key_missing() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("auto_switch_timeout_seconds", 7)
            .commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(7, settings.statusStallTimeoutSeconds)
    }

    @Test
    fun load_clamps_legacy_timeout_to_minimum() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit()
            .putInt("auto_switch_timeout_seconds", 0)
            .commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(1, settings.statusStallTimeoutSeconds)
    }

    @Test
    fun save_status_stall_timeout_clamps_to_minimum() {
        UserSettingsStore.saveStatusStallTimeoutSeconds(context, 0)

        val settings = UserSettingsStore.load(context)
        assertEquals(1, settings.statusStallTimeoutSeconds)
    }

    @Test
    fun load_defaults_dns_option_to_server() {
        val settings = UserSettingsStore.load(context)
        assertEquals(DnsOption.SERVER, settings.dnsOption)
    }

    @Test
    fun save_dns_option_persists() {
        UserSettingsStore.saveDnsOption(context, DnsOption.QUAD9)

        val settings = UserSettingsStore.load(context)
        assertEquals(DnsOption.QUAD9, settings.dnsOption)
    }

    @Test
    fun resolve_server_urls_filters_placeholder_for_custom_source() {
        val urls = UserSettingsStore.resolveServerUrls(
            UserSettings(
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "https://placeholder/api/v1/servers/active"
            )
        )

        assertTrue(urls.isEmpty())
    }

    @Test
    fun resolve_server_urls_filters_non_https_for_custom_source() {
        val urls = UserSettingsStore.resolveServerUrls(
            UserSettings(
                serverSource = ServerSource.CUSTOM,
                customServerUrl = "http://example.com/api/v1/servers/active"
            )
        )

        assertTrue(urls.isEmpty())
    }
}

