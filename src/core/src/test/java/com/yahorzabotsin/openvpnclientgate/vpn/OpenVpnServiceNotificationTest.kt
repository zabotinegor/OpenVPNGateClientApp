package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class OpenVpnServiceNotificationTest {

    @Before
    fun resetState() {
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        VpnManager.resetActionStartDispatchTrackingForTest()
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

    // Regression tests for the QA-reproduced FATAL RemoteServiceException crash (fix-cycle 7,
    // docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-qa-2.md, "2026-08-14 continuation
    // 2"): device logs showed a fresh ACTION_START dispatch (VpnManager.startVpn(), a reconnect
    // tap) landing just 3ms before the buffered stopAfterOneShotSyncConfirmedRunnable re-check
    // fired -- well under real AMS/Binder Intent-delivery latency, so userInitiatedStart (set
    // inside onStartCommand) was still false when the re-check ran. This is a DISTINCT root cause
    // from review-7's R7-1 (ConnectionStateManager staleness): ConnectionStateManager.state is
    // genuinely DISCONNECTED throughout these tests, not stale -- the race is purely that AMS's
    // FGS-start obligation begins the instant startForegroundService() is CALLED, before this
    // process can possibly observe the Intent. The fix records the dispatch attempt synchronously
    // in VpnManager (hasRecentActionStartDispatch()) and checks it alongside userInitiatedStart.
    //
    // This models the gap directly: VpnManager.startVpn() is called (recording the dispatch) WITHOUT
    // driving the resulting Intent through onStartCommand() at all, isolating the new guard from
    // userInitiatedStart and from the real end-to-end ACTION_START path already covered by
    // oneShotSync_realActionStartThroughOnStartCommand_abortsBufferedStop below.
    @Test
    fun oneShotSync_abortsBufferedStop_whenActionStartDispatchIsStillInFlight() {
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

        // A fresh ACTION_START dispatch attempt lands (e.g. a reconnect tap), but its Intent is
        // deliberately NOT driven through onStartCommand() here -- modeling the still-in-flight
        // AMS/Binder gap the device crash exposed.
        val app = RuntimeEnvironment.getApplication()
        VpnManager.startVpn(app, "client\n", displayName = "RU")
        val userInitiatedStartField = OpenVpnService::class.java.getDeclaredField("userInitiatedStart")
        userInitiatedStartField.isAccessible = true
        assertFalse(
            "Precondition: the dispatched ACTION_START must not have reached onStartCommand() yet",
            userInitiatedStartField.getBoolean(service)
        )

        // The confirm buffer elapses; the re-check must see the recent dispatch and abort even
        // though userInitiatedStart is still false.
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(
            "Buffered re-check must abort stopSelf() when an ACTION_START dispatch was recently " +
                "issued via VpnManager, even before its Intent reaches onStartCommand() -- " +
                "otherwise stopSelf() races AMS's FGS-start obligation, which begins at dispatch " +
                "time, not at Intent-delivery time",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Falsifiability control for the test above: with no ACTION_START dispatch recorded at all,
    // the confirm buffer must still stop the service normally -- proves the new guard is a real
    // condition, not an always-true short-circuit.
    @Test
    fun oneShotSync_stopsAfterConfirmBuffer_whenNoRecentActionStartDispatch() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        VpnManager.resetActionStartDispatchTrackingForTest()

        val syncIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val onInitialStateSynced = OpenVpnService::class.java.getDeclaredMethod(
            "onOneShotInitialStateSynced", String::class.java
        )
        onInitialStateSynced.isAccessible = true
        onInitialStateSynced.invoke(service, "test")

        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(
            "Legitimate idle one-shot-sync cleanup must still stop the service when no " +
                "ACTION_START dispatch is in flight",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Regression tests for quality-gate finding G1 (fix-cycle 3, docs/qa-evidence/
    // 86cb35fbt-vpn-foreground-service-crash-gate-2.md): ONE_SHOT_STOP_CONFIRM_DELAY_MS alone only
    // relocates the AMS "bringing down service while still waiting for start foreground" crash
    // window from ~1000ms to ~1000-1400ms; it does not remove it, because the race is against a
    // genuine ACTION_START that can land at any point on that timeline. There are TWO production
    // ACTION_START dispatchers (review-4 F1/F2, docs/qa-evidence/
    // 86cb35fbt-vpn-foreground-service-crash-review-4.md), and each is excluded by a different
    // mechanism: isAppForegroundVisible() refuses stopSelf() outright while any activity is
    // started, excluding a human Connect tap (VpnManager.startVpn()'s MainActivityCore.kt caller),
    // with appLifecycleObserver reaping the deferred stop via the pre-existing, already-tested
    // ACTION_STOP_IF_IDLE path once the UI actually leaves the foreground; the
    // != ConnectionState.DISCONNECTED state guard excludes ServerAutoSwitcher's background
    // retry-timer dispatcher, which is not gated by UI visibility at all.

    // These two tests override appForegroundVisibleProvider by reflection rather than driving a
    // real Activity through Robolectric -- ProcessLifecycleOwner is a process-wide singleton whose
    // internal LifecycleRegistry is only wired to Activity callbacks via androidx-startup
    // initialization, which is not reliably active for a bare Robolectric-built Activity in this
    // module's test environment. The provider field is the same injectable-clock pattern already
    // used for watchdogNowMs/elapsedRealtimeMs elsewhere in this class.

    @Test
    fun oneShotSync_suppressesStopSelf_whileAppUiIsForeground() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ReflectionHelpers.setField(service, "appForegroundVisibleProvider", ({ true } as () -> Boolean))

        val syncIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val onInitialStateSynced = OpenVpnService::class.java.getDeclaredMethod(
            "onOneShotInitialStateSynced", String::class.java
        )
        onInitialStateSynced.isAccessible = true
        onInitialStateSynced.invoke(service, "test")

        // Run stage 1 and stage 2 all the way past their combined ~1400ms schedule -- with
        // pre-fix-cycle-3 code this would call stopSelf() here regardless of UI state.
        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertFalse(
            "stopSelf() must never fire from the one-shot sync path while an activity is " +
                "started -- that is exactly the condition under which a genuine ACTION_START " +
                "(human Connect tap) can be dispatched, and calling stopSelf() here is the AMS " +
                "bring-down race G1 requires closed structurally, not just narrowed",
            shadowOf(service).isStoppedBySelf
        )
    }

    @Test
    fun oneShotSync_stopsSelf_onceAppUiLeavesForegroundAfterSuppression() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ReflectionHelpers.setField(service, "appForegroundVisibleProvider", ({ true } as () -> Boolean))

        val syncIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val onInitialStateSynced = OpenVpnService::class.java.getDeclaredMethod(
            "onOneShotInitialStateSynced", String::class.java
        )
        onInitialStateSynced.isAccessible = true
        onInitialStateSynced.invoke(service, "test")

        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(
            "Precondition: stop must be suppressed while the app is foreground",
            shadowOf(service).isStoppedBySelf
        )

        // The UI leaves the foreground -- appLifecycleObserver.onStop() dispatches
        // ACTION_STOP_IF_IDLE via VpnManager.stopControllerIfIdle(), exactly as it does in
        // production (see appLifecycleObserver_onStop_dispatchesActionStopIfIdle above, which
        // isolates that dispatch), and the real ACTION_STOP_IF_IDLE branch in onStartCommand()
        // (see the pre-existing stopIfIdleActionStopsService test) performs the actual stop --
        // independent of the one-shot mechanism's own state, since it only checks
        // ConnectionStateManager directly.
        // Drain any unrelated started-service intents queued by the ACTION_SYNC_STATUS setup above
        // (e.g. bindStatusService()'s own service start) so nextStartedService below reliably picks
        // up the one appLifecycleObserver.onStop() dispatches, not an earlier unrelated entry.
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()

        val observerField = OpenVpnService::class.java.getDeclaredField("appLifecycleObserver")
        observerField.isAccessible = true
        val observer = observerField.get(service) as DefaultLifecycleObserver
        observer.onStop(ProcessLifecycleOwner.get())

        val reapIntent = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertTrue("Precondition: onStop() must dispatch ACTION_STOP_IF_IDLE", reapIntent != null)
        service.onStartCommand(reapIntent, 0, 2)

        assertTrue(
            "Leaving the foreground must reap a one-shot stop that was suppressed while " +
                "visible, via the pre-existing ACTION_STOP_IF_IDLE path -- otherwise a " +
                "suppressed idle controller would never be cleaned up",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Regression test for review-4 F1 (docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-
    // review-4.md): fix-cycle 3's isAppForegroundVisible() only excludes the UI ACTION_START
    // dispatcher. ServerAutoSwitcher's background retry timers are a SECOND, non-UI-gated
    // ACTION_START dispatcher, and the reviewer proved by executing a probe test that stopSelf()
    // still fired in that window under the fix-cycle-3 == CONNECTED guard alone. This models the
    // same scenario the probe used -- app backgrounded (appForegroundVisibleProvider = { false }),
    // reconnectingHint = true and state = CONNECTING (exactly what the auto-switch stop-to-start
    // gap looks like, per ConnectionState.kt's engine-idle-to-CONNECTING mapping while
    // reconnectingHint holds), userInitiatedStart left false (as OpenVpnService's own
    // AUTO_SWITCH_LEVELS handling leaves it during that gap) -- and asserts stopSelf() is now
    // suppressed by the fix-cycle-4 != DISCONNECTED state guard, independent of UI visibility.
    @Test
    fun oneShotSync_suppressesStopSelf_duringAutoSwitchGapWhileBackgrounded() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ReflectionHelpers.setField(service, "appForegroundVisibleProvider", ({ false } as () -> Boolean))

        val syncIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val onInitialStateSynced = OpenVpnService::class.java.getDeclaredMethod(
            "onOneShotInitialStateSynced", String::class.java
        )
        onInitialStateSynced.isAccessible = true
        onInitialStateSynced.invoke(service, "test")

        // Model the auto-switch stop-to-start gap: reconnectingHint holds the mapped state at
        // CONNECTING (not DISCONNECTED) for its whole duration, with no activity started.
        ConnectionStateManager.setReconnectingHint(true)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        // Run stage 1 and stage 2 all the way past their combined ~1400ms schedule -- under the
        // disproven fix-cycle-3 invariant this would call stopSelf() here despite the app being
        // backgrounded, exactly as PROBE-1 demonstrated.
        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertFalse(
            "stopSelf() must never fire from the one-shot sync path while ConnectionStateManager " +
                "is not DISCONNECTED -- that is the condition under which ServerAutoSwitcher's " +
                "background-timer ACTION_START dispatcher can fire, independent of app UI " +
                "visibility, and calling stopSelf() here is the review-4 F1 residual race",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Guard-isolating regression tests for review-5 R5-1 (docs/qa-evidence/
    // 86cb35fbt-vpn-foreground-service-crash-review-5.md): the test above proves at least ONE of
    // stage 1's and stage 2's != DISCONNECTED guards is tightened, but with state held constant
    // at CONNECTING across both deadlines, either guard alone reaches the same outcome as the
    // other -- reverting stage 1 alone still passes (stage 2 catches it) and reverting stage 2
    // alone also still passes (stage 1 already aborted before stage 2 could ever run), so neither
    // mutation is independently caught. These two tests change state BETWEEN stage 1's and stage
    // 2's deadlines instead of holding it constant, so each guard is evaluated against a state its
    // sibling guard never sees, and only the guard actually under test can prevent the wrong
    // outcome.

    // Isolates stage 1: state is CONNECTING when stage 1 runs (an intact guard aborts here,
    // meaning stage 2 is never even scheduled), then state changes to DISCONNECTED before stage
    // 2's deadline would arrive. If stage 1 failed to abort (its guard reverted), stage 2 WAS
    // scheduled and observes the now-genuinely-DISCONNECTED state, which even the fixed stage-2
    // guard does not block -- stopSelf() fires. Only stage 1's own guard can prevent that.
    @Test
    fun oneShotSync_stage1GuardAlone_preventsStage2FromActingOnLaterDisconnectedState() {
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

        // State is CONNECTING when stage 1's 1000ms deadline arrives.
        ConnectionStateManager.setReconnectingHint(true)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(
            "Precondition: stage 1 must not have called stopSelf() directly",
            shadowOf(service).isStoppedBySelf
        )

        // The auto-switch gap resolves into a genuine disconnect before stage 2's deadline. A
        // stage-2 confirmation that was never scheduled (because stage 1's own guard aborted)
        // cannot act on this -- there is nothing left pending to fire.
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertFalse(
            "stage 1's != DISCONNECTED guard must independently prevent scheduling stage 2 while " +
                "state is CONNECTING -- if it does not, a stage-2 confirmation left pending from " +
                "that moment can later act on a state it never actually observed during the " +
                "auto-switch gap (review-5 R5-1)",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Isolates stage 2: state is DISCONNECTED when stage 1 runs (an intact stage-1 guard passes
    // through normally and schedules stage 2, exactly like the ordinary idle-teardown path), then
    // the auto-switch gap begins -- state moves to CONNECTING -- during the confirmation buffer.
    // Only stage 2's own guard, re-evaluated immediately before stopSelf(), can catch this.
    @Test
    fun oneShotSync_stage2GuardAlone_abortsStopWhenAutoSwitchStartsDuringConfirmBuffer() {
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

        // State is genuinely DISCONNECTED when stage 1 runs -- it schedules stage 2 normally.
        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(shadowOf(service).isStoppedBySelf)

        // The auto-switch gap begins inside the confirmation buffer, before stage 2's deadline.
        ConnectionStateManager.setReconnectingHint(true)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertFalse(
            "stage 2's != DISCONNECTED guard must independently abort stopSelf() when the " +
                "auto-switch gap begins during the confirmation buffer, after stage 1 already " +
                "scheduled it against an earlier, genuinely-disconnected observation (review-5 " +
                "R5-1)",
            shadowOf(service).isStoppedBySelf
        )
    }

    @Test
    fun appLifecycleObserver_onStop_dispatchesActionStopIfIdle() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()

        val observerField = OpenVpnService::class.java.getDeclaredField("appLifecycleObserver")
        observerField.isAccessible = true
        val observer = observerField.get(service) as DefaultLifecycleObserver
        observer.onStop(ProcessLifecycleOwner.get())

        val started = Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        assertTrue(
            "appLifecycleObserver.onStop() must dispatch ACTION_STOP_IF_IDLE via " +
                "VpnManager.stopControllerIfIdle() so a one-shot stop suppressed while foreground " +
                "gets reaped once the UI goes away",
            started != null && started.getStringExtra(VpnManager.actionKey(service)) == VpnManager.ACTION_STOP_IF_IDLE
        )
    }

    @Test
    fun appLifecycleObserver_onStop_isNoOpWhileVpnActive() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()

        val observerField = OpenVpnService::class.java.getDeclaredField("appLifecycleObserver")
        observerField.isAccessible = true
        val observer = observerField.get(service) as DefaultLifecycleObserver
        observer.onStop(ProcessLifecycleOwner.get())

        assertNull(
            "Leaving the foreground must not touch an active (non-DISCONNECTED) controller " +
                "instance -- stopControllerIfIdle()'s own guard must still apply",
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).nextStartedService
        )
    }

    // Regression test for quality-gate finding G3: of the five cancellation sites for
    // stopAfterOneShotSyncConfirmedRunnable, scheduleOneShotStop()'s (~line 1041) is the only one
    // that is genuinely load-bearing -- it is the sole site that must cancel a stage-2 confirmation
    // left pending by a PREVIOUS scheduling cycle when the sync path is re-entered. The other four
    // sites are provably redundant with stage-2's own guards (ACTION_START/ACTION_STOP resetting
    // oneShotSyncRequested, onDestroy() tearing down the instance). This isolates that one site
    // directly, the same way onOneShotInitialStateSynced is already invoked directly elsewhere in
    // this file to isolate a single mechanism from the full onStartCommand() flow.
    @Test
    fun scheduleOneShotStop_cancelsStalePendingConfirmation_whenReScheduled() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        // Pin explicitly (review-4 F5) rather than relying on Robolectric leaving
        // ProcessLifecycleOwner at INITIALIZED -- this test is not about UI visibility.
        ReflectionHelpers.setField(service, "appForegroundVisibleProvider", ({ false } as () -> Boolean))

        val syncIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val onInitialStateSynced = OpenVpnService::class.java.getDeclaredMethod(
            "onOneShotInitialStateSynced", String::class.java
        )
        onInitialStateSynced.isAccessible = true
        onInitialStateSynced.invoke(service, "test")

        // t=1000ms: stage 1 fires, scheduling the STALE stage-2 confirmation for t=1400ms.
        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(shadowOf(service).isStoppedBySelf)

        // t=1200ms: re-enter the sync path directly through scheduleOneShotStop() -- the same
        // method stage 1 itself calls -- while the stale stage-2 confirmation is still pending.
        Shadows.shadowOf(service.mainLooper).idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        val scheduleOneShotStop = OpenVpnService::class.java.getDeclaredMethod(
            "scheduleOneShotStop", Long::class.javaPrimitiveType
        )
        scheduleOneShotStop.isAccessible = true
        scheduleOneShotStop.invoke(service, 1000L)

        // t=1400ms: the STALE deadline. If scheduleOneShotStop() had not cancelled the pending
        // stage-2 token (the load-bearing cancellation), stopSelf() would fire here.
        Shadows.shadowOf(service.mainLooper).idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(
            "A stage-2 confirmation left pending by a previous scheduling cycle must be " +
                "cancelled when scheduleOneShotStop() re-enters the sync path, not left to fire " +
                "on its stale schedule",
            shadowOf(service).isStoppedBySelf
        )

        // t=2200ms: the NEW stage-1 deadline (1000ms after the t=1200ms re-entry).
        Shadows.shadowOf(service.mainLooper).idleFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(shadowOf(service).isStoppedBySelf)

        // t=2600ms: the NEW stage-2 deadline. The controller must still stop, following the fresh
        // schedule established by the re-entry.
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(
            "The controller must still stop, following the NEW schedule established by " +
                "scheduleOneShotStop()'s re-entry, not be stuck forever",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Regression test for quality-gate finding G4: the pre-existing abort test
    // (oneShotSync_abortsBufferedStop_whenActionStartInterruptsDuringBuffer) pokes
    // userInitiatedStart via reflection, which isolates a WEAKER guard than the real ACTION_START
    // path trips first (oneShotSyncRequested=false, set earlier in onStartCommand, ahead of
    // userInitiatedStart=true). This drives a real ACTION_START Intent through the actual
    // onStartCommand() path end-to-end while a one-shot stop is pending, matching what a device
    // build actually does.
    //
    // sdk = [27]: as in startActionCallsStartForegroundAgainEvenWhenControllerForegroundAlreadyActive
    // above, ACTION_START's enterControllerForeground() call builds a real notification; on the
    // project's default Robolectric SDK that throws NoSuchMethodError (AndroidX-core/Robolectric
    // shadow-jar mismatch unrelated to this fix), which enterControllerForeground()'s catch block
    // then treats as a failure and calls stopSelf() -- masking the very thing this test checks.
    @Config(sdk = [27])
    @Test
    fun oneShotSync_realActionStartThroughOnStartCommand_abortsBufferedStop() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        // Pin explicitly (review-4 F5) rather than relying on Robolectric leaving
        // ProcessLifecycleOwner at INITIALIZED -- this test is about the ACTION_START abort path,
        // not UI visibility.
        ReflectionHelpers.setField(service, "appForegroundVisibleProvider", ({ false } as () -> Boolean))

        val syncIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val onInitialStateSynced = OpenVpnService::class.java.getDeclaredMethod(
            "onOneShotInitialStateSynced", String::class.java
        )
        onInitialStateSynced.isAccessible = true
        onInitialStateSynced.invoke(service, "test")

        // Stage 1 fires, scheduling the pending stage-2 confirmation.
        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(shadowOf(service).isStoppedBySelf)

        // A REAL ACTION_START intent, driven through the actual onStartCommand() path.
        val app = RuntimeEnvironment.getApplication()
        val startIntent = Intent(app, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(app), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(app), "client\n")
            putExtra(VpnManager.extraTitleKey(app), "RU")
        }
        service.onStartCommand(startIntent, 0, 2)

        // Advance through the confirm buffer: the real ACTION_START path must have prevented the
        // pending stage-2 confirmation from ever calling stopSelf().
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(
            "A genuine ACTION_START Intent driven through the real onStartCommand() path must " +
                "abort the pending one-shot stop end-to-end, not merely when userInitiatedStart " +
                "is flipped in isolation",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Regression test for quality-gate finding G4's CONNECTED-guard follow-up: the buffered
    // re-check must also abort when the VPN has become CONNECTED during the buffer, independent of
    // userInitiatedStart/Stop (e.g. an in-flight auto-switch reconnect succeeding).
    @Test
    fun oneShotSync_connectedGuard_abortsBufferedStop_whenVpnConnectsDuringBuffer() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        // Pin explicitly (review-4 F5) rather than relying on Robolectric leaving
        // ProcessLifecycleOwner at INITIALIZED -- this test is about the state guard, not UI
        // visibility.
        ReflectionHelpers.setField(service, "appForegroundVisibleProvider", ({ false } as () -> Boolean))

        val syncIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val onInitialStateSynced = OpenVpnService::class.java.getDeclaredMethod(
            "onOneShotInitialStateSynced", String::class.java
        )
        onInitialStateSynced.isAccessible = true
        onInitialStateSynced.invoke(service, "test")

        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(shadowOf(service).isStoppedBySelf)

        // The VPN reaches CONNECTED during the confirm buffer.
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(
            "The buffered re-check must abort stopSelf() once the VPN is CONNECTED, even without " +
                "userInitiatedStart/Stop being set",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Regression test for ClickUp 86cb2kqvu: a one-shot ACTION_SYNC_STATUS controller must not
    // tear down while CONNECTING, since a stale-push CONNECTING snapshot is exactly when
    // applyStatusSnapshot() arms ServerAutoSwitcher's timer -- tearing down here would orphan it.
    // See docs/guides/troubleshooting.md (bug 86cb35fbt entry) for the full mechanism.
    //
    // Guard pinned: STAGE 1 ONLY (OpenVpnService.kt:302). Shares stage1GuardAlone's (line 643)
    // exact mutation profile -- dies if that guard is reverted, survives a stage-2-only revert
    // (docs/qa-evidence/86cb2kqvu-autoswitch-timer-oneshot-teardown-review-2.md, MUT-1/MUT-2).
    // Differs from the pre-existing guard tests (lines 589, 643, 689) in scenario, not guard
    // coverage: this models the stale-push CONNECTING snapshot as the very first state the
    // one-shot sync observes, matching applyStatusSnapshot()'s timer-arming path directly.
    @Test
    fun oneShotSync_doesNotTearDownController_whenConnectingWithAutoSwitchTimerPotentiallyArmed() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        // Precondition: a stale-push CONNECTING snapshot -- NOT DISCONNECTED -- is exactly the
        // state under which applyStatusSnapshot() arms ServerAutoSwitcher's timer per 86cb2kqvu.
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ReflectionHelpers.setField(service, "appForegroundVisibleProvider", ({ false } as () -> Boolean))

        val syncIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_SYNC_STATUS)
        }
        service.onStartCommand(syncIntent, 0, 1)

        val onInitialStateSynced = OpenVpnService::class.java.getDeclaredMethod(
            "onOneShotInitialStateSynced", String::class.java
        )
        onInitialStateSynced.isAccessible = true
        onInitialStateSynced.invoke(service, "test")

        // State is CONNECTING when stage 1's 1000ms deadline arrives (ClickUp 86cb2kqvu).
        Shadows.shadowOf(service.mainLooper).idleFor(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(
            "Precondition: stage 1 must not have called stopSelf() directly",
            shadowOf(service).isStoppedBySelf
        )

        // The connection resolves to a genuine disconnect before stage 2's deadline. A stage-2
        // confirmation that was never scheduled (because stage 1's own guard aborted) cannot act
        // on this -- there is nothing left pending to fire.
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        Shadows.shadowOf(service.mainLooper).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertFalse(
            "stage 1's != DISCONNECTED guard must independently prevent scheduling stage 2 while " +
                "CONNECTING with ServerAutoSwitcher's timer potentially armed (ClickUp 86cb2kqvu) -- " +
                "if it does not, a stage-2 confirmation left pending from that moment can later act " +
                "on a state it never actually observed, tearing down and orphaning the armed timer, " +
                "which can then fire and switch away from a connection that actually succeeded",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Regression test for PR #135 round-1 bot review (Codex P2): this REVERSES QG4-1 (fix-cycle 8,
    // docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-4.md), which removed the
    // stopSelf() from this failure path on the premise that stopping inside a live FGS obligation
    // *causes* ForegroundServiceDidNotStartInTimeException. That premise is inverted, and gate-4
    // recorded its own confidence in it as only "medium ... not verified against AOSP".
    //
    // AMS treats bring-down as the DISCHARGE of the obligation, not a violation of it:
    // ActiveServices.bringDownServiceLocked() logs "Bringing down service while still waiting for
    // start foreground", clears fgRequired/fgWaiting and removes SERVICE_FOREGROUND_TIMEOUT_MSG;
    // serviceForegroundTimeout() additionally no-ops on `!r.fgRequired || r.destroying`. What
    // actually raises the exception is the timeout firing against a service that is still alive --
    // and START_NOT_STICKY does not stop a service (it only governs restart-after-kill), so the
    // post-QG4-1 code left exactly that state behind: obligation armed, service running, nothing
    // able to discharge it. QG4-1 therefore introduced a fresh route into the very crash class this
    // release exists to eliminate.
    //
    // Deliberately NOT pinned to @Config(sdk = [27]): on the project's DEFAULT Robolectric SDK,
    // NotificationCompat.Builder(...).build() inside enterControllerForeground() already throws
    // NoSuchMethodError (see the sdk=27 tests above, which pin away from this exact throw so their
    // own assertions about a successfully-posted notification aren't masked by it). That default
    // throw is precisely the fault this test needs, so no synthetic fault injection is required --
    // driving a real ACTION_START through onStartCommand() on the default SDK exercises it directly.
    @Test
    fun startAction_enterForegroundThrows_stopsSelfToDischargeFgsObligation() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        val startIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(service), "client\n")
            putExtra(VpnManager.extraTitleKey(service), "RU")
        }
        val result = service.onStartCommand(startIntent, 0, 1)

        // R9-6 (fix-cycle 9, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-9.md):
        // assert the induced fault actually happened BEFORE asserting the reaction to it. Without
        // this, a future Robolectric/AndroidX version bump that stops NotificationCompat.Builder
        // from throwing NoSuchMethodError on the default SDK would make enterControllerForeground()
        // succeed instead -- no stopSelf() would be called either way, so assertFalse(isStoppedBySelf)
        // below would keep passing for entirely the wrong reason, and the START_NOT_STICKY assertion
        // is vacuous on its own (onStartCommand() returns START_NOT_STICKY on every path). Checking
        // controllerForegroundActive == false via the same reflection idiom the sibling tests above
        // already use (see simulateEnteredControllerForeground()/controllerForegroundActive_isVolatile)
        // pins the test to the catch block having actually run: enterControllerForeground() only sets
        // this false inside that catch, and true on the success path.
        val activeField = OpenVpnService::class.java.getDeclaredField("controllerForegroundActive")
        activeField.isAccessible = true
        assertFalse(
            "Precondition: enterControllerForeground() must have actually thrown and hit its catch " +
                "block (controllerForegroundActive left false) for this test to be exercising the " +
                "QG4-1 fault path at all -- if this fails, the induced NoSuchMethodError fault no " +
                "longer occurs on the default Robolectric SDK and this test needs a real fault " +
                "injection instead of relying on an incidental throw",
            activeField.getBoolean(service)
        )
        assertEquals(
            "ACTION_START must still report START_NOT_STICKY when enterControllerForeground() fails",
            Service.START_NOT_STICKY,
            result
        )
        assertTrue(
            "A startForeground() throw during ACTION_START's enterControllerForeground() call MUST " +
                "stop the service. ACTION_START is the only action dispatched via " +
                "startForegroundService(), so an FGS-start obligation is live and can no longer be " +
                "discharged by startForeground() -- it just threw. START_NOT_STICKY does not stop a " +
                "service, so returning it alone leaves the obligation armed against a running " +
                "service and AMS raises ForegroundServiceDidNotStartInTimeException. Bringing the " +
                "service down is what clears fgRequired and cancels SERVICE_FOREGROUND_TIMEOUT_MSG.",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Regression tests for QG4-2(b) (fix-cycle 8,
    // docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-4.md): finishStopFlowConfirmed()'s
    // stopSelf() call must respect the same VpnManager.hasRecentActionStartDispatch() marker as the
    // cycle-7 one-shot-sync site and QG4-3's ACTION_STOP_IF_IDLE site. ServerAutoSwitcher's
    // previously-untracked retry lambda (QG4-2(a), see ServerAutoSwitcherTest's
    // cancelForUserStop_withinRetryDelayWindow_preventsOrphanedReconnect for that half of the fix)
    // could dispatch a fresh ACTION_START while a user-stop teardown was still resolving toward this
    // exact stopSelf() call -- arming a fresh FGS obligation this stopSelf() would tear down before
    // it is discharged.
    @Test
    fun finishStopFlowConfirmed_abortsStopSelf_whenRecentActionStartDispatchInFlight() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        val userInitiatedStopField = OpenVpnService::class.java.getDeclaredField("userInitiatedStop")
        userInitiatedStopField.isAccessible = true
        userInitiatedStopField.setBoolean(service, true)

        // A fresh ACTION_START dispatch attempt lands (e.g. the orphaned retry lambda QG4-2(a)
        // fixes), arming the recent-dispatch marker, while the user-stop confirmation below is
        // still in flight.
        VpnManager.startVpn(RuntimeEnvironment.getApplication(), "client\n", displayName = "RU")

        service.updateState("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, Intent())

        assertFalse(
            "QG4-2(b): finishStopFlowConfirmed()'s stopSelf() must be aborted while a recent " +
                "ACTION_START dispatch is still in flight, exactly like the cycle-7 one-shot-sync " +
                "site",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Falsifiability control for the test above: with no recent ACTION_START dispatch recorded,
    // a genuine confirmed user-stop must still call stopSelf() normally -- proves the guard only
    // suppresses the crash-adjacent case, not the ordinary teardown path.
    @Test
    fun finishStopFlowConfirmed_callsStopSelf_whenNoRecentActionStartDispatch() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        val userInitiatedStopField = OpenVpnService::class.java.getDeclaredField("userInitiatedStop")
        userInitiatedStopField.isAccessible = true
        userInitiatedStopField.setBoolean(service, true)

        service.updateState("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, Intent())

        assertTrue(
            "A genuine confirmed user-stop with no in-flight ACTION_START dispatch must still stop " +
                "the controller normally",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Regression test for QG4-3 (fix-cycle 8,
    // docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-4.md): ACTION_STOP_IF_IDLE's
    // stopSelf() must also respect hasRecentActionStartDispatch(). review-8's "FIFO ordering makes
    // this safe" argument only covers dispatch order (a STOP_IF_IDLE dispatched AFTER an
    // ACTION_START is always delivered after it); it misses the unsafe inverse, which is the QA
    // reproduction gesture itself: background the app (dispatches ACTION_STOP_IF_IDLE), then
    // immediately return and tap Connect -- a fresh ACTION_START can arm a new FGS obligation BEFORE
    // the already-in-flight ACTION_STOP_IF_IDLE is actually delivered here, so `state` above can
    // still read stale DISCONNECTED. The pre-existing stopIfIdleActionStopsService test above is
    // this test's falsifiability control: with no marker recorded, ACTION_STOP_IF_IDLE must still
    // stop normally.
    @Test
    fun stopIfIdleAction_abortsStopSelf_whenRecentActionStartDispatchInFlight() {
        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        VpnManager.startVpn(RuntimeEnvironment.getApplication(), "client\n", displayName = "RU")

        val intent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_STOP_IF_IDLE)
        }
        service.onStartCommand(intent, 0, 1)

        assertFalse(
            "QG4-3: ACTION_STOP_IF_IDLE's stopSelf() must be aborted while a recent ACTION_START " +
                "dispatch is still in flight, exactly like the cycle-7 and QG4-2 sites",
            shadowOf(service).isStoppedBySelf
        )
    }

    // Regression test for R9-3 (fix-cycle 9, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-review-9.md): onStartCommand()'s ACTION_START handler must clear
    // VpnManager's dispatch-marker once the start has actually landed and userInitiatedStart is
    // set -- see VpnManager.clearRecentActionStartDispatch()'s declaration comment and
    // VpnManagerTest.clearRecentActionStartDispatch_clearsMarker for the unit-level test of the
    // cleared function itself. Before this fix, the marker stayed "recent" for the full 2s bridge
    // window even after ACTION_START fully landed, so an ordinary Connect-then-Disconnect gesture
    // within that window suppressed finishStopFlowConfirmed()'s stopSelf() (QG4-2(b)) and left the
    // controller service lingering as a background service after an explicit Disconnect.
    //
    // sdk = [27]: this test needs onStartCommand()'s ACTION_START branch to run PAST
    // enterControllerForeground() (userInitiatedStart = true and the marker-clear both sit after
    // that call and are skipped via the early `return START_NOT_STICKY` if it fails). On the
    // project's default Robolectric SDK, enterControllerForeground() throws NoSuchMethodError (see
    // startAction_enterForegroundThrows_doesNotStopSelf above), which would make this test fail for
    // the wrong reason. Pinning sdk=27 lets the real notification path succeed, matching
    // startActionCallsStartForegroundAgainEvenWhenControllerForegroundAlreadyActive's use of the
    // same pin for the same reason.
    @Config(sdk = [27])
    @Test
    fun startAction_clearsRecentActionStartDispatchMarker() {
        val app = RuntimeEnvironment.getApplication()
        VpnManager.startVpn(app, "client\n", displayName = "RU")
        assertTrue(
            "Precondition: startVpn() must record the dispatch marker before ACTION_START is " +
                "delivered to onStartCommand()",
            VpnManager.hasRecentActionStartDispatch()
        )

        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        val startIntent = Intent().apply {
            putExtra(VpnManager.actionKey(service), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(service), "client\n")
            putExtra(VpnManager.extraTitleKey(service), "RU")
        }
        service.onStartCommand(startIntent, 0, 1)

        assertFalse(
            "R9-3: once ACTION_START has been delivered and processed (userInitiatedStart set), " +
                "the bridge marker must be cleared so it hands authority back to userInitiatedStart " +
                "instead of staying 'recent' for the rest of its 2s window",
            VpnManager.hasRecentActionStartDispatch()
        )
    }
}
