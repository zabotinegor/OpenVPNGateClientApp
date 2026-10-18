package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [27])
class OpenVpnServiceSessionLoggingTest {

    private val appContext = RuntimeEnvironment.getApplication()
    private val logTag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ":" + "OpenVpnService"

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
    }

    private fun saveServers(count: Int, sameIp: Boolean = false) {
        val list = (1..count).map { idx ->
            Server(
                lineIndex = idx,
                name = "n$idx",
                city = "c$idx",
                country = Country("RU"),
                ping = 0,
                signalStrength = SignalStrength.STRONG,
                ip = if (sameIp) "0.0.0.1" else "0.0.0.$idx",
                score = 0,
                speed = 0,
                numVpnSessions = 0,
                uptime = 0,
                totalUsers = 0,
                totalTraffic = 0,
                logType = "",
                operator = "",
                message = "",
                configData = "conf$idx"
            )
        }
        SelectedCountryStore.saveSelection(appContext, "RU", list)
        SelectedCountryStore.resetIndex(appContext)
    }

    @Test
    fun logsSessionAttemptsAcrossReconnects() {
        saveServers(3)
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        // First manual start (dummy config to pass non-empty check)
        run {
            val intent = Intent(appContext, OpenVpnService::class.java).apply {
                putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
                putExtra(VpnManager.extraConfigKey(appContext), "client\n")
                putExtra(VpnManager.extraTitleKey(appContext), "RU")
            }
            service.onStartCommand(intent, 0, 1)
        }

        // Auto reconnect start
        run {
            val intent = Intent(appContext, OpenVpnService::class.java).apply {
                putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
                putExtra(VpnManager.extraAutoSwitchKey(appContext), true)
                putExtra(VpnManager.extraConfigKey(appContext), "client\n")
                putExtra(VpnManager.extraTitleKey(appContext), "RU")
            }
            service.onStartCommand(intent, 0, 2)
        }

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(logs.any { it.contains("Session attempt 1 (serversInCountry=3") })
        assertTrue(logs.any { it.contains("Session attempt 2 (serversInCountry=3") })
    }

    @Test
    fun reconnectStartKeepsSecondServerWhenConfigsDifferAndIpIsShared() {
        saveServers(2, sameIp = true)
        SelectedCountryStore.setCurrentIndex(appContext, 1)
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        val intent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraAutoSwitchKey(appContext), true)
            // The engine receives decoded config; the selected country stores another representation.
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }

        service.onStartCommand(intent, 0, 1)

        assertEquals(1, SelectedCountryStore.getCurrentIndex(appContext))
        assertEquals("conf2", SelectedCountryStore.currentServer(appContext)?.config)
        assertEquals("client\n", SelectedCountryStore.getLastStartedConfig(appContext)?.config)
        assertTrue(
            ShadowLog.getLogs().any {
                it.tag == logTag && it.msg.contains("server=2/2, ip=0.0.0.1")
            }
        )
    }

    @Test
    fun manualStartKeepsSecondServerWhenConfigsDifferAndIpIsShared() {
        saveServers(2, sameIp = true)
        SelectedCountryStore.setCurrentIndex(appContext, 1)
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        val intent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraAutoSwitchKey(appContext), false)
            // VpnManager.startVpn decodes the config; the store keeps the encoded representation.
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }

        service.onStartCommand(intent, 0, 1)

        assertEquals(1, SelectedCountryStore.getCurrentIndex(appContext))
        assertTrue(
            ShadowLog.getLogs().any {
                it.tag == logTag && it.msg.contains("server=2/2, ip=0.0.0.1")
            }
        )
    }

    private fun idServer(id: Int, config: String, ip: String) = Server(
        lineIndex = id, name = "n$config", city = "c$config", country = Country("RU"), ping = 0,
        signalStrength = SignalStrength.STRONG, ip = ip, score = 0, speed = 0, numVpnSessions = 0,
        uptime = 0, totalUsers = 0, totalTraffic = 0, logType = "", operator = "", message = "",
        configData = config, id = id
    )

    private fun reconnectStart(service: OpenVpnService, decodedConfig: String) {
        val intent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraAutoSwitchKey(appContext), true)
            putExtra(VpnManager.extraConfigKey(appContext), decodedConfig)
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(intent, 0, 1)
    }

    @Test
    fun reconnectStartOfCandidateRemovedBeforeActionStart_doesNotAttributeItToTheParkedEntry() {
        val a = idServer(1, "A", "1.1.1.1")
        val b = idServer(2, "B", "2.2.2.2")
        val c = idServer(3, "C", "3.3.3.3")
        SelectedCountryStore.saveSelection(appContext, "RU", listOf(a, b, c))
        SelectedCountryStore.resetIndex(appContext)
        ServerAutoSwitcher.resetForTest()
        try {
            // A failed: the auto-switch selects B and holds its config until the delayed ACTION_START.
            assertEquals("B", ServerAutoSwitcher.nextCycleCandidate(appContext)?.config)
            // A refresh removes B in that window: the index parks on A, which is only a cursor now.
            SelectedCountryStore.saveSelectionPreservingIndex(appContext, "RU", listOf(a, c))
            assertEquals("A", SelectedCountryStore.currentServer(appContext)?.config)

            val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
            // The engine is started with B's (decoded) config.
            reconnectStart(service, "decoded-B")

            org.junit.Assert.assertNull(
                "ACTION_START of the removed B must not make the parked A the attempted server",
                SelectedCountryStore.currentAttemptedServerKey(appContext)
            )
            assertEquals(
                "B's failure must not be probed as A",
                0, SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(appContext)
            )

            // B connects: the success must not record A's identity alongside B's config. This is the
            // persistence the AIDL LEVEL_CONNECTED callback runs.
            OpenVpnService::class.java.getDeclaredMethod("persistLastSuccessfulConfig")
                .apply { isAccessible = true }
                .invoke(service)
            assertEquals("decoded-B", SelectedCountryStore.getLastSuccessfulConfigForSelected(appContext))
            SelectedCountryStore.setCurrentIndex(appContext, 1)
            assertEquals(false, SelectedCountryStore.restoreLastSuccessfulServerIndex(appContext))
            assertEquals("C", SelectedCountryStore.currentServer(appContext)?.config)
        } finally {
            ServerAutoSwitcher.resetForTest()
            ConnectionStateManager.setReconnectingHint(false)
        }
    }

    @Test
    fun reconnectStartOfCandidateSurvivingARefresh_recordsThatCandidate() {
        val a = idServer(1, "A", "1.1.1.1")
        val b = idServer(2, "B", "2.2.2.2")
        val c = idServer(3, "C", "3.3.3.3")
        SelectedCountryStore.saveSelection(appContext, "RU", listOf(a, b, c))
        SelectedCountryStore.resetIndex(appContext)
        ServerAutoSwitcher.resetForTest()
        try {
            assertEquals("B", ServerAutoSwitcher.nextCycleCandidate(appContext)?.config)
            // The refresh reorders the pool but keeps B, so the index still identifies it.
            SelectedCountryStore.saveSelectionPreservingIndex(appContext, "RU", listOf(c, b, a))
            val bKey = SelectedCountryStore.currentServerKey(appContext)

            val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
            reconnectStart(service, "decoded-B")

            assertEquals(bKey, SelectedCountryStore.currentAttemptedServerKey(appContext))
            assertEquals(2, SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(appContext))
            assertEquals("counter follows the started B", 2 to 3, SelectedCountryStore.getCurrentPosition(appContext))
        } finally {
            ServerAutoSwitcher.resetForTest()
            ConnectionStateManager.setReconnectingHint(false)
        }
    }

    @Test
    fun logsExhaustedListWhenNoAlternative() {
        saveServers(1)
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        val start = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(start, 0, 1)

        // Engine reports a failure level; since only 1 server, nextServer() is null
        service.updateState(null, null, 0, ConnectionStatus.LEVEL_AUTH_FAILED, null)

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(logs.any { it.contains("Exhausted server list without success") && it.contains("1 attempts (serversInCountry=1)") })
    }

    @Test
    fun fallbackExhaustion_doesNotLeaveSeededCycleBehind() {
        saveServers(1)
        ServerAutoSwitcher.resetForTest()
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        val start = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(start, 0, 1)

        service.updateState(null, null, 0, ConnectionStatus.LEVEL_AUTH_FAILED, null)

        org.junit.Assert.assertNull(ServerAutoSwitcher.cycleVisitedForTest())
    }

    @Test
    fun fallbackFailureOnLastServer_wrapsToFirstServerWithMatchingCounter() {
        saveServers(3)
        SelectedCountryStore.setCurrentIndex(appContext, 2)
        ServerAutoSwitcher.resetForTest()
        ConnectionStateManager.setReconnectingHint(false)
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        val start = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        try {
            service.onStartCommand(start, 0, 1)

            service.updateState(null, null, 0, ConnectionStatus.LEVEL_AUTH_FAILED, null)

            val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
            assertTrue(logs.any { it.contains("Auto-switching to next server in country list: RU -> c1 (server=1/3") })
            assertTrue(logs.none { it.contains("Exhausted server list without success") })
            assertEquals(1 to 3, SelectedCountryStore.getCurrentPosition(appContext))
        } finally {
            ServerAutoSwitcher.resetForTest()
            ConnectionStateManager.setReconnectingHint(false)
        }
    }

    private fun startAttempt(service: OpenVpnService) {
        val start = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(start, 0, 1)
    }

    private fun reportFromBackgroundThread(service: OpenVpnService, level: ConnectionStatus) {
        val worker = Thread { service.updateState(null, null, 0, level, null) }
        worker.start()
        worker.join(5_000)
        org.junit.Assert.assertFalse("callback thread did not finish", worker.isAlive)
    }

    @Test
    fun fallbackCallbackOffTheMainThread_selectsAndSwitchesOnlyOnTheMainLooper() {
        saveServers(3)
        ServerAutoSwitcher.resetForTest()
        ConnectionStateManager.setReconnectingHint(false)
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        try {
            startAttempt(service)
            ShadowLooper.idleMainLooper()
            ShadowLog.clear()

            reportFromBackgroundThread(service, ConnectionStatus.LEVEL_AUTH_FAILED)

            org.junit.Assert.assertNull(
                "the switch cycle must not be touched from the callback thread",
                ServerAutoSwitcher.cycleVisitedForTest()
            )
            assertEquals(1 to 3, SelectedCountryStore.getCurrentPosition(appContext))

            ShadowLooper.idleMainLooper()

            assertEquals(2 to 3, SelectedCountryStore.getCurrentPosition(appContext))
            val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
            assertTrue(logs.any { it.contains("Auto-switching to next server in country list: RU -> c2 (server=2/3") })
        } finally {
            ServerAutoSwitcher.resetForTest()
            ConnectionStateManager.setReconnectingHint(false)
        }
    }

    // A timed (main-looper) switch and a fallback callback from another thread used to select against the
    // same not-yet-updated visited set and both picked the same server. Serialized onto the main looper,
    // the queued fallback sees the server the timed switch already attempted and moves on to the next one.
    @Test
    fun overlappingTimedSwitchAndOffThreadFallback_pickDistinctServers() {
        saveServers(3)
        ServerAutoSwitcher.resetForTest()
        ConnectionStateManager.setReconnectingHint(false)
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        try {
            startAttempt(service)
            ShadowLooper.idleMainLooper()
            ShadowLog.clear()

            reportFromBackgroundThread(service, ConnectionStatus.LEVEL_AUTH_FAILED)
            // The timed path wins the race on the main looper.
            assertEquals("c2", ServerAutoSwitcher.nextCycleCandidate(appContext)?.city)

            ShadowLooper.idleMainLooper()

            val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
            assertTrue(logs.any { it.contains("RU -> c3 (server=3/3") })
            assertTrue(logs.none { it.contains("RU -> c2 ") })
            assertEquals(3 to 3, SelectedCountryStore.getCurrentPosition(appContext))
        } finally {
            ServerAutoSwitcher.resetForTest()
            ConnectionStateManager.setReconnectingHint(false)
        }
    }

    @Test
    fun queuedFallbackCallback_isDroppedWhenTheServiceIsDestroyedBeforeItRuns() {
        saveServers(3)
        ServerAutoSwitcher.resetForTest()
        ConnectionStateManager.setReconnectingHint(false)
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        try {
            startAttempt(service)
            ShadowLooper.idleMainLooper()
            reportFromBackgroundThread(service, ConnectionStatus.LEVEL_AUTH_FAILED)

            controller.destroy()
            ShadowLooper.idleMainLooper()

            org.junit.Assert.assertNull(ServerAutoSwitcher.cycleVisitedForTest())
        } finally {
            ServerAutoSwitcher.resetForTest()
            ConnectionStateManager.setReconnectingHint(false)
        }
    }

    @Test
    fun logsConnectedAfterNthAttempt() {
        saveServers(2)
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        // First attempt
        val start1 = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(start1, 0, 1)

        // Second attempt (reconnect)
        val start2 = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraAutoSwitchKey(appContext), true)
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(start2, 0, 2)

        // Now engine connects
        service.updateState(null, null, 0, ConnectionStatus.LEVEL_CONNECTED, null)
        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(logs.any { it.contains("Connected after attempt 2 (serversInCountry=2)") })
    }

    @Test
    fun countsSavedConfigPlusAllServersWhenExhausted() {
        saveServers(10)
        SelectedCountryStore.saveLastSuccessfulConfig(appContext, "RU", "saved-config")

        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        // First attempt: start with saved config
        val first = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(appContext), "saved-config")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(first, 0, 1)

        // Next 10 attempts: auto-switch reconnects
        repeat(10) { idx ->
            val reconnect = Intent(appContext, OpenVpnService::class.java).apply {
                putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
                putExtra(VpnManager.extraAutoSwitchKey(appContext), true)
                putExtra(VpnManager.extraConfigKey(appContext), "conf${idx + 1}")
                putExtra(VpnManager.extraTitleKey(appContext), "RU")
            }
            service.onStartCommand(reconnect, 0, idx + 2)
        }

        // Every server has been attempted in this cycle, so the next lookup finds no candidate.
        ServerAutoSwitcher.resetForTest()
        repeat(9) { ServerAutoSwitcher.nextCycleCandidate(appContext) }
        ConnectionStateManager.setReconnectingHint(false)

        service.updateState(null, null, 0, ConnectionStatus.LEVEL_AUTH_FAILED, null)

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(
            logs.any {
                it.contains("Exhausted server list without success") &&
                    it.contains("11 attempts (serversInCountry=10)")
            }
        )
    }
}

