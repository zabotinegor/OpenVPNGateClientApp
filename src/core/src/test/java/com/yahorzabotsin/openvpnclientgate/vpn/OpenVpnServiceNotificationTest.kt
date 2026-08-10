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

    // Regression test for Round 6 Thread 1 (PRRT_kwDOONeEXM6MYCVc):
    // syncEngineState() must clear userInitiatedStart when LEVEL_CONNECTED arrives via the AIDL
    // path. Without this, a server-drop disconnect after a successful connect leaves
    // userInitiatedStart=true, causing the FGS guard to hold "VPN connecting" indefinitely.
    // We invoke syncEngineState() directly via reflection to test the AIDL path in isolation,
    // bypassing the suppressEngineState guard which only affects the VPN_STATUS path.
    @Test
    fun syncEngineState_clearsUserInitiatedStart_onLevelConnected() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()

        // Simulate a user-initiated connect that set userInitiatedStart=true
        val userInitiatedStartField = OpenVpnService::class.java.getDeclaredField("userInitiatedStart")
        userInitiatedStartField.isAccessible = true
        userInitiatedStartField.setBoolean(service, true)

        // Invoke syncEngineState() directly — this mirrors the AIDL binder thread path
        // (updateStateString → syncEngineState), bypassing suppressEngineState which belongs
        // to the VPN_STATUS path only.
        val syncEngineStateMethod = OpenVpnService::class.java.getDeclaredMethod(
            "syncEngineState",
            ConnectionStatus::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType
        )
        syncEngineStateMethod.isAccessible = true
        syncEngineStateMethod.invoke(service, ConnectionStatus.LEVEL_CONNECTED, "CONNECTED", false)

        assertFalse(
            "userInitiatedStart must be cleared when LEVEL_CONNECTED arrives in syncEngineState; " +
                "otherwise a subsequent server-drop disconnect leaves the FGS stuck showing " +
                "'VPN connecting' indefinitely (reconnect guard incorrectly fires)",
            userInitiatedStartField.getBoolean(service)
        )
    }

    // Regression test for the RemoteServiceException$ForegroundServiceDidNotStartInTimeException
    // crash (ClickUp 86cb35fbt): an OpenVpnService instance created via ACTION_SYNC_STATUS sets
    // controllerForegroundActive=true through onCreate()'s eager enterControllerForeground() call.
    // If that same instance later receives a genuine ACTION_START, enterControllerForeground() must
    // still perform a fresh startForeground() call rather than short-circuiting on the
    // already-true flag, or Android kills the app ~5s later for missing the foreground-service-start
    // timing requirement.
    //
    // NOTE: under Robolectric, the real NotificationCompat.Builder(...).build() call inside
    // enterControllerForeground() throws (NoSuchMethodError on Notification$Builder.setShowWhen)
    // due to an AndroidX/Robolectric shadow API-level mismatch unrelated to this fix — this is the
    // same reason the other tests in this file bypass the real method via
    // simulateEnteredControllerForeground() instead of invoking it directly. That mismatch is
    // actually useful here: it means we can distinguish "guard short-circuited, nothing happened"
    // (old buggy behavior: flag stays true, no notification, service not stopped) from "a fresh
    // attempt was made" (fixed behavior: either the notification gets (re)posted, or — as observed
    // in this test environment — the attempt fails and is handled via the existing catch block,
    // resetting the flag and calling stopSelf() since stopOnFailure=true for a real ACTION_START).
    // Either outcome proves the early-return guard no longer skips the call; only the old buggy
    // no-op leaves all three signals untouched.
    @Test
    fun startActionCallsStartForegroundAgainEvenWhenControllerForegroundAlreadyActive() {
        val app: Application = RuntimeEnvironment.getApplication()
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = shadowOf(notificationManager)

        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()

        // Simulate the earlier ACTION_SYNC_STATUS-triggered onCreate() having already left the
        // service in "controller foreground active" state, WITHOUT posting a notification here —
        // this isolates whether enterControllerForeground() itself attempts a fresh startForeground()
        // call when invoked again, as opposed to the test helper doing it for us.
        val activeField = OpenVpnService::class.java.getDeclaredField("controllerForegroundActive")
        activeField.isAccessible = true
        activeField.setBoolean(service, true)
        notificationManager.cancelAll()
        assertTrue("Precondition: no notification posted yet", shadowNotificationManager.allNotifications.isEmpty())
        assertFalse("Precondition: service not yet stopped", shadowOf(service).isStoppedBySelf)

        val method = OpenVpnService::class.java.getDeclaredMethod(
            "enterControllerForeground",
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true
        method.invoke(service, true)

        val notificationPosted = !shadowNotificationManager.allNotifications.isEmpty()
        val attemptFailedAndWasHandled = !activeField.getBoolean(service) && shadowOf(service).isStoppedBySelf

        assertTrue(
            "enterControllerForeground() must attempt a fresh startForeground() call even when " +
                "controllerForegroundActive was already true; it must not silently short-circuit and " +
                "return without doing anything. Expected either a (re)posted notification " +
                "(notificationPosted=$notificationPosted) or a handled failed attempt " +
                "(flag reset + stopSelf, attemptFailedAndWasHandled=$attemptFailedAndWasHandled) -- " +
                "neither happened, meaning the early-return guard skipped the call",
            notificationPosted || attemptFailedAndWasHandled
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
