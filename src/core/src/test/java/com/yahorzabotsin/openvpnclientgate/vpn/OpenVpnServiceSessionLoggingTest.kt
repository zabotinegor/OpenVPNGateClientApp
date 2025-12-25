package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
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

    private fun saveServers(count: Int) {
        val list = (1..count).map { idx ->
            Server(
                lineIndex = idx,
                name = "n$idx",
                city = "c$idx",
                country = Country("RU"),
                ping = 0,
                signalStrength = SignalStrength.STRONG,
                ip = "0.0.0.$idx",
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

        // Move index to the last server so that nextServer() returns null
        repeat(9) { SelectedCountryStore.nextServer(appContext) }
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

