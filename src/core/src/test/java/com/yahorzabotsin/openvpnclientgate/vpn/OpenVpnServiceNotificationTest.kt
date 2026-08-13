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
import org.robolectric.annotation.Config

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
    // The ACTION_SYNC_STATUS handler only exits controller-foreground when the VPN is DISCONNECTED
    // (see syncStatusActionDoesNotExitControllerForegroundWhenVpnActive above), so an instance that
    // is mid-connection keeps controllerForegroundActive=true across the sync. If that same instance
    // later receives a genuine ACTION_START, enterControllerForeground() must still perform a fresh
    // startForeground() call rather than short-circuiting on the already-true flag, or Android kills
    // the app ~5s later for missing the foreground-service-start timing requirement.
    //
    // sdk = [27]: the real NotificationCompat.Builder(...).build() call inside
    // enterControllerForeground() throws NoSuchMethodError on the project's default Robolectric SDK
    // (an AndroidX-core/Robolectric shadow-jar mismatch unrelated to this fix). Pinning sdk=27 lets
    // the real notification path run so a freshly (re)posted notification is directly observable.
    @Config(sdk = [27])
    @Test
    fun startActionCallsStartForegroundAgainEvenWhenControllerForegroundAlreadyActive() {
        val app: Application = RuntimeEnvironment.getApplication()
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = shadowOf(notificationManager)

        // Keep the VPN "active" (not DISCONNECTED) so the ACTION_SYNC_STATUS handler does NOT call
        // exitControllerForeground() -- this reproduces an ACTION_SYNC_STATUS-created instance that
        // still has controllerForegroundActive=true when a later genuine ACTION_START arrives.
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()

        val syncIntent = Intent(app, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(app), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val activeField = OpenVpnService::class.java.getDeclaredField("controllerForegroundActive")
        activeField.isAccessible = true
        assertTrue(
            "Precondition: controllerForegroundActive must still be true after ACTION_SYNC_STATUS " +
                "while the VPN is active (not DISCONNECTED)",
            activeField.getBoolean(service)
        )

        // Clear the notification that onCreate()/ACTION_SYNC_STATUS already posted, so that only a
        // FRESH startForeground() call triggered by the upcoming ACTION_START can make it reappear.
        notificationManager.cancelAll()
        assertTrue(
            "Precondition: no notification posted yet",
            shadowNotificationManager.allNotifications.isEmpty()
        )

        val startIntent = Intent(app, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(app), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(app), "client\n")
            putExtra(VpnManager.extraTitleKey(app), "RU")
        }
        service.onStartCommand(startIntent, 0, 2)

        assertTrue(
            "enterControllerForeground() must (re)post the controller foreground notification when " +
                "a genuine ACTION_START arrives, even though controllerForegroundActive was already " +
                "true from an earlier ACTION_SYNC_STATUS; otherwise Android kills the app for " +
                "missing the foreground-service-start timing requirement",
            shadowNotificationManager.allNotifications.isNotEmpty()
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

    // Regression tests for the second stopAfterOneShotSyncRunnable race (ClickUp 86cb35fbt,
    // fix-cycle 2): stopAfterOneShotSyncRunnable used to call stopSelf() directly once its own
    // guard checks passed. Android's main-thread looper is strictly serial, so if a genuine
    // ACTION_START (from ContextCompat.startForegroundService(), user tapping Connect) was
    // enqueued to arrive at nearly the same wall-clock moment this runnable's own
    // ONE_SHOT_STOP_DELAY_MS timer was due, whichever message the looper processed first won --
    // if the stop runnable won, ACTION_START's removeCallbacks(stopAfterOneShotSyncRunnable)
    // arrived too late to cancel it, and stopSelf() could tear the instance down out from under
    // a startForeground() that had already succeeded moments later, producing
    // RemoteServiceException$ForegroundServiceDidNotStartInTimeException on the OS side. Device
    // reproduced at a ~1058ms gap; see
    // docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-qa.md ADDENDUM.
    //
    // The fix defers the actual stopSelf() by ONE_SHOT_STOP_CONFIRM_DELAY_MS (400ms) into a
    // second runnable (stopAfterOneShotSyncConfirmedRunnable) that re-runs the same guard checks
    // immediately before firing. These two tests invoke the private
    // onOneShotInitialStateSynced(reason) hand-off directly to isolate the stop-buffer timing
    // under test, matching the pattern already used elsewhere in this file for private-method
    // access via reflection.

    @Test
    fun oneShotSync_stopsAfterConfirmBuffer_whenUninterrupted() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        val syncIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val onInitialStateSynced = OpenVpnService::class.java.getDeclaredMethod(
            "onOneShotInitialStateSynced", String::class.java
        )
        onInitialStateSynced.isAccessible = true
        onInitialStateSynced.invoke(service, "test")

        // Stage 1 (the original ONE_SHOT_STOP_DELAY_MS decision) fires here but must NOT stop
        // the service directly -- it only schedules the buffered confirmation.
        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(
            "stopSelf() must not fire until the confirm buffer elapses and re-validates",
            shadowOf(service).isStoppedBySelf
        )

        // Stage 2 (the confirm buffer) elapses with nothing having interrupted the decision --
        // the legitimate "just checking status, nothing else happening" cleanup path must still
        // complete within a bound close to the original ~1000ms (now ~1000ms + buffer).
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(
            "Legitimate idle one-shot-sync cleanup must still stop the service after the buffer",
            shadowOf(service).isStoppedBySelf
        )
    }

    @Test
    fun oneShotSync_abortsBufferedStop_whenActionStartInterruptsDuringBuffer() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        val syncIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val onInitialStateSynced = OpenVpnService::class.java.getDeclaredMethod(
            "onOneShotInitialStateSynced", String::class.java
        )
        onInitialStateSynced.isAccessible = true
        onInitialStateSynced.invoke(service, "test")

        // Stage 1 fires and schedules the buffered confirmation, exactly like the control case.
        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(shadowOf(service).isStoppedBySelf)

        // Simulate the state a genuine ACTION_START sets very early in onStartCommand --
        // WITHOUT going through onStartCommand's own removeCallbacks() cancellation -- to
        // isolate the buffered re-check itself as the safety net, independent of whether the
        // cancellation call wins its own race against an already-fired stage-1 runnable (that
        // race losing is the original bug this fix closes).
        val userInitiatedStartField = OpenVpnService::class.java.getDeclaredField("userInitiatedStart")
        userInitiatedStartField.isAccessible = true
        userInitiatedStartField.setBoolean(service, true)

        // The confirm buffer elapses; the re-check must see userInitiatedStart=true and abort.
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(
            "Buffered re-check must abort stopSelf() when a genuine ACTION_START set " +
                "userInitiatedStart=true during the confirm buffer, even if the cancellation " +
                "call itself lost its race against the already-fired stage-1 runnable",
            shadowOf(service).isStoppedBySelf
        )
    }
}
