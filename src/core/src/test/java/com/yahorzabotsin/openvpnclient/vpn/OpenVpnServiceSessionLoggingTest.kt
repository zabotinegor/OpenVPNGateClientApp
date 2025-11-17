package com.yahorzabotsin.openvpnclient.vpn

import android.content.Intent
import com.yahorzabotsin.openvpnclient.core.servers.Country
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclient.core.servers.Server
import com.yahorzabotsin.openvpnclient.core.servers.SignalStrength
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenVpnServiceSessionLoggingTest {

    private val appContext = RuntimeEnvironment.getApplication()

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

        val logs = ShadowLog.getLogs().filter { it.tag == "OpenVpnService" }.map { it.msg }
        assertTrue(logs.any { it.contains("Session attempt 1/3") })
        assertTrue(logs.any { it.contains("Session attempt 2/3") })
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

        val logs = ShadowLog.getLogs().filter { it.tag == "OpenVpnService" }.map { it.msg }
        assertTrue(logs.any { it.contains("Exhausted server list without success") && it.contains("1/1") })
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
        EngineStatusReceiver().onReceive(
            appContext,
            Intent("de.blinkt.openvpn.VPN_STATUS").apply {
                putExtra("status", ConnectionStatus.LEVEL_CONNECTED.name)
            }
        )

        val logs = ShadowLog.getLogs().filter { it.tag == "OpenVpnService" }.map { it.msg }
        assertTrue(logs.any { it.contains("Connected after attempt 2/2") })

        val lastConfig = SelectedCountryStore.getLastSuccessfulConfigForSelected(appContext)
        assertEquals("client\n", lastConfig)
    }
}
