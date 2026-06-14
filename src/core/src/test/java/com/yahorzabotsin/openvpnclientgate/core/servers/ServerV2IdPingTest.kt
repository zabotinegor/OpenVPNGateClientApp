package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for SUB-01: ServerV2.id, ServerV2.ping, toLegacyServer() propagation,
 * and SelectedCountryStore round-trip with the new id field.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ServerV2IdPingTest {

    private val gson = Gson()

    // TS-1 — Parse ServerV2 JSON with "id": 5, "ping": 120
    @Test
    fun serverV2_parses_id_and_ping_from_json() {
        val json = """
            {
                "ip": "10.0.0.1",
                "countryCode": "US",
                "countryName": "United States",
                "configData": "config_data",
                "id": 5,
                "ping": 120
            }
        """.trimIndent()

        val server = gson.fromJson(json, ServerV2::class.java)

        assertEquals(5, server.id)
        assertEquals(120, server.ping)
    }

    // TS-2 — Parse ServerV2 JSON without id or ping keys → defaults to 0, no exception
    @Test
    fun serverV2_defaults_id_and_ping_when_missing() {
        val json = """
            {
                "ip": "10.0.0.2",
                "countryCode": "DE",
                "countryName": "Germany",
                "configData": "config_data"
            }
        """.trimIndent()

        val server = gson.fromJson(json, ServerV2::class.java)

        assertEquals(0, server.id)
        assertEquals(0, server.ping)
    }

    // TS-3 — toLegacyServer() with ping=75 propagates real ping value
    @Test
    fun toLegacyServer_propagates_ping_75() {
        val v2 = ServerV2(
            ip = "10.0.0.3",
            countryCode = "JP",
            countryName = "Japan",
            configData = "config_jp",
            id = 7,
            ping = 75
        )

        val legacy = v2.toLegacyServer()

        assertEquals(75, legacy.ping)
    }

    // TS-4 — toLegacyServer() with ping=0 (default) produces server.ping==0 (no regression)
    @Test
    fun toLegacyServer_ping_zero_default_no_regression() {
        val v2 = ServerV2(
            ip = "10.0.0.4",
            countryCode = "FR",
            countryName = "France",
            configData = "config_fr"
        )

        val legacy = v2.toLegacyServer()

        assertEquals(0, legacy.ping)
    }

    // TS-3 (extra) — toLegacyServer() propagates id from ServerV2 to Server
    @Test
    fun toLegacyServer_propagates_id() {
        val v2 = ServerV2(
            ip = "10.0.0.5",
            countryCode = "GB",
            countryName = "United Kingdom",
            configData = "config_gb",
            id = 42,
            ping = 30
        )

        val legacy = v2.toLegacyServer()

        assertEquals(42, legacy.id)
    }

    // TS-5 — SelectedCountryStore saveSelection writes id=42, currentServer() reads it back as id==42
    @Test
    fun selectedCountryStore_round_trip_with_id() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val server = makeServer(id = 42, config = "config_a", ip = "1.1.1.1")
        SelectedCountryStore.saveSelection(ctx, "CountryA", listOf(server))

        val stored = SelectedCountryStore.currentServer(ctx)
        assertEquals(42, stored?.id)
    }

    // TS-6 — SelectedCountryStore reads JSON blob without "id" key → returns id==0, no crash
    @Test
    fun selectedCountryStore_reads_legacy_json_without_id_defaults_to_zero() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        // Write legacy JSON without "id" key
        val legacyJson = """[{"city":"OldCity","config":"old_config","code":"OC","ip":"9.9.9.9","utc":null}]"""
        prefs.edit()
            .putString("selected_country", "OldCountry")
            .putString("selected_country_servers", legacyJson)
            .putInt("selected_country_index", 0)
            .commit()

        val stored = SelectedCountryStore.currentServer(ctx)
        assertEquals(0, stored?.id)
        assertEquals("OldCity", stored?.city)
        assertEquals("old_config", stored?.config)
    }

    // TS-7 — Parse ServerV2 from old-format JSON (no id/ping fields) → existing fields correct, id=0, ping=0
    @Test
    fun serverV2_old_format_json_backward_compatible() {
        val json = """
            {
                "ip": "203.0.113.1",
                "countryCode": "JP",
                "countryName": "Japan",
                "configData": "base64config",
                "city": "Tokyo",
                "utc": "UTC+9"
            }
        """.trimIndent()

        val server = gson.fromJson(json, ServerV2::class.java)

        assertEquals("203.0.113.1", server.ip)
        assertEquals("JP", server.countryCode)
        assertEquals("Japan", server.countryName)
        assertEquals("base64config", server.configData)
        assertEquals("Tokyo", server.city)
        assertEquals("UTC+9", server.utc)
        assertEquals(0, server.id)
        assertEquals(0, server.ping)
    }

    // TS-8A — ServerV2 no-arg constructor (all-default) yields correct id/ping defaults
    // Verifies that all constructor parameters have defaults, so Kotlin generates a no-arg
    // constructor for Gson to use instead of sun.misc.Unsafe. Without all-defaults, Gson
    // would bypass the constructor and the = 0 defaults would only hold by JVM coincidence.
    @Test
    fun serverV2_no_arg_constructor_has_zero_id_and_ping() {
        val server = ServerV2()
        assertEquals(0, server.id)
        assertEquals(0, server.ping)
        assertEquals("", server.ip)
    }

    // TS-8B — Gson deserialization via no-arg constructor path: minimal JSON still yields id=0
    @Test
    fun serverV2_gson_empty_object_yields_zero_id_and_ping() {
        val server = gson.fromJson("{}", ServerV2::class.java)
        assertEquals(0, server.id)
        assertEquals(0, server.ping)
    }

    // Helper: create a Server with all required fields
    private fun makeServer(
        id: Int = 0,
        config: String,
        ip: String,
        city: String = "TestCity",
        country: Country = Country("TestCountry", "TC")
    ) = Server(
        lineIndex = 0,
        name = ip,
        city = city,
        country = country,
        ping = 10,
        signalStrength = SignalStrength.WEAK,
        ip = ip,
        score = 0,
        speed = 0L,
        numVpnSessions = 0,
        uptime = 0L,
        totalUsers = 0L,
        totalTraffic = 0L,
        logType = "",
        operator = "",
        message = "",
        configData = config,
        utc = null,
        id = id
    )
}
