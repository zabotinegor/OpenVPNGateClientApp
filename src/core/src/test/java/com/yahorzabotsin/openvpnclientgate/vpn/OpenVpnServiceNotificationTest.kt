package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class OpenVpnServiceNotificationTest {

    @Before
    fun resetState() {
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    @Test
    fun updateStateDoesNotPostControllerForegroundNotification() {
        val app: Application = RuntimeEnvironment.getApplication()
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = shadowOf(notificationManager)

        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()

        service.updateState("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, Intent())

        assertTrue(shadowNotificationManager.allNotifications.isEmpty())
    }

    @Test
    fun stopIfIdleActionStopsService() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        val intent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_STOP_IF_IDLE)
        }

        service.onStartCommand(intent, 0, 1)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun syncStatusActionWaitsForInitialStateThenStopsOnTimeout() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()

        val intent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }

        service.onStartCommand(intent, 0, 1)
        Shadows.shadowOf(service.mainLooper).idleFor(3, java.util.concurrent.TimeUnit.SECONDS)
        assertFalse(shadowOf(service).isStoppedBySelf)
        Shadows.shadowOf(service.mainLooper).idleFor(13, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
