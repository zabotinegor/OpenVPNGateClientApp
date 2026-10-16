package com.yahorzabotsin.openvpnclientgate.core.ui.main

import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import com.yahorzabotsin.openvpnclientgate.vpn.OpenVpnService
import com.yahorzabotsin.openvpnclientgate.vpn.VpnManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

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

    // region last-successful start with same-IP entries

    private val sharedIp = "5.228.228.136"
    private val sharedTextA = joinText(1001, sharedIp)
    private val sharedTextB = joinText(1002, sharedIp)
    private val sharedTextC = joinText(1003, "6.6.6.6")

    private fun saveSharedIpPoolWithLastSuccessfulOnSecond(): MainSelectedServer {
        val ctx = RuntimeEnvironment.getApplication()
        SelectedCountryStore.saveSelection(ctx, "Belarus", listOf(
            makeServer(joinEncoded(sharedTextA), sharedIp),
            makeServer(joinEncoded(sharedTextB), sharedIp),
            makeServer(joinEncoded(sharedTextC), "6.6.6.6")
        ))
        SelectedCountryStore.setCurrentIndex(ctx, 1)
        // ACTION_START stores the DECODED config; the connection succeeded on entry B.
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, "Belarus", sharedTextB, sharedIp, alignIndex = false)
        return makeSelectedServer(config = joinEncoded(sharedTextB), ip = sharedIp)
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

        assertEquals(sharedTextB, prepared.config)
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
        assertEquals(joinEncoded(sharedTextC), next?.server?.config)
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

    private fun joinText(port: Int, host: String) = "client\nproto udp\nremote $host $port\n"

    private fun joinEncoded(text: String) =
        android.util.Base64.encodeToString(text.toByteArray(), android.util.Base64.NO_WRAP)

    private fun rawSelectionPrefs() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE)

    // Drives the production hand-off: the prepared start goes through VpnManager.startVpn into an
    // ACTION_START intent, the service resolves the recorded IP from that intent (resolveStartIp)
    // and aligns/records the start, and the success is persisted from the recorded start.
    private fun connectLikeTheServiceDoes(selected: MainSelectedServer) {
        val ctx = RuntimeEnvironment.getApplication()
        val prepared = interactor.prepareStart(selected, preferUserSelection = false)!!
        VpnManager.startVpn(ctx, prepared.config, prepared.country, ip = prepared.ip)
        val intent = Shadows.shadowOf(ctx).nextStartedService
        // Android's strict Base64 rejects plain config text and VpnManager then forwards it as is;
        // Robolectric's lenient decoder would turn it into garbage, so model the device behaviour.
        val config = if (prepared.config.any { it.isWhitespace() }) prepared.config
        else intent.getStringExtra(VpnManager.extraConfigKey(ctx))!!
        val targetIp = OpenVpnService.resolveStartIp(ctx, config, intent.getStringExtra(VpnManager.extraIpKey(ctx)))
        SelectedCountryStore.saveLastStartedConfig(ctx, prepared.country, config, targetIp)
        val last = SelectedCountryStore.getLastStartedConfig(ctx)!!
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, last.country, last.config!!, last.ip, alignIndex = false)
    }

    // A refresh removed the last successful server X; a surviving same-IP entry Y has another endpoint
    // and the cursor is parked on P (different IP). Connect must not adopt Y as the started server.
    private fun connectAfterLastSuccessfulServerRemoved(autoSwitch: Boolean) {
        val ctx = RuntimeEnvironment.getApplication()
        com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
            .saveAutoSwitchWithinCountry(ctx, autoSwitch)
        val xText = joinText(1194, "1.1.1.1")
        val y = makeServer(joinEncoded(joinText(443, "1.1.1.1")), "1.1.1.1")
        val p = makeServer(joinEncoded(joinText(1194, "2.2.2.2")), "2.2.2.2")
        SelectedCountryStore.saveSelection(ctx, "Belarus", listOf(makeServer(joinEncoded(xText), "1.1.1.1"), y, p))
        SelectedCountryStore.setCurrentIndex(ctx, 0)
        SelectedCountryStore.saveLastStartedConfig(ctx, "Belarus", xText, "1.1.1.1")
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, "Belarus", xText, "1.1.1.1", alignIndex = false)
        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "Belarus", listOf(y, p))

        connectLikeTheServiceDoes(makeSelectedServer(config = joinEncoded(xText), ip = "1.1.1.1"))
    }

    private fun assertStartedAndSuccessIpAreTheRemovedServers() {
        assertEquals("1.1.1.1", rawSelectionPrefs().getString("last_started_ip", null))
        assertEquals("1.1.1.1", rawSelectionPrefs().getString("last_success_ip", null))
    }

    private fun assertNoIdentityWasCaptured() {
        assertEquals("", rawSelectionPrefs().getString("last_started_server_key", null))
        assertNull(rawSelectionPrefs().getString("last_success_server_key", null))
        assertNull(SelectedCountryStore.currentAttemptedServerKey(RuntimeEnvironment.getApplication()))
    }

    @Test
    fun prepareStart_removedLastSuccessfulServerWithSameIpSurvivor_capturesNoIdentity_autoSwitchOn() {
        connectAfterLastSuccessfulServerRemoved(autoSwitch = true)
        assertNoIdentityWasCaptured()
        assertStartedAndSuccessIpAreTheRemovedServers()
    }

    @Test
    fun prepareStart_removedLastSuccessfulServerWithSameIpSurvivor_capturesNoIdentity_autoSwitchOff() {
        connectAfterLastSuccessfulServerRemoved(autoSwitch = false)
        assertNoIdentityWasCaptured()
        assertStartedAndSuccessIpAreTheRemovedServers()
    }

    @Test
    fun prepareStart_lastSuccessfulServerStillPresent_capturesItsIdentity() {
        val ctx = RuntimeEnvironment.getApplication()
        val yText = joinText(443, "1.1.1.1")
        val x = makeServer(joinEncoded(joinText(1194, "1.1.1.1")), "1.1.1.1")
        val y = makeServer(joinEncoded(yText), "1.1.1.1")
        SelectedCountryStore.saveSelection(ctx, "Belarus", listOf(x, y))
        SelectedCountryStore.setCurrentIndex(ctx, 1)
        SelectedCountryStore.saveLastStartedConfig(ctx, "Belarus", yText, "1.1.1.1")
        SelectedCountryStore.saveLastSuccessfulConfig(ctx, "Belarus", yText, "1.1.1.1", alignIndex = false)
        SelectedCountryStore.setCurrentIndex(ctx, 0)

        connectLikeTheServiceDoes(makeSelectedServer(config = joinEncoded(yText), ip = "1.1.1.1"))

        val yKey = SelectedCountryStore.currentServerKeys(ctx)[1]
        assertEquals(yKey, rawSelectionPrefs().getString("last_started_server_key", null))
        assertEquals(yKey, rawSelectionPrefs().getString("last_success_server_key", null))
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
