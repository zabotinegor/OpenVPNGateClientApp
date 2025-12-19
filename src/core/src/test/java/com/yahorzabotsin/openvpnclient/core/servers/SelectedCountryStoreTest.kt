package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelectedCountryStoreTest {

    private fun server(
        name: String,
        city: String,
        country: Country = Country("CountryA"),
        config: String,
        lineIndex: Int = 1,
        signalStrength: SignalStrength = SignalStrength.STRONG
    ) = Server(
        lineIndex = lineIndex,
        name = name,
        city = city,
        country = country,
        ping = 10,
        signalStrength = signalStrength,
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
        configData = config
    )

    @Test
    fun save_and_iterate_servers() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1),
            server(name = "srv-2", city = "City2", config = "config2", lineIndex = 2, signalStrength = SignalStrength.MEDIUM)
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

        val serversA = listOf(server(name = "srv-1", city = "City1", country = Country("CountryA"), config = "configA1", lineIndex = 1))

        SelectedCountryStore.saveSelection(ctx, "CountryA", serversA)
        SelectedCountryStore.resetIndex(ctx)

        SelectedCountryStore.saveLastSuccessfulConfig(ctx, "CountryA", "configA1")
        val forA = SelectedCountryStore.getLastSuccessfulConfigForSelected(ctx)
        assertEquals("configA1", forA)

        val serversB = listOf(server(name = "srv-2", city = "City2", country = Country("CountryB"), config = "configB1", lineIndex = 1, signalStrength = SignalStrength.MEDIUM))

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
        assertEquals("CountryA", last!!.country)
        assertEquals("conf-start-A", last.config)
        assertNull(last.ip)

        // Blank configs should be ignored
        SelectedCountryStore.saveLastStartedConfig(ctx, "CountryA", "")
        val stillLast = SelectedCountryStore.getLastStartedConfig(ctx)
        assertNotNull(stillLast)
        assertEquals("CountryA", stillLast!!.country)
        assertEquals("conf-start-A", stillLast.config)
    }

    @Test
    fun next_server_circular_stops_after_full_cycle() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1),
            server(name = "srv-2", city = "City2", config = "config2", lineIndex = 2),
            server(name = "srv-3", city = "City3", config = "config3", lineIndex = 3)
        )

        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        val first = SelectedCountryStore.nextServerCircular(ctx, 1)
        assertEquals("City3", first?.city)
        val second = SelectedCountryStore.nextServerCircular(ctx, 1)
        assertEquals("City1", second?.city)
        val third = SelectedCountryStore.nextServerCircular(ctx, 1)
        assertNull(third)
    }

    @Test
    fun ensure_index_for_config_recovers_from_invalid_index() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1),
            server(name = "srv-2", city = "City2", config = "config2", lineIndex = 2, signalStrength = SignalStrength.MEDIUM)
        )

        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE)
            .edit()
            .putInt("selected_country_index", -1)
            .commit()

        SelectedCountryStore.ensureIndexForConfig(ctx, "config2")

        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("City2", current!!.city)
    }

    @Test
    fun saveSelection_persists_country_code_in_stored_servers() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(server(name = "srv-1", city = "City1", country = Country("CountryA", "AA"), config = "config1", lineIndex = 1))

        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)

        val stored = SelectedCountryStore.getServers(ctx)
        assertEquals(1, stored.size)
        assertEquals("City1", stored[0].city)
        assertEquals("config1", stored[0].config)
        assertEquals("AA", stored[0].countryCode)
    }
}
