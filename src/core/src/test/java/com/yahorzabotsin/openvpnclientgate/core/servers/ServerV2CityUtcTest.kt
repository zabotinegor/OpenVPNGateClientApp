package com.yahorzabotsin.openvpnclientgate.core.servers

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerV2CityUtcTest {

    private val gson = Gson()

    // AC-1.1 — ServerV2 parses nullable city and UTC fields from API response
    @Test
    fun serverV2_parses_city_and_utc_fields() {
        val json = """
            {
                "ip": "203.0.113.1",
                "countryCode": "JP",
                "countryName": "Japan",
                "configData": "config123",
                "city": "Tokyo",
                "utc": "UTC+9"
            }
        """.trimIndent()

        val server = gson.fromJson(json, ServerV2::class.java)

        assertEquals("203.0.113.1", server.ip)
        assertEquals("Tokyo", server.city)
        assertEquals("UTC+9", server.utc)
    }

    // AC-1.1 — ServerV2 handles missing city and UTC fields gracefully
    @Test
    fun serverV2_handles_missing_city_and_utc() {
        val json = """
            {
                "ip": "203.0.113.2",
                "countryCode": "US",
                "countryName": "United States",
                "configData": "config456"
            }
        """.trimIndent()

        val server = gson.fromJson(json, ServerV2::class.java)

        assertEquals("203.0.113.2", server.ip)
        assertNull(server.city)
        assertNull(server.utc)
    }

    // AC-1.1 — ServerV2 handles null city and UTC fields in JSON
    @Test
    fun serverV2_handles_null_city_and_utc_in_json() {
        val json = """
            {
                "ip": "203.0.113.3",
                "countryCode": "DE",
                "countryName": "Germany",
                "configData": "config789",
                "city": null,
                "utc": null
            }
        """.trimIndent()

        val server = gson.fromJson(json, ServerV2::class.java)

        assertEquals("203.0.113.3", server.ip)
        assertNull(server.city)
        assertNull(server.utc)
    }

    // AC-1.2 — Mapping from ServerV2 to Server preserves city and UTC
    @Test
    fun serverV2_toLegacyServer_preserves_city_and_utc() {
        val v2 = ServerV2(
            ip = "203.0.113.4",
            countryCode = "FR",
            countryName = "France",
            configData = "config_fr",
            city = "Paris",
            utc = "UTC+1"
        )

        val legacyServer = v2.toLegacyServer()

        assertEquals("Paris", legacyServer.city)
        assertEquals("UTC+1", legacyServer.utc)
        assertEquals("203.0.113.4", legacyServer.ip)
    }

    // AC-1.2 — Mapping handles null city and UTC gracefully
    @Test
    fun serverV2_toLegacyServer_handles_null_city_and_utc() {
        val v2 = ServerV2(
            ip = "203.0.113.5",
            countryCode = "GB",
            countryName = "United Kingdom",
            configData = "config_gb",
            city = null,
            utc = null
        )

        val legacyServer = v2.toLegacyServer()

        assertEquals("", legacyServer.city)
        assertNull(legacyServer.utc)
        assertEquals("203.0.113.5", legacyServer.ip)
    }

    // AC-1.2 — Mapping uses IP as name when city is blank
    @Test
    fun serverV2_toLegacyServer_uses_ip_as_name_when_city_blank() {
        val v2 = ServerV2(
            ip = "203.0.113.6",
            countryCode = "CA",
            countryName = "Canada",
            configData = "config_ca",
            city = "   ",
            utc = null
        )

        val legacyServer = v2.toLegacyServer()

        assertEquals("", legacyServer.city)
        assertEquals("203.0.113.6", legacyServer.name)
    }

    // AC-1.2 — Mapping preserves UTC when present and blank city
    @Test
    fun serverV2_toLegacyServer_preserves_utc_with_blank_city() {
        val v2 = ServerV2(
            ip = "203.0.113.7",
            countryCode = "AU",
            countryName = "Australia",
            configData = "config_au",
            city = "",
            utc = "UTC+10"
        )

        val legacyServer = v2.toLegacyServer()

        assertEquals("", legacyServer.city)
        assertEquals("UTC+10", legacyServer.utc)
    }

    // AC-1.3 — Malformed JSON with extra fields doesn't crash parsing
    @Test
    fun serverV2_handles_extra_unexpected_fields() {
        val json = """
            {
                "ip": "203.0.113.8",
                "countryCode": "SG",
                "countryName": "Singapore",
                "configData": "config_sg",
                "city": "Singapore",
                "utc": "UTC+8",
                "unexpectedField": "value",
                "anotherField": 123
            }
        """.trimIndent()

        val server = gson.fromJson(json, ServerV2::class.java)

        assertEquals("203.0.113.8", server.ip)
        assertEquals("Singapore", server.city)
        assertEquals("UTC+8", server.utc)
    }

    // AC-1.3 — Empty string for city and UTC are treated as valid
    @Test
    fun serverV2_handles_empty_string_city_and_utc() {
        val json = """
            {
                "ip": "203.0.113.9",
                "countryCode": "NZ",
                "countryName": "New Zealand",
                "configData": "config_nz",
                "city": "",
                "utc": ""
            }
        """.trimIndent()

        val server = gson.fromJson(json, ServerV2::class.java)

        assertEquals("", server.city)
        assertEquals("", server.utc)
    }

    // AC-5.3 — Verify non-v2 sources don't have city/UTC fields
    @Test
    fun verify_legacy_server_model_compatibility() {
        // This verifies that when creating a legacy Server from non-v2 source,
        // it still works with the new utc field (optional)
        val legacyServer = Server(
            lineIndex = 0,
            name = "IP-Only",
            city = "Sample City",
            country = Country(name = "Sample", code = "SC"),
            ping = 10,
            signalStrength = SignalStrength.STRONG,
            ip = "203.0.113.10",
            score = 100,
            speed = 1000L,
            numVpnSessions = 50,
            uptime = 99,
            totalUsers = 1000,
            totalTraffic = 5000L,
            logType = "legacy",
            operator = "op",
            message = "",
            configData = "legacy_config",
            utc = null  // Legacy sources have no UTC
        )

        assertEquals("Sample City", legacyServer.city)
        assertNull(legacyServer.utc)
    }
}
