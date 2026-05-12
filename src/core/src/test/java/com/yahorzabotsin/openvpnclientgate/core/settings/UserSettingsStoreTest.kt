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

    // UT-1.1 — migration: stored "DEFAULT" → ServerSource.LEGACY
    @Test
    fun load_legacy_migration() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit().putString("server_source", "DEFAULT").commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.LEGACY, settings.serverSource)
    }

    // UT-1.2 — stored "DEFAULT_V2" round-trips correctly
    @Test
    fun load_default_v2() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit().putString("server_source", "DEFAULT_V2").commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.DEFAULT_V2, settings.serverSource)
    }

    // UT-1.3 — unknown stored key falls back to LEGACY
    @Test
    fun load_unknown_key_falls_back_to_legacy() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit().putString("server_source", "TOTALLY_UNKNOWN").commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.LEGACY, settings.serverSource)
    }

    // UT-1.4 — save DEFAULT_V2 then reload returns DEFAULT_V2
    @Test
    fun save_and_load_roundtrip_default_v2() {
        UserSettingsStore.saveServerSource(context, ServerSource.DEFAULT_V2)
        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.DEFAULT_V2, settings.serverSource)
    }

    // UT-1.5 — DEFAULT_V2 resolves to the v2 server URL (not empty)
    @Test
    fun resolve_server_urls_default_v2_returns_v2_url() {
        val urls = UserSettingsStore.resolveServerUrls(
            UserSettings(serverSource = ServerSource.DEFAULT_V2)
        )
        // PRIMARY_SERVERS_V2_URL is injected via BuildConfig; in a valid build it resolves to a
        // non-empty, usable https URL and must not be empty after filtering.
        assertTrue(urls.isNotEmpty())
    }

    // UT-1.6 — LEGACY resolves to primary + fallback URL
    @Test
    fun resolve_server_urls_legacy_returns_primary_and_fallback() {
        val urls = UserSettingsStore.resolveServerUrls(
            UserSettings(serverSource = ServerSource.LEGACY)
        )
        assertEquals(2, urls.size)
    }

    // AC-1: new install (no stored key) defaults to DEFAULT_V2
    @Test
    fun load_new_install_defaults_to_default_v2() {
        // setUp() already cleared prefs — no "server_source" key exists
        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.DEFAULT_V2, settings.serverSource)
    }
}

