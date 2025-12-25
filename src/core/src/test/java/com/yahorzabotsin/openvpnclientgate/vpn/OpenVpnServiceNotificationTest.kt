package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import android.os.Looper
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IStatusCallbacks
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class OpenVpnServiceNotificationTest {

    @Test
    fun updateStateStopsForegroundWhenConnectedAndRestartsWhenDisconnected() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()

        assertTrue(service.isForegroundStarted())

        service.updateState("CONNECTED", null, 0, ConnectionStatus.LEVEL_CONNECTED, Intent())
        assertFalse(service.isForegroundStarted())

        service.updateState("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, Intent())
        assertTrue(service.isForegroundStarted())
    }

    @Test
    fun aidlStatusUpdatesToggleForegroundForDisconnectAndConnect() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        val callbacks = service.statusCallbacks()

        service.updateState("CONNECTED", null, 0, ConnectionStatus.LEVEL_CONNECTED, Intent())
        assertFalse(service.isForegroundStarted())

        callbacks.updateStateString("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, Intent())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(service.isForegroundStarted())

        callbacks.updateStateString("START", null, 0, ConnectionStatus.LEVEL_START, Intent())
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(service.isForegroundStarted())
    }

    private fun OpenVpnService.isForegroundStarted(): Boolean {
        val field = OpenVpnService::class.java.getDeclaredField("foregroundStarted")
        field.isAccessible = true
        return field.getBoolean(this)
    }

    private fun OpenVpnService.statusCallbacks(): IStatusCallbacks {
        val field = OpenVpnService::class.java.getDeclaredField("statusCallbacks")
        field.isAccessible = true
        return field.get(this) as IStatusCallbacks
    }
}
