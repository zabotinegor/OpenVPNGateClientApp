package com.yahorzabotsin.openvpnclientgate.core.ui.main

import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainConnectionInteractorTest {

    private val interactor = DefaultMainConnectionInteractor(RuntimeEnvironment.getApplication())

    // region prepareStart

    @Test
    fun prepareStart_null_server_returns_null() {
        val prepared = interactor.prepareStart(null, preferUserSelection = true)
        assertNull(prepared)
    }

    @Test
    fun prepareStart_preferUserSelection_true_reads_fresh_config_from_store() {
        val ctx = RuntimeEnvironment.getApplication()
        val freshConfig = "by-3-fresh"
        val ip = "213.184.224.127"
        SelectedCountryStore.saveSelection(ctx, "Belarus", listOf(
            makeServer("by-1-stale", ip),
            makeServer("by-2-stale", ip),
            makeServer(freshConfig, ip)
        ))
        SelectedCountryStore.setCurrentIndex(ctx, 2)

        val selected = makeSelectedServer(config = "by-3-stale", ip = ip)

        val prepared = interactor.prepareStart(selected, preferUserSelection = true)

        assertNotNull(prepared)
        assertEquals(freshConfig, prepared!!.config)
    }

    @Test
    fun prepareStart_preferUserSelection_true_falls_back_to_selected_server_config_when_store_empty() {
        val selected = makeSelectedServer(config = "fallback-config", ip = "10.0.0.1")

        val prepared = interactor.prepareStart(selected, preferUserSelection = true)

        assertNotNull(prepared)
        assertEquals("fallback-config", prepared!!.config)
    }

    @Test
    fun prepareStart_preferUserSelection_false_ignores_store_and_uses_selected_server_config() {
        val ctx = RuntimeEnvironment.getApplication()
        SelectedCountryStore.saveSelection(ctx, "Belarus", listOf(
            makeServer("store-config", "1.2.3.4")
        ))
        SelectedCountryStore.setCurrentIndex(ctx, 0)

        val selected = makeSelectedServer(config = "auto-switch-config", ip = "1.2.3.4")

        val prepared = interactor.prepareStart(selected, preferUserSelection = false)

        assertNotNull(prepared)
        assertEquals("auto-switch-config", prepared!!.config)
    }

    // endregion

    private fun makeServer(config: String, ip: String) = Server(
        lineIndex = 0,
        name = ip,
        city = "Minsk",
        country = Country("Belarus", "BY"),
        ping = 0,
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
        configData = config
    )

    private fun makeSelectedServer(config: String, ip: String) = MainSelectedServer(
        country = "Belarus",
        countryCode = "BY",
        city = "Minsk",
        config = config,
        ip = ip,
        fromUserSelection = true,
        version = 1L
    )

    @Test
    fun shouldStopForUserSelection_returnsTrueWhenPausedAndIpChanged() {
        val shouldStop = interactor.shouldStopForUserSelection(
            state = ConnectionState.PAUSED,
            previousConfig = "cfg",
            newConfig = "cfg",
            previousIp = "1.1.1.1",
            newIp = "2.2.2.2"
        )

        assertTrue(shouldStop)
    }

    @Test
    fun shouldStopForUserSelection_returnsTrueWhenPausedAndConfigChanged() {
        val shouldStop = interactor.shouldStopForUserSelection(
            state = ConnectionState.PAUSED,
            previousConfig = "cfg1",
            newConfig = "cfg2",
            previousIp = "1.1.1.1",
            newIp = "1.1.1.1"
        )

        assertTrue(shouldStop)
    }

    @Test
    fun shouldStopForUserSelection_returnsFalseWhenDisconnected() {
        val shouldStop = interactor.shouldStopForUserSelection(
            state = ConnectionState.DISCONNECTED,
            previousConfig = "cfg1",
            newConfig = "cfg2",
            previousIp = "1.1.1.1",
            newIp = "2.2.2.2"
        )

        assertFalse(shouldStop)
    }

    @Test
    fun shouldStopForUserSelection_returnsFalseWhenNoChangesWhileConnected() {
        val shouldStop = interactor.shouldStopForUserSelection(
            state = ConnectionState.CONNECTED,
            previousConfig = "cfg1",
            newConfig = "cfg1",
            previousIp = "1.1.1.1",
            newIp = "1.1.1.1"
        )

        assertFalse(shouldStop)
    }
}
