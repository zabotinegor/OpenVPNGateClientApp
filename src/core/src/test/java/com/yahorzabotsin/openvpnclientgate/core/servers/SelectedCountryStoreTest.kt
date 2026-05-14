package com.yahorzabotsin.openvpnclientgate.core.servers

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SelectedCountryStoreTest {

    private fun server(
        name: String,
        city: String,
        country: Country = Country("CountryA"),
        config: String,
        lineIndex: Int = 1,
        signalStrength: SignalStrength = SignalStrength.STRONG,
        ip: String = "1.1.1.1"
    ) = Server(
        lineIndex = lineIndex,
        name = name,
        city = city,
        country = country,
        ping = 10,
        signalStrength = signalStrength,
        ip = ip,
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
    fun ensure_index_for_config_prefers_matching_ip_when_config_duplicates() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "dup-config", lineIndex = 1, ip = "10.0.0.1"),
            server(name = "srv-2", city = "City2", config = "dup-config", lineIndex = 2, ip = "10.0.0.2")
        )
        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)
        SelectedCountryStore.resetIndex(ctx)

        SelectedCountryStore.ensureIndexForConfig(ctx, "dup-config", "10.0.0.2")

        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("City2", current!!.city)
        assertEquals("10.0.0.2", current.ip)
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

    @Test
    fun saveSelectionPreservingIndex_preserves_current_server_and_bumps_version() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val initial = listOf(
            server(name = "srv-1", city = "City1", country = Country("CountryA", "AA"), config = "config1", lineIndex = 1, ip = "10.0.0.1"),
            server(name = "srv-2", city = "City2", country = Country("CountryA", "AA"), config = "config2", lineIndex = 2, ip = "10.0.0.2")
        )
        SelectedCountryStore.saveSelection(ctx, "CountryA", initial)
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        val versionBefore = SelectedCountryVersionSignal.version.value

        val refreshed = listOf(
            server(name = "srv-x", city = "CityX", country = Country("CountryA", "AA"), config = "configX", lineIndex = 3, ip = "10.0.0.9"),
            server(name = "srv-2", city = "City2-new", country = Country("CountryA", "AA"), config = "config2", lineIndex = 4, ip = "10.0.0.2"),
            server(name = "srv-3", city = "City3", country = Country("CountryA", "AA"), config = "config3", lineIndex = 5, ip = "10.0.0.3")
        )

        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "CountryA", refreshed)

        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("config2", current!!.config)
        assertEquals("10.0.0.2", current.ip)
        assertEquals(3, SelectedCountryStore.getServers(ctx).size)
        assertEquals((2 to 3), SelectedCountryStore.getCurrentPosition(ctx))
        assertTrue(SelectedCountryVersionSignal.version.value > versionBefore)
    }

    @Test
    fun saveSelectionPreservingIndex_ignores_other_country() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val initial = listOf(
            server(name = "srv-1", city = "City1", country = Country("CountryA"), config = "config1", lineIndex = 1, ip = "10.0.0.1"),
            server(name = "srv-2", city = "City2", country = Country("CountryA"), config = "config2", lineIndex = 2, ip = "10.0.0.2")
        )
        SelectedCountryStore.saveSelection(ctx, "CountryA", initial)
        SelectedCountryStore.setCurrentIndex(ctx, 1)

        val versionBefore = SelectedCountryVersionSignal.version.value

        val otherCountryServers = listOf(
            server(name = "srv-3", city = "City3", country = Country("CountryB"), config = "config3", lineIndex = 3, ip = "10.0.1.3")
        )

        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "CountryB", otherCountryServers)

        assertEquals("CountryA", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals(2, SelectedCountryStore.getServers(ctx).size)
        assertEquals((2 to 2), SelectedCountryStore.getCurrentPosition(ctx))
        assertEquals(versionBefore, SelectedCountryVersionSignal.version.value)
    }

    @Test
    fun getCurrentPosition_returns_null_when_servers_json_is_malformed() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()

        prefs.edit()
            .putString("selected_country", "CountryA")
            .putString("selected_country_servers", "{bad-json")
            .putInt("selected_country_index", 0)
            .commit()

        assertTrue(SelectedCountryStore.getServers(ctx).isEmpty())
        assertNull(SelectedCountryStore.getCurrentPosition(ctx))
    }

    @Test
    fun updateSelectedCountryName_changes_country_name_and_bumps_version() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", country = Country("Russia", "RU"), config = "config1", lineIndex = 1)
        )
        SelectedCountryStore.saveSelection(ctx, "Russia", servers)

        val versionBefore = SelectedCountryVersionSignal.version.value

        // Simulate language change: update country name to Russian locale
        SelectedCountryStore.updateSelectedCountryName(ctx, "Россия")

        assertEquals("Россия", SelectedCountryStore.getSelectedCountry(ctx))
        val stored = SelectedCountryStore.getServers(ctx)
        assertEquals(1, stored.size)
        assertEquals("City1", stored[0].city)
        assertEquals("config1", stored[0].config)
        assertEquals("RU", stored[0].countryCode)
        assertTrue(SelectedCountryVersionSignal.version.value > versionBefore)
    }

    @Test
    fun updateSelectedCountryName_noop_when_name_unchanged() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1)
        )
        SelectedCountryStore.saveSelection(ctx, "Russia", servers)

        val versionBefore = SelectedCountryVersionSignal.version.value

        // Try to update to the same name
        SelectedCountryStore.updateSelectedCountryName(ctx, "Russia")

        assertEquals("Russia", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals(versionBefore, SelectedCountryVersionSignal.version.value)
    }

    @Test
    fun updateSelectedCountryName_preserves_server_index() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", config = "config1", lineIndex = 1),
            server(name = "srv-2", city = "City2", config = "config2", lineIndex = 2),
            server(name = "srv-3", city = "City3", config = "config3", lineIndex = 3)
        )
        SelectedCountryStore.saveSelection(ctx, "Russia", servers)
        SelectedCountryStore.setCurrentIndex(ctx, 2)

        SelectedCountryStore.updateSelectedCountryName(ctx, "Россия")

        assertEquals("Россия", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals(3, SelectedCountryStore.getServers(ctx).size)
        val current = SelectedCountryStore.currentServer(ctx)
        assertNotNull(current)
        assertEquals("City3", current!!.city)
    }

    @Test
    fun updateSelectedCountryName_updates_last_country_metadata_when_matching_selection() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val servers = listOf(
            server(name = "srv-1", city = "City1", country = Country("Russia", "RU"), config = "config1", lineIndex = 1, ip = "1.1.1.1")
        )
        SelectedCountryStore.saveSelection(ctx, "Russia", servers)
        SelectedCountryStore.saveLastStartedConfig(ctx, "Russia", "config1", "1.1.1.1")
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, "Russia", "config1", "1.1.1.1")

        SelectedCountryStore.updateSelectedCountryName(ctx, "Россия")

        assertEquals("Россия", SelectedCountryStore.getSelectedCountry(ctx))

        val lastStarted = SelectedCountryStore.getLastStartedConfig(ctx)
        assertNotNull(lastStarted)
        assertEquals("Россия", lastStarted!!.country)
        assertEquals("config1", lastStarted.config)

        assertEquals("config1", SelectedCountryStore.getLastSuccessfulConfigForSelected(ctx))
        assertEquals("1.1.1.1", SelectedCountryStore.getLastSuccessfulIpForSelected(ctx))
    }

    @Test
    fun updateSelectedCountryNameIfCurrent_skips_when_selection_changed() {
        val ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        val russiaServers = listOf(
            server(name = "srv-1", city = "City1", country = Country("Russia", "RU"), config = "config-ru", lineIndex = 1)
        )
        val germanyServers = listOf(
            server(name = "srv-2", city = "City2", country = Country("Germany", "DE"), config = "config-de", lineIndex = 2)
        )

        SelectedCountryStore.saveSelection(ctx, "Russia", russiaServers)
        val versionBefore = SelectedCountryVersionSignal.version.value

        SelectedCountryStore.saveSelection(ctx, "Germany", germanyServers)

        val updated = SelectedCountryStore.updateSelectedCountryNameIfCurrent(
            ctx = ctx,
            expectedCurrentCountryName = "Russia",
            newCountryName = "Россия"
        )

        assertFalse(updated)
        assertEquals("Germany", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals("config-de", SelectedCountryStore.currentServer(ctx)?.config)
        assertEquals(versionBefore, SelectedCountryVersionSignal.version.value)
    }
}

