package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.content.Intent
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
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
    }

    @Test
    fun savesLastSuccessfulConfigFromLastStartedOnConnected() {
        SelectedCountryStore.saveLastStartedConfig(ctx, "CountryA", "confA1")

        val broadcast = Intent("de.blinkt.openvpn.VPN_STATUS").apply {
            putExtra("status", ConnectionStatus.LEVEL_CONNECTED.name)
            putExtra("detailstatus", "CONNECTED")
        }

        // Also mark current selected country so that last-success retrieval is scoped correctly
        ctx.getSharedPreferences("vpn_selection_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("selected_country", "CountryA")
            .apply()

        receiver.onReceive(ctx, broadcast)

        val stored = SelectedCountryStore.getLastSuccessfulConfigForSelected(ctx)
        assertEquals("confA1", stored)
    }
}

