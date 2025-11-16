package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.content.Intent
import com.yahorzabotsin.openvpnclient.core.servers.Country
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclient.core.servers.Server
import com.yahorzabotsin.openvpnclient.core.servers.SignalStrength
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EngineStatusReceiverTest {

    private lateinit var ctx: Context
    private lateinit var receiver: EngineStatusReceiver

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        receiver = EngineStatusReceiver()

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
                configData = "configA1"
            )
        )

        SelectedCountryStore.saveSelection(ctx, "CountryA", servers)
        SelectedCountryStore.resetIndex(ctx)
    }

    @Test
    fun savesLastSuccessfulConfigOnConnectedBroadcast() {
        val intent = Intent("de.blinkt.openvpn.VPN_STATUS").apply {
            putExtra("status", ConnectionStatus.LEVEL_CONNECTED.name)
            putExtra("detailstatus", "CONNECTED")
        }

        receiver.onReceive(ctx, intent)

        val stored = SelectedCountryStore.getLastSuccessfulConfigForSelected(ctx)
        assertEquals("configA1", stored)
    }
}

