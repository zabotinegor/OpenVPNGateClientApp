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
    fun syncStatusActionExitsControllerForegroundWhenDisconnected() {
        val app: Application = RuntimeEnvironment.getApplication()
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = shadowOf(notificationManager)

        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        simulateEnteredControllerForeground(service)
        assertFalse("Precondition: notification must be posted", shadowNotificationManager.allNotifications.isEmpty())

        val intent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(intent, 0, 1)

        assertTrue("exitControllerForeground must remove the notification when VPN is disconnected",
            shadowNotificationManager.allNotifications.isEmpty())
    }

    @Test
    fun syncStatusActionDoesNotExitControllerForegroundWhenVpnActive() {
        val app: Application = RuntimeEnvironment.getApplication()
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = shadowOf(notificationManager)

        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        simulateEnteredControllerForeground(service)
        assertFalse("Precondition: notification must be posted", shadowNotificationManager.allNotifications.isEmpty())

        val intent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(intent, 0, 1)

        assertFalse("exitControllerForeground must NOT be called while VPN is active (CONNECTING)",
            shadowNotificationManager.allNotifications.isEmpty())
    }

    // enterControllerForeground() fails silently in Robolectric (no notification channel
    // infrastructure). Bypass it by setting the flag and posting the notification directly
    // so that stopForeground() removal is observable in allNotifications.
    @Suppress("DEPRECATION")
    private fun simulateEnteredControllerForeground(service: OpenVpnService) {
        val field = OpenVpnService::class.java.getDeclaredField("controllerForegroundActive")
        field.isAccessible = true
        field.setBoolean(service, true)
        service.startForeground(TEST_FOREGROUND_NOTIFICATION_ID, android.app.Notification())
    }

    companion object {
        private const val TEST_FOREGROUND_NOTIFICATION_ID = 9001
    }

    // Structural tests: verify that fields accessed from both the main thread and the AIDL binder
    // thread carry the @Volatile annotation so that JVM memory-visibility is guaranteed.
    // controllerForegroundActive was marked @Volatile in round 4; userInitiatedStart,
    // userInitiatedStop, and ignoreConnectedUntilNotConnected were marked @Volatile in round 5.

    @Test
    fun controllerForegroundActive_isVolatile() {
        val field = OpenVpnService::class.java.getDeclaredField("controllerForegroundActive")
        assertTrue(
            "controllerForegroundActive must be @Volatile for cross-thread visibility " +
                "(main thread writes; AIDL binder thread reads in syncEngineState)",
            java.lang.reflect.Modifier.isVolatile(field.modifiers)
        )
    }

    @Test
    fun userInitiatedStart_isVolatile() {
        val field = OpenVpnService::class.java.getDeclaredField("userInitiatedStart")
        assertTrue(
            "userInitiatedStart must be @Volatile for cross-thread visibility " +
                "(main thread writes in ACTION_START; AIDL binder thread reads in syncEngineState)",
            java.lang.reflect.Modifier.isVolatile(field.modifiers)
        )
    }

    @Test
    fun userInitiatedStop_isVolatile() {
        val field = OpenVpnService::class.java.getDeclaredField("userInitiatedStop")
        assertTrue(
            "userInitiatedStop must be @Volatile for cross-thread visibility " +
                "(main thread writes; AIDL binder thread reads in handleEngineLevelForStop)",
            java.lang.reflect.Modifier.isVolatile(field.modifiers)
        )
    }

    @Test
    fun ignoreConnectedUntilNotConnected_isVolatile() {
        val field = OpenVpnService::class.java.getDeclaredField("ignoreConnectedUntilNotConnected")
        assertTrue(
            "ignoreConnectedUntilNotConnected must be @Volatile for cross-thread visibility " +
                "(main thread writes; AIDL binder thread reads/writes in shouldIgnoreLevelAfterUserStop)",
            java.lang.reflect.Modifier.isVolatile(field.modifiers)
        )
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
