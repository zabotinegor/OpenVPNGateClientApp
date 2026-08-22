package com.yahorzabotsin.openvpnclientgate.core.settings

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.ApiConstants
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

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

    // UT-1.1a — migration: stored legacy "DEFAULT" string -> ServerSource.DEFAULT_V2
    @Test
    fun load_legacy_default_string_migration() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit().putString("server_source", "DEFAULT").commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.DEFAULT_V2, settings.serverSource)
    }

    // UT-1.1b — migration: stored "LEGACY" (removed enum value) -> ServerSource.DEFAULT_V2
    @Test
    fun load_legacy_enum_name_migration() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit().putString("server_source", "LEGACY").commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.DEFAULT_V2, settings.serverSource)
    }

    // UT-1.1c — migration: stored "CUSTOM" (removed enum value) -> ServerSource.DEFAULT_V2
    @Test
    fun load_custom_enum_name_migration() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit().putString("server_source", "CUSTOM").commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.DEFAULT_V2, settings.serverSource)
    }

    // F4 (docs/qa-evidence/release-review-1.md): load() must remove the orphaned
    // "custom_server_url" key left behind by the US-14 removal of the custom-server-URL feature,
    // so a value a user entered before the removal does not linger indefinitely in this
    // SharedPreferences file with no UI left to view or clear it.
    @Test
    fun load_removes_orphaned_custom_server_url_key() {
        val prefs = context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("server_source", "CUSTOM")
            .putString("custom_server_url", "https://example.invalid/servers.csv")
            .commit()
        assertTrue(
            "precondition: the orphaned key must actually be present before load() runs",
            prefs.contains("custom_server_url")
        )

        UserSettingsStore.load(context)

        assertTrue(
            "load() must remove the orphaned custom_server_url key so it does not persist " +
                "indefinitely after the feature that wrote it was removed",
            !prefs.contains("custom_server_url")
        )
    }

    // UT-1.2 — stored "DEFAULT_V2" round-trips correctly
    @Test
    fun load_default_v2() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit().putString("server_source", "DEFAULT_V2").commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.DEFAULT_V2, settings.serverSource)
    }

    // UT-1.3 — unknown/stale stored key falls back to DEFAULT_V2 (no crash on unresolved enum name)
    @Test
    fun load_unknown_key_falls_back_to_default_v2() {
        context.getSharedPreferences("user_settings", Context.MODE_PRIVATE)
            .edit().putString("server_source", "TOTALLY_UNKNOWN").commit()

        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.DEFAULT_V2, settings.serverSource)
    }

    // UT-1.4 — save DEFAULT_V2 then reload returns DEFAULT_V2
    @Test
    fun save_and_load_roundtrip_default_v2() {
        UserSettingsStore.saveServerSource(context, ServerSource.DEFAULT_V2)
        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.DEFAULT_V2, settings.serverSource)
    }

    // UT-1.5 — DEFAULT_V2 does not expose CSV URLs directly
    @Test
    fun resolve_server_urls_default_v2_returns_empty_csv_list() {
        val urls = UserSettingsStore.resolveServerUrls(
            UserSettings(serverSource = ServerSource.DEFAULT_V2)
        )
        assertTrue(urls.isEmpty())
    }

    // UT-1.6 — VPNGATE resolves to the single fallback CSV URL
    @Test
    fun resolve_server_urls_vpngate_returns_fallback_url() {
        val urls = UserSettingsStore.resolveServerUrls(
            UserSettings(serverSource = ServerSource.VPNGATE)
        )
        assertEquals(1, urls.size)
        assertEquals(ApiConstants.FALLBACK_SERVERS_URL, urls.first())
    }

    // isUsableServerUrl — placeholder host is rejected regardless of source. This guard is still
    // live for every remaining ServerSource (including VPNGATE via resolveServerUrls), even though
    // the CUSTOM source that used to exercise it directly has been removed.
    @Test
    fun isUsableServerUrl_rejects_placeholder_host() {
        assertTrue(
            !UserSettingsStore.isUsableServerUrl("https://placeholder/api/v1/servers/active")
        )
    }

    // isUsableServerUrl — non-HTTPS scheme is rejected regardless of source.
    @Test
    fun isUsableServerUrl_rejects_non_https_scheme() {
        assertTrue(
            !UserSettingsStore.isUsableServerUrl("http://example.com/api/v1/servers/active")
        )
    }

    // isUsableServerUrl — a well-formed https URL with a real host is accepted.
    @Test
    fun isUsableServerUrl_accepts_valid_https_url() {
        assertTrue(UserSettingsStore.isUsableServerUrl("https://example.com/api/v1/servers/active"))
    }

    // AC-1: new install (no stored key) defaults to DEFAULT_V2
    @Test
    fun load_new_install_defaults_to_default_v2() {
        // setUp() already cleared prefs — no "server_source" key exists
        val settings = UserSettingsStore.load(context)
        assertEquals(ServerSource.DEFAULT_V2, settings.serverSource)
    }

    @Test
    fun resolve_preferred_locale_maps_explicit_languages() {
        assertEquals("en", UserSettingsStore.resolvePreferredLocale(LanguageOption.ENGLISH))
        assertEquals("ru", UserSettingsStore.resolvePreferredLocale(LanguageOption.RUSSIAN))
        assertEquals("pl", UserSettingsStore.resolvePreferredLocale(LanguageOption.POLISH))
    }

    @Test
    fun resolvePreferredLocale_systemFallbackToEn() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("de"))
            val settings = UserSettings(language = LanguageOption.SYSTEM)
            UserSettingsStore.save(context, settings)

            val locale = UserSettingsStore.resolvePreferredLocale(context)
            assertEquals("en", locale)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun resolvePreferredLocale_systemUsesDeviceLocale() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("pl"))
            val settings = UserSettings(language = LanguageOption.SYSTEM)
            UserSettingsStore.save(context, settings)

            val locale = UserSettingsStore.resolvePreferredLocale(context)
            assertEquals("pl", locale)
        } finally {
            Locale.setDefault(previousLocale)
        }
    }
}

