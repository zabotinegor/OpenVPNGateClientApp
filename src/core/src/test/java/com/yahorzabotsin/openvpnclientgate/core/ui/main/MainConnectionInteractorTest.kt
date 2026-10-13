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

    // region last-successful start with same-IP entries (ClickUp 12487v2bg7u)

    private val sharedIp = "5.228.228.136"

    private fun saveSharedIpPoolWithLastSuccessfulOnSecond(): MainSelectedServer {
        val ctx = RuntimeEnvironment.getApplication()
        SelectedCountryStore.saveSelection(ctx, "Belarus", listOf(
            makeServer("encoded-A", sharedIp), makeServer("encoded-B", sharedIp), makeServer("encoded-C", "6.6.6.6")
        ))
        SelectedCountryStore.setCurrentIndex(ctx, 1)
        // ACTION_START stores the DECODED config; the connection succeeded on entry B.
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, "Belarus", "decoded-B", sharedIp, alignIndex = false)
        return makeSelectedServer(config = "encoded-B", ip = sharedIp)
    }

    private fun simulateActionStartAlignment(config: String, ip: String) {
        val ctx = RuntimeEnvironment.getApplication()
        SelectedCountryStore.ensureIndexForConfig(ctx, config, ip)
        SelectedCountryStore.saveLastStartedConfig(ctx, "Belarus", config, ip)
    }

    @Test
    fun prepareStart_lastSuccessfulOnSecondSharedIpEntry_keepsThatEntryThroughActionStart() {
        val ctx = RuntimeEnvironment.getApplication()
        val selected = saveSharedIpPoolWithLastSuccessfulOnSecond()
        // Selection moved to the first entry meanwhile (e.g. after an app relaunch).
        SelectedCountryStore.setCurrentIndex(ctx, 0)

        val prepared = interactor.prepareStart(selected, preferUserSelection = false)
        simulateActionStartAlignment(prepared!!.config, sharedIp)

        assertEquals("decoded-B", prepared.config)
        assertEquals("counter must stay on entry B, the real target", 1, SelectedCountryStore.getCurrentIndex(ctx))
    }

    @Test
    fun prepareStart_lastSuccessfulOnSecondSharedIpEntry_seedsCycleWithThatEntryNotTheFirst() {
        val ctx = RuntimeEnvironment.getApplication()
        val selected = saveSharedIpPoolWithLastSuccessfulOnSecond()

        val prepared = interactor.prepareStart(selected, preferUserSelection = false)
        simulateActionStartAlignment(prepared!!.config, sharedIp)

        val visited = linkedSetOf(SelectedCountryStore.currentServerKey(ctx)!!)
        val next = SelectedCountryStore.nextUnvisitedServer(ctx, visited)
        assertEquals("encoded-C", next?.server?.config)
    }

    @Test
    fun prepareStart_lastSuccessfulServerRemovedByRefresh_fallsBackToIpAlignment() {
        val ctx = RuntimeEnvironment.getApplication()
        val selected = saveSharedIpPoolWithLastSuccessfulOnSecond()
        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "Belarus", listOf(
            makeServer("encoded-A", sharedIp), makeServer("encoded-C", "6.6.6.6")
        ))

        val prepared = interactor.prepareStart(selected, preferUserSelection = false)

        assertNotNull(prepared)
        assertEquals("falls back to the existing IP alignment", 0, SelectedCountryStore.getCurrentIndex(ctx))
    }

    @Test
    fun prepareStart_lastSuccessfulWithoutRecordedKey_usesLegacyIpAlignment() {
        val ctx = RuntimeEnvironment.getApplication()
        SelectedCountryStore.saveSelection(ctx, "Belarus", listOf(
            makeServer("encoded-A", "1.1.1.1"), makeServer("encoded-B", "2.2.2.2")
        ))
        // Older persisted data: only config/ip, no server key.
        ctx.getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE).edit()
            .putString("last_success_config", "decoded-B").putString("last_success_country", "Belarus")
            .putString("last_success_ip", "2.2.2.2").remove("last_success_server_key").commit()

        interactor.prepareStart(makeSelectedServer(config = "encoded-B", ip = "2.2.2.2"), preferUserSelection = false)

        assertEquals(1, SelectedCountryStore.getCurrentIndex(ctx))
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
