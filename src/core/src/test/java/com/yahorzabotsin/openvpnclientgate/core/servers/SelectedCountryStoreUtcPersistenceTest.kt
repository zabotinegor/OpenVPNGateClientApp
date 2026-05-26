package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelectedCountryStoreUtcPersistenceTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // AC-1.2 — SelectedCountryStore persists UTC with server selection
    @Test
    fun saveSelection_persists_utc() {
        val servers = listOf(
            Server(
                lineIndex = 0,
                name = "Server1",
                city = "Tokyo",
                country = Country(name = "Japan", code = "JP"),
                ping = 20,
                signalStrength = SignalStrength.STRONG,
                ip = "203.0.113.1",
                score = 100,
                speed = 1000L,
                numVpnSessions = 50,
                uptime = 99,
                totalUsers = 1000,
                totalTraffic = 5000L,
                logType = "v2",
                operator = "op",
                message = "",
                configData = "config1",
                utc = "UTC+9"
            ),
            Server(
                lineIndex = 1,
                name = "Server2",
                city = "Paris",
                country = Country(name = "France", code = "FR"),
                ping = 30,
                signalStrength = SignalStrength.MEDIUM,
                ip = "203.0.113.2",
                score = 95,
                speed = 900L,
                numVpnSessions = 40,
                uptime = 98,
                totalUsers = 800,
                totalTraffic = 4000L,
                logType = "v2",
                operator = "op",
                message = "",
                configData = "config2",
                utc = "UTC+1"
            )
        )

        SelectedCountryStore.saveSelection(context, "Japan", servers)

        val stored = SelectedCountryStore.getServers(context)
        assertEquals(2, stored.size)
        assertEquals("Tokyo", stored[0].city)
        assertEquals("UTC+9", stored[0].utc)
        assertEquals("Paris", stored[1].city)
        assertEquals("UTC+1", stored[1].utc)
    }

    // AC-1.2 — SelectedCountryStore handles null UTC gracefully
    @Test
    fun saveSelection_handles_null_utc() {
        val servers = listOf(
            Server(
                lineIndex = 0,
                name = "Server1",
                city = "Berlin",
                country = Country(name = "Germany", code = "DE"),
                ping = 15,
                signalStrength = SignalStrength.STRONG,
                ip = "203.0.113.3",
                score = 100,
                speed = 1100L,
                numVpnSessions = 60,
                uptime = 99,
                totalUsers = 1200,
                totalTraffic = 6000L,
                logType = "v2",
                operator = "op",
                message = "",
                configData = "config3",
                utc = null
            )
        )

        SelectedCountryStore.saveSelection(context, "Germany", servers)

        val stored = SelectedCountryStore.getServers(context)
        assertEquals(1, stored.size)
        assertEquals("Berlin", stored[0].city)
        assertNull(stored[0].utc)
    }

    // AC-1.2 — SelectedCountryStore preserves UTC on index change
    @Test
    fun setCurrentIndex_preserves_utc() {
        val servers = listOf(
            Server(
                lineIndex = 0,
                name = "Server1",
                city = "Madrid",
                country = Country(name = "Spain", code = "ES"),
                ping = 25,
                signalStrength = SignalStrength.MEDIUM,
                ip = "203.0.113.4",
                score = 90,
                speed = 950L,
                numVpnSessions = 45,
                uptime = 97,
                totalUsers = 900,
                totalTraffic = 4500L,
                logType = "v2",
                operator = "op",
                message = "",
                configData = "config4",
                utc = "UTC+1"
            ),
            Server(
                lineIndex = 1,
                name = "Server2",
                city = "Barcelona",
                country = Country(name = "Spain", code = "ES"),
                ping = 28,
                signalStrength = SignalStrength.WEAK,
                ip = "203.0.113.5",
                score = 85,
                speed = 800L,
                numVpnSessions = 35,
                uptime = 96,
                totalUsers = 700,
                totalTraffic = 3500L,
                logType = "v2",
                operator = "op",
                message = "",
                configData = "config5",
                utc = "UTC+1"
            )
        )

        SelectedCountryStore.saveSelection(context, "Spain", servers)
        SelectedCountryStore.setCurrentIndex(context, 1)

        val current = SelectedCountryStore.currentServer(context)
        assertEquals("Barcelona", current?.city)
        assertEquals("UTC+1", current?.utc)
    }

    // AC-1.2 — SelectedCountryStore handles empty UTC string
    @Test
    fun saveSelection_handles_empty_utc_string() {
        val servers = listOf(
            Server(
                lineIndex = 0,
                name = "Server1",
                city = "Amsterdam",
                country = Country(name = "Netherlands", code = "NL"),
                ping = 10,
                signalStrength = SignalStrength.STRONG,
                ip = "203.0.113.6",
                score = 100,
                speed = 1200L,
                numVpnSessions = 70,
                uptime = 99,
                totalUsers = 1500,
                totalTraffic = 7000L,
                logType = "v2",
                operator = "op",
                message = "",
                configData = "config6",
                utc = ""
            )
        )

        SelectedCountryStore.saveSelection(context, "Netherlands", servers)

        val stored = SelectedCountryStore.getServers(context)
        assertEquals(1, stored.size)
        assertEquals("Amsterdam", stored[0].city)
        assertEquals("", stored[0].utc)
    }
}
