package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelectedCountryStoreTest {

    @Test
    fun save_and_iterate_servers() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            Server(
                name = "srv-1",
                city = "City1",
                country = Country("CountryA"),
                ping = 10,
                signalStrength = SignalStrength.STRONG,
                ip = "1.1.1.1",
                score = 100,
                speed = 1000,
                numVpnSessions = 1,
                uptime = 100,
                totalUsers = 10,
                totalTraffic = 1000,
                logType = "",
                operator = "",
                message = "",
                configData = "config1"
            ),
            Server(
                name = "srv-2",
                city = "City2",
                country = Country("CountryA"),
                ping = 20,
                signalStrength = SignalStrength.MEDIUM,
                ip = "2.2.2.2",
                score = 90,
                speed = 900,
                numVpnSessions = 2,
                uptime = 200,
                totalUsers = 20,
                totalTraffic = 2000,
                logType = "",
                operator = "",
                message = "",
                configData = "config2"
            )
        )

        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)

        assertEquals("CountryA", SelectedCountryStore.getSelectedCountry(ctx))
        val stored = SelectedCountryStore.getServers(ctx)
        assertEquals(2, stored.size)
        assertEquals("City1", stored[0].city)
        assertEquals("config1", stored[0].config)

        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("City1", current!!.city)

        val next = SelectedCountryStore.nextServer(ctx)
        assertNotNull(next)
        assertEquals("City2", next!!.city)

        val none = SelectedCountryStore.nextServer(ctx)
        assertNull(none)

        SelectedCountryStore.resetIndex(ctx)
        val again = SelectedCountryStore.currentServer(ctx)
        assertEquals("City1", again!!.city)
    }

    @Test
    fun last_successful_config_is_scoped_to_selected_country() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val serversA = listOf(
            Server(
                name = "srv-1",
                city = "City1",
                country = Country("CountryA"),
                ping = 10,
                signalStrength = SignalStrength.STRONG,
                ip = "1.1.1.1",
                score = 100,
                speed = 1000,
                numVpnSessions = 1,
                uptime = 100,
                totalUsers = 10,
                totalTraffic = 1000,
                logType = "",
                operator = "",
                message = "",
                configData = "configA1"
            )
        )

        SelectedCountryStore.saveSelection(ctx, "CountryA", serversA)
        SelectedCountryStore.resetIndex(ctx)

        SelectedCountryStore.saveLastSuccessfulConfig(ctx, "CountryA", "configA1")
        val forA = SelectedCountryStore.getLastSuccessfulConfigForSelected(ctx)
        assertEquals("configA1", forA)

        val serversB = listOf(
            Server(
                name = "srv-2",
                city = "City2",
                country = Country("CountryB"),
                ping = 20,
                signalStrength = SignalStrength.MEDIUM,
                ip = "2.2.2.2",
                score = 90,
                speed = 900,
                numVpnSessions = 2,
                uptime = 200,
                totalUsers = 20,
                totalTraffic = 2000,
                logType = "",
                operator = "",
                message = "",
                configData = "configB1"
            )
        )

        SelectedCountryStore.saveSelection(ctx, "CountryB", serversB)
        SelectedCountryStore.resetIndex(ctx)

        val forB = SelectedCountryStore.getLastSuccessfulConfigForSelected(ctx)
        assertNull(forB)
    }

    @Test
    fun last_started_config_persists_and_is_read_back() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        SelectedCountryStore.saveLastStartedConfig(ctx, "CountryA", "conf-start-A")
        val last = SelectedCountryStore.getLastStartedConfig(ctx)
        assertNotNull(last)
        assertEquals("CountryA", last!!.first)
        assertEquals("conf-start-A", last.second)

        // Blank configs should be ignored
        SelectedCountryStore.saveLastStartedConfig(ctx, "CountryA", "")
        val stillLast = SelectedCountryStore.getLastStartedConfig(ctx)
        assertNotNull(stillLast)
        assertEquals("CountryA", stillLast!!.first)
        assertEquals("conf-start-A", stillLast.second)
    }
}
