package com.yahorzabotsin.openvpnclient.core.servers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectionBootstrapTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        context.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().apply()
    }

    @Test
    fun ensureSelection_loadsConfigs_and_savesSelection() = runBlocking {
        val srv = Server(
            lineIndex = 1,
            name = "srv",
            city = "City",
            country = Country("Country", "CC"),
            ping = 10,
            signalStrength = SignalStrength.STRONG,
            ip = "1.1.1.1",
            score = 100,
            speed = 1000,
            numVpnSessions = 1,
            uptime = 1,
            totalUsers = 1,
            totalTraffic = 1,
            logType = "log",
            operator = "op",
            message = "msg",
            configData = ""
        )

        var appliedConfig: String? = null
        SelectionBootstrap.ensureSelection(
            context = context,
            getServers = { listOf(srv) },
            loadConfigs = { listOf(srv).associate { it.lineIndex to "config-loaded" } }
        ) { _, _, config, _, _ ->
            appliedConfig = config
        }

        assertEquals("config-loaded", appliedConfig)
        val stored = SelectedCountryStore.currentServer(context)
        assertNotNull(stored)
        assertEquals("config-loaded", stored?.config)
    }

    @Test
    fun ensureSelection_prefersStoredSelection() = runBlocking {
        val storedServer = Server(
            lineIndex = 1,
            name = "srv-stored",
            city = "CityStored",
            country = Country("CountryStored", "CS"),
            ping = 10,
            signalStrength = SignalStrength.STRONG,
            ip = "1.1.1.1",
            score = 100,
            speed = 1000,
            numVpnSessions = 1,
            uptime = 1,
            totalUsers = 1,
            totalTraffic = 1,
            logType = "log",
            operator = "op",
            message = "msg",
            configData = "stored-config"
        )
        SelectedCountryStore.saveSelection(context, "CountryStored", listOf(storedServer))

        var appliedCountry: String? = null
        var appliedCity: String? = null
        var appliedConfig: String? = null
        var appliedCode: String? = null

        SelectionBootstrap.ensureSelection(
            context = context,
            getServers = { error("should not fetch when stored selection exists") },
            loadConfigs = { emptyMap() }
        ) { country, city, config, code, _ ->
            appliedCountry = country
            appliedCity = city
            appliedConfig = config
            appliedCode = code
        }

        assertEquals("CountryStored", appliedCountry)
        assertEquals("CityStored", appliedCity)
        assertEquals("stored-config", appliedConfig)
        assertEquals("CS", appliedCode)
    }
}
