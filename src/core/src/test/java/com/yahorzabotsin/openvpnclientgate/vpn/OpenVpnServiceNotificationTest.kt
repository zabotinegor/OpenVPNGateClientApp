package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.Before
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
}
