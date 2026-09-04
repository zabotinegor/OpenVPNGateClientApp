package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.ConnectionStatus
import java.time.Duration
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
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

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

    // Regression test for fix-cycle 6 (docs/qa-evidence/
    // feature-86cb5y61z-reconnect-dispatch-state-machine-review-5.md, F1): ServerAutoSwitcher's
    // blank-config fall-through now routes its controller-notification cleanup through
    // VpnManager.stopControllerIfIdle() -> ACTION_STOP_IF_IDLE (its `idleNotificationStopper`)
    // instead of the full `stopper()` (VpnManager.stopVpn() -> ACTION_STOP -> OpenVpnService's
    // user-stop teardown), specifically because the full teardown's startUserStopTeardown()
    // unconditionally calls ConnectionStateManager.updateState(DISCONNECTING) -- a transition
    // review-5 proved IS accepted from DISCONNECTED (ConnectionState.kt's allowedFromDisconnected
    // includes DISCONNECTING), not rejected as a no-op as an earlier fix-cycle incorrectly claimed,
    // and which can latch state at DISCONNECTING with a spurious STOP_FAILED error if the engine
    // then declines the redundant stop dispatch.
    //
    // This drives the REAL dispatch end to end (VpnManager.stopControllerIfIdle() -> the actual
    // onStartCommand(ACTION_STOP_IF_IDLE) branch), not a fake stopper counter -- review-5 F2's
    // complaint about the prior regression test was exactly that a fake stopper can never observe
    // this class of defect -- and asserts the controller notification is cleared while
    // ConnectionStateManager never leaves DISCONNECTED, proving the new path's central safety claim
    // that the old ACTION_STOP-based dispatch got wrong.
    @Test
    fun blankConfigIdleStop_realStopControllerIfIdlePath_clearsNotificationWithoutEnteringDisconnecting() {
        val app: Application = RuntimeEnvironment.getApplication()
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = shadowOf(notificationManager)

        // Mirrors the state ServerAutoSwitcher's blank-config fall-through has already established
        // (cancel(resetCycle=true) + setReconnectingHint(false) + updateState(DISCONNECTED)) before
        // it calls idleNotificationStopper().
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        simulateEnteredControllerForeground(service)
        assertFalse("Precondition: notification must be posted", shadowNotificationManager.allNotifications.isEmpty())

        Shadows.shadowOf(app).clearStartedServices()

        // The real dispatcher ServerAutoSwitcher.idleNotificationStopper wraps in production.
        val dispatched = VpnManager.stopControllerIfIdle(app)
        assertTrue("Precondition: stopControllerIfIdle() must dispatch while state is DISCONNECTED", dispatched)

        val startedIntent = Shadows.shadowOf(app).nextStartedService
        assertTrue(
            "Precondition: stopControllerIfIdle() must dispatch ACTION_STOP_IF_IDLE",
            startedIntent != null &&
                startedIntent.getStringExtra(VpnManager.actionKey(service)) == VpnManager.ACTION_STOP_IF_IDLE
        )

        service.onStartCommand(startedIntent, 0, 1)

        assertTrue(
            "The real ACTION_STOP_IF_IDLE path must clear the retained controller foreground " +
                "notification, exactly as the fix-cycle-5 blank-config fix intended",
            shadowNotificationManager.allNotifications.isEmpty()
        )
        assertTrue("The real ACTION_STOP_IF_IDLE path must stop the service", shadowOf(service).isStoppedBySelf)
        assertEquals(
            "ConnectionStateManager must never leave DISCONNECTED for this path -- unlike the full " +
                "stopper()/ACTION_STOP path, ACTION_STOP_IF_IDLE's handler never calls " +
                "updateState() at all, so it cannot force the DISCONNECTED -> DISCONNECTING " +
                "sequence review-5 F1 proved was reachable from the old dispatch",
            ConnectionState.DISCONNECTED,
            ConnectionStateManager.state.value
        )
    }

    // Regression test for F1-6 (fix-cycle 7, docs/qa-evidence/
    // feature-86cb5y61z-reconnect-dispatch-state-machine-review-6.md): the TIMEOUT TWIN of the
    // blank-config fall-through above (ServerAutoSwitcher's scheduleStopRetryTimeout() runnable,
    // reached when the stop-retry timeout fires instead of NOTCONNECTED) must dispatch the REAL
    // engine-stop teardown -- stopper() -> VpnManager.stopVpn() -> ACTION_STOP, non-preserve --
    // NOT idleNotificationStopper() -> ACTION_STOP_IF_IDLE. The reason is the opposite of the
    // sibling above: idleNotificationStopper()'s premise (engine already confirmed idle, so a
    // notification-only cleanup with no engine contact is safe) does NOT hold on the timeout path,
    // because the timeout firing IS the absence of that confirmation -- the earlier stop was
    // dispatched with preserveReconnectHint = true, which never arms OpenVpnService's own
    // confirmation-timeout/retry machinery (that only exists on the non-preserve
    // startUserStopTeardown() path).
    //
    // This drives the REAL dispatch end to end (VpnManager.stopVpn() -> the actual
    // onStartCommand(ACTION_STOP) branch), not a fake stopper counter, and asserts both that the
    // controller notification is cleared (exitControllerForeground() runs unconditionally at
    // ACTION_STOP entry, before the preserve/non-preserve branch split) and that the app genuinely
    // enters DISCONNECTING -- proving a real engine-stop was requested, not merely a notification
    // cleanup masquerading as one. DISCONNECTING here is the correct, intentional outcome (not a
    // repeat of the review-5 F1 latch): OpenVpnService's startUserStopTeardown() arms
    // STOP_CONFIRMATION_TIMEOUT_MS/STOP_DISPATCH_MAX_ATTEMPTS, so this always resolves -- either to
    // DISCONNECTED on a genuine NOTCONNECTED, or to the documented STOP_FAILED error state after
    // repeated engine silence -- never a silent permanent latch.
    @Test
    fun stopRetryTimeoutBlankConfig_realStopVpnPath_clearsNotificationAndEntersDisconnecting() {
        val app: Application = RuntimeEnvironment.getApplication()
        val notificationManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotificationManager = shadowOf(notificationManager)

        // Mirrors the state ServerAutoSwitcher's stop-retry-timeout branch has already established
        // (cancel(resetCycle=true) + setReconnectingHint(false) + updateState(DISCONNECTED)) before
        // it calls stopper() -- the fix's dispatcher, distinct from the NOTCONNECTED sibling's
        // idleNotificationStopper().
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        simulateEnteredControllerForeground(service)
        assertFalse("Precondition: notification must be posted", shadowNotificationManager.allNotifications.isEmpty())

        Shadows.shadowOf(app).clearStartedServices()

        // The real dispatcher ServerAutoSwitcher.stopper wraps in production.
        val dispatched = VpnManager.stopVpn(app)
        assertTrue("Precondition: stopVpn() must dispatch", dispatched)

        val startedIntent = Shadows.shadowOf(app).nextStartedService
        assertTrue(
            "Precondition: stopVpn() must dispatch ACTION_STOP with preserveReconnectHint=false " +
                "(the non-preserve, full-teardown branch)",
            startedIntent != null &&
                startedIntent.getStringExtra(VpnManager.actionKey(service)) == VpnManager.ACTION_STOP &&
                !startedIntent.getBooleanExtra(VpnManager.extraPreserveReconnectKey(service), false)
        )

        service.onStartCommand(startedIntent, 0, 1)

        assertTrue(
            "The real ACTION_STOP path must clear the retained controller foreground notification " +
                "-- exitControllerForeground() runs unconditionally at ACTION_STOP entry, so " +
                "choosing the real teardown over idleNotificationStopper does not regress " +
                "notification cleanup",
            shadowNotificationManager.allNotifications.isEmpty()
        )
        assertEquals(
            "Unlike idleNotificationStopper's ACTION_STOP_IF_IDLE (which never mutates connection " +
                "state), the real stopper()/ACTION_STOP dispatch must force a genuine DISCONNECTING " +
                "transition here -- proving an actual engine-stop was requested rather than only a " +
                "notification cleanup, which is the whole point of not reusing idleNotificationStopper " +
                "on this path (no confirmation the engine is actually idle)",
            ConnectionState.DISCONNECTING,
            ConnectionStateManager.state.value
        )
    }

    // F1-9 (fix-cycle 9, PR #140 round 4, Kody thread PRRT_kwDOONeEXM6e2mam). Fix-cycle 8 had
    // ServerAutoSwitcher.dispatchStopAfterStopRetryTimeout() publish DISCONNECTED itself as soon as
    // stopper() returned true. That Boolean comes from Context.startService(), which acknowledges
    // DELIVERY, not teardown: onStartCommand() has not run, startUserStopTeardown() has not set
    // DISCONNECTING, and the engine has not confirmed anything. The switcher now publishes nothing
    // on an accepted dispatch (ServerAutoSwitcherTest pins that half).
    //
    // This test pins the other half, which is the load-bearing one: handing the outcome to the
    // controller must still CLOSE the permanent CONNECTING latch the branch exists to close, rather
    // than trade a false DISCONNECTED for a state nobody ever resolves. It starts from the exact
    // state the switcher leaves behind (CONNECTING, hint cleared), delivers the real ACTION_STOP,
    // and drives the engine confirmation, asserting the full CONNECTING -> DISCONNECTING ->
    // DISCONNECTED sequence actually completes without the switcher publishing any of it.
    @Test
    fun stopRetryTimeoutBlankConfig_acceptedStopDispatchResolvesConnectingLatchViaControllerTeardown() {
        val app: Application = RuntimeEnvironment.getApplication()

        // Exactly what ServerAutoSwitcher's stop-retry-timeout blank-config branch leaves behind
        // once it dispatches an ACCEPTED stop and returns without settling: the hint is cleared and
        // the cycle is reset, but connection state is still the CONNECTING that
        // updateFromEngine() had latched. Nothing but the controller can move it from here.
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        val controller = Robolectric.buildService(OpenVpnService::class.java)
        val service = controller.create().get()
        simulateEnteredControllerForeground(service)

        Shadows.shadowOf(app).clearStartedServices()
        val dispatched = VpnManager.stopVpn(app)
        assertTrue("Precondition: stopVpn() must dispatch", dispatched)

        assertEquals(
            "Precondition: an accepted dispatch alone must not have moved the state -- the intent " +
                "has been accepted for delivery, nothing more",
            ConnectionState.CONNECTING,
            ConnectionStateManager.state.value
        )

        val startedIntent = Shadows.shadowOf(app).nextStartedService
        service.onStartCommand(startedIntent, 0, 1)

        assertEquals(
            "delivering the accepted ACTION_STOP must break the CONNECTING latch: " +
                "startUserStopTeardown() sets DISCONNECTING unconditionally, and " +
                "CONNECTING -> DISCONNECTING is an allowed transition",
            ConnectionState.DISCONNECTING,
            ConnectionStateManager.state.value
        )

        // The engine confirms the stop, which is what makes the outcome terminal rather than a new
        // latch one step further along.
        service.updateState("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, Intent())

        assertEquals(
            "confirmed teardown must publish DISCONNECTED -- so handing the outcome to the " +
                "controller resolves the latch instead of merely relocating it",
            ConnectionState.DISCONNECTED,
            ConnectionStateManager.state.value
        )
        assertEquals(
            "a confirmed stop is not a stop failure",
            ConnectionStateManager.VpnError.NONE,
            ConnectionStateManager.error.value
        )
    }

    // When the blank-config stop-retry timeout's ACTION_STOP is rejected, ServerAutoSwitcher arms a
    // bounded re-dispatch TIMEOUT_STOP_DISPATCH_RETRY_DELAY_MS later. ServerAutoSwitcher.cancel()
    // clears it, but onStartCommand()'s ACTION_START branch touches none of ServerAutoSwitcher's
    // other state, so without an explicit supersession a user tapping Connect inside that one-second
    // window -- typically right after returning to the foreground, which is also what LIFTS the
    // background-start restriction that caused the rejection in the first place -- gets the retry
    // succeeding against their brand-new connection: a real, non-preserve ACTION_STOP into
    // startUserStopTeardown(), which stops the tunnel AND calls
    // ServerAutoSwitcher.cancelForUserStop(), killing the new cycle with it.
    //
    // This drives the REAL onStartCommand() ACTION_START branch on a real service instance rather
    // than calling the cancellation method directly -- a direct call would still pass with the
    // production call site deleted, which IS the defect.
    //
    // sdk = [27]: the cancellation deliberately sits AFTER ACTION_START's two aborting guards
    // (enterControllerForeground() and the blank-config check), because only a start that actually
    // commits may drop the previous tunnel's last remaining teardown -- see
    // abortedActionStart_preservesPendingStopRetryRedispatchSoTheOldTunnelIsStillStopped below for
    // the complementary case. On the project's default Robolectric SDK,
    // enterControllerForeground() throws NoSuchMethodError (an AndroidX-core/Robolectric shadow-jar
    // mismatch unrelated to this fix), so ACTION_START would abort and never reach the cancellation.
    // Pinning sdk=27 lets the real notification path run so the start genuinely commits.
    //
    // Falsification: removing ServerAutoSwitcher.detachStopDispatchForPendingStart() from
    // OpenVpnService's ACTION_START branch fails both assertions below.
    @Config(sdk = [27])
    @Test
    fun committedActionStart_cancelsPendingStopRetryRedispatchFromRejectedBlankConfigStop() {
        val app: Application = RuntimeEnvironment.getApplication()
        val originalStopper = ServerAutoSwitcher.stopper
        val originalStarter = ServerAutoSwitcher.starter
        try {
            UserSettingsStore.saveAutoSwitchWithinCountry(app, true)
            UserSettingsStore.saveStatusStallTimeoutSeconds(app, 2)
            ServerAutoSwitcher.setNoReplyThresholdForTest(2)
            // Second server has a blank config, which is what routes the switch into the
            // blank-config branch of the stop-retry timeout.
            SelectedCountryStore.saveSelection(
                app,
                "RU",
                listOf(
                    Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
                    Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
                )
            )
            SelectedCountryStore.resetIndex(app)

            val dispatchContext = RejectingStartServiceContext(app)
            ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }
            ServerAutoSwitcher.starter = { _, _, _, _ -> true }

            ServerAutoSwitcher.onEngineLevel(
                dispatchContext,
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                "VPN_STATUS"
            )
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            ConnectionStateManager.updateState(ConnectionState.CONNECTING)

            // The blank-config timeout fires while the background-start restriction is active.
            dispatchContext.rejecting = true
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(
                "Precondition: a rejected stop dispatch must arm a bounded re-dispatch",
                ServerAutoSwitcher.hasPendingStopDispatchForTest()
            )

            // The user returns to the foreground and taps Connect inside the retry window.
            dispatchContext.rejecting = false
            val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
            Shadows.shadowOf(app).clearStartedServices()
            val startIntent = Intent(app, OpenVpnService::class.java).apply {
                putExtra(VpnManager.actionKey(app), VpnManager.ACTION_START)
                putExtra(VpnManager.extraConfigKey(app), "client\n")
                putExtra(VpnManager.extraTitleKey(app), "RU")
            }
            service.onStartCommand(startIntent, 0, 1)

            assertFalse(
                "A committed ACTION_START must supersede the stale stop re-dispatch -- otherwise it " +
                    "fires up to a second later and stops the connection the user just started",
                ServerAutoSwitcher.hasPendingStopDispatchForTest()
            )

            // Let the window the stale retry would have fired in elapse, and prove nothing stopped
            // the fresh connection.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
            val stops = generateSequence { Shadows.shadowOf(app).nextStartedService }
                .filter { it.getStringExtra(VpnManager.actionKey(app)) == VpnManager.ACTION_STOP }
                .toList()
            assertTrue(
                "No ACTION_STOP may be dispatched after a fresh start commits: reaching " +
                    "startUserStopTeardown() would both stop the new tunnel and, via " +
                    "cancelForUserStop(), tear down the new cycle",
                stops.isEmpty()
            )
        } finally {
            ServerAutoSwitcher.stopper = originalStopper
            ServerAutoSwitcher.starter = originalStarter
            ServerAutoSwitcher.resetNoReplyThreshold()
            ServerAutoSwitcher.resetForTest()
        }
    }

    // The complement of the test above, and the reason the cancellation sits AFTER ACTION_START's
    // aborting guards instead of with the pending-stop bookkeeping at the top of that branch.
    //
    // enterControllerForeground() can fail (startForeground() throws), and ACTION_START then returns
    // START_NOT_STICKY without starting any engine. Cancelling the switcher's re-dispatch on that
    // path strands the previous, possibly still-live tunnel. It is NOT equivalent to the
    // service-side stop runnables the branch discards above: those merely retry a stop that already
    // reached startUserStopTeardown() and requestStopIcsOpenVpn(), whereas this re-dispatch stands
    // in for a stop whose dispatch was REJECTED before OpenVpnService ever saw it -- so once it is
    // gone, nothing is asking the engine to stop and nothing can escalate to STOP_FAILED either.
    //
    // Runs on the project's default Robolectric SDK precisely because enterControllerForeground()
    // reliably throws NoSuchMethodError there, which is exactly the abort this test needs.
    //
    // Falsification: moving detachStopDispatchForPendingStart() back above
    // enterControllerForeground() fails both assertions below.
    @Test
    fun abortedActionStart_preservesPendingStopRetryRedispatchSoTheOldTunnelIsStillStopped() {
        val app: Application = RuntimeEnvironment.getApplication()
        val originalStopper = ServerAutoSwitcher.stopper
        val originalStarter = ServerAutoSwitcher.starter
        try {
            UserSettingsStore.saveAutoSwitchWithinCountry(app, true)
            UserSettingsStore.saveStatusStallTimeoutSeconds(app, 2)
            ServerAutoSwitcher.setNoReplyThresholdForTest(2)
            // Second server has a blank config, which is what routes the switch into the
            // blank-config branch of the stop-retry timeout.
            SelectedCountryStore.saveSelection(
                app,
                "RU",
                listOf(
                    Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
                    Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
                )
            )
            SelectedCountryStore.resetIndex(app)

            val dispatchContext = RejectingStartServiceContext(app)
            ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }
            ServerAutoSwitcher.starter = { _, _, _, _ -> true }

            ServerAutoSwitcher.onEngineLevel(
                dispatchContext,
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                "VPN_STATUS"
            )
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            ConnectionStateManager.updateState(ConnectionState.CONNECTING)

            // The blank-config timeout fires while the background-start restriction is active, so
            // the real ACTION_STOP never reaches OpenVpnService and only this re-dispatch is left.
            dispatchContext.rejecting = true
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertTrue(
                "Precondition: a rejected stop dispatch must arm a bounded re-dispatch",
                ServerAutoSwitcher.hasPendingStopDispatchForTest()
            )

            dispatchContext.rejecting = false
            val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
            Shadows.shadowOf(app).clearStartedServices()
            val startIntent = Intent(app, OpenVpnService::class.java).apply {
                putExtra(VpnManager.actionKey(app), VpnManager.ACTION_START)
                putExtra(VpnManager.extraConfigKey(app), "client\n")
                putExtra(VpnManager.extraTitleKey(app), "RU")
            }
            service.onStartCommand(startIntent, 0, 1)

            // enterControllerForeground() only clears controllerForegroundActive in its catch block,
            // so this pins the test to the abort having actually happened rather than passing
            // vacuously if the induced fault ever stops firing.
            val activeField = OpenVpnService::class.java.getDeclaredField("controllerForegroundActive")
            activeField.isAccessible = true
            assertFalse(
                "Precondition: enterControllerForeground() must have thrown and hit its catch block, " +
                    "so ACTION_START aborts before starting any engine",
                activeField.getBoolean(service)
            )

            assertTrue(
                "An ACTION_START that aborts must NOT cancel the pending stop re-dispatch: it starts " +
                    "no replacement engine, so this retry is the only thing still trying to stop the " +
                    "previous, possibly live tunnel",
                ServerAutoSwitcher.hasPendingStopDispatchForTest()
            )

            // The preserved retry must still reach the controller once the background-start
            // restriction lifts, otherwise the old tunnel has no teardown path at all.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
            val stops = generateSequence { Shadows.shadowOf(app).nextStartedService }
                .filter { it.getStringExtra(VpnManager.actionKey(app)) == VpnManager.ACTION_STOP }
                .toList()
            assertFalse(
                "The preserved re-dispatch must still deliver a real ACTION_STOP for the stranded " +
                    "tunnel after an aborted start",
                stops.isEmpty()
            )
        } finally {
            ServerAutoSwitcher.stopper = originalStopper
            ServerAutoSwitcher.starter = originalStarter
            ServerAutoSwitcher.resetNoReplyThreshold()
            ServerAutoSwitcher.resetForTest()
        }
    }

    // Rejects startService() the way Android's background-start restriction does, on demand.
    // VpnManager.startControllerService() catches IllegalStateException and returns false.
    private class RejectingStartServiceContext(base: Context) : android.content.ContextWrapper(base) {
        var rejecting = false

        override fun startService(service: Intent?): android.content.ComponentName? {
            if (rejecting) throw IllegalStateException("background start not allowed")
            return super.startService(service)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Custody of the pending stop re-dispatch across an ACTION_START whose engine launch is not
    // guaranteed.
    //
    // The two tests above pin the two outcomes that were already understood: a start that commits
    // supersedes the re-dispatch, and a start that aborts at its guards never touches it. Between
    // those sits a third class of outcome -- the branch passes its guards, takes the re-dispatch
    // away, and then never reaches the engine after all, because the OVPN payload is malformed or
    // because a reconnect's deferred dispatch bails during its buffer. The previous tunnel is then
    // in exactly the state the aborting-guard case exists to avoid: its own stop was rejected before
    // OpenVpnService ever saw it, no replacement teardown was ever armed, and the one bounded retry
    // that would have escalated it to STOP_FAILED is gone.
    //
    // The tests below cover each outcome of that third class, plus the success outcome that must
    // still drop the re-dispatch, so "restore whenever unsure" cannot silently satisfy them all.
    // ---------------------------------------------------------------------------------------

    // Drives the REAL production path that arms the bounded stop re-dispatch: an auto-switch onto a
    // blank-config server whose ACTION_STOP is rejected by the background-start restriction. Leaves
    // the restriction lifted on return, so a restored re-dispatch can actually be observed firing.
    // Synthesizing the pending Runnable by reflection instead would keep passing even if the
    // production arming path stopped producing one.
    private fun armRejectedBlankConfigStopRedispatch(app: Application): RejectingStartServiceContext {
        UserSettingsStore.saveAutoSwitchWithinCountry(app, true)
        UserSettingsStore.saveStatusStallTimeoutSeconds(app, 2)
        ServerAutoSwitcher.setNoReplyThresholdForTest(2)
        SelectedCountryStore.saveSelection(
            app,
            "RU",
            listOf(
                Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
                Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
            )
        )
        SelectedCountryStore.resetIndex(app)

        val dispatchContext = RejectingStartServiceContext(app)
        ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }
        ServerAutoSwitcher.starter = { _, _, _, _ -> true }

        ServerAutoSwitcher.onEngineLevel(
            dispatchContext,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            "VPN_STATUS"
        )
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        dispatchContext.rejecting = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(
            "Precondition: a rejected stop dispatch must arm a bounded re-dispatch",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        dispatchContext.rejecting = false
        return dispatchContext
    }

    private fun startIntent(app: Application, config: String, isReconnect: Boolean = false) =
        Intent(app, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(app), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(app), config)
            putExtra(VpnManager.extraTitleKey(app), "RU")
            if (isReconnect) putExtra(VpnManager.extraAutoSwitchKey(app), true)
        }

    // The Runnable the service is currently holding custody of, or null when custody is resolved.
    // Read directly because "resolved" and "restored" are different states that
    // hasPendingStopDispatchForTest() alone cannot tell apart: a Runnable stranded in this field is
    // just as unscheduled as one correctly dropped.
    @Suppress("UNCHECKED_CAST")
    private fun heldStopDispatchCustody(service: OpenVpnService): Runnable? {
        val field = OpenVpnService::class.java.getDeclaredField("supersededStopDispatch")
        field.isAccessible = true
        return (field.get(service) as java.util.concurrent.atomic.AtomicReference<Runnable?>).get()
    }

    private fun realStopsDispatchedTo(app: Application): List<Intent> =
        generateSequence { Shadows.shadowOf(app).nextStartedService }
            .filter { it.getStringExtra(VpnManager.actionKey(app)) == VpnManager.ACTION_STOP }
            .toList()

    private fun withSwitcherRestored(body: () -> Unit) {
        val originalStopper = ServerAutoSwitcher.stopper
        val originalStarter = ServerAutoSwitcher.starter
        try {
            body()
        } finally {
            ServerAutoSwitcher.stopper = originalStopper
            ServerAutoSwitcher.starter = originalStarter
            ServerAutoSwitcher.resetNoReplyThreshold()
            ServerAutoSwitcher.resetForTest()
        }
    }

    // sdk = [27] on the tests below for the same reason the committed-start test above pins it:
    // enterControllerForeground() throws NoSuchMethodError on the project's default Robolectric SDK,
    // which would abort ACTION_START at its first guard -- before the branch ever takes custody --
    // and make every assertion here vacuous.

    // The immediate (non-reconnect) launch path. A malformed OVPN payload throws out of
    // ConfigParser.parseConfig() inside startIcsOpenVpn(), which logs and calls stopSelf() -- no
    // engine is ever asked to start, so this start superseded nothing and the previous tunnel is
    // still owed its stop.
    @Config(sdk = [27])
    @Test
    fun freshStartWithMalformedConfig_restoresPendingStopRetryRedispatch() = withSwitcherRestored {
        val app: Application = RuntimeEnvironment.getApplication()
        armRejectedBlankConfigStopRedispatch(app)

        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        Shadows.shadowOf(app).clearStartedServices()
        service.onStartCommand(startIntent(app, MALFORMED_OVPN_CONFIG), 0, 1)

        assertTrue(
            "A start whose engine launch threw asked no engine to start, so it performed none of " +
                "the teardown that would justify dropping the previous tunnel's last bounded stop " +
                "attempt -- the re-dispatch must be back",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // Prove it was genuinely re-scheduled and still executable, not merely a non-null field.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertFalse(
            "The restored re-dispatch must still deliver the real ACTION_STOP the stranded tunnel " +
                "is owed",
            realStopsDispatchedTo(app).isEmpty()
        )
    }

    // The deferred (reconnect) launch path reaching its launch and failing there. Also pins that
    // custody is taken at ACTION_START time rather than at launch time: for the whole buffer window
    // the re-dispatch must be unscheduled, because that is precisely when it would otherwise fire a
    // real ACTION_STOP into the reconnect that is mid-flight.
    @Config(sdk = [27])
    @Test
    fun reconnectStartWithMalformedConfig_restoresPendingStopRetryRedispatchAfterTheBuffer() = withSwitcherRestored {
        val app: Application = RuntimeEnvironment.getApplication()
        armRejectedBlankConfigStopRedispatch(app)

        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        Shadows.shadowOf(app).clearStartedServices()
        service.onStartCommand(startIntent(app, MALFORMED_OVPN_CONFIG, isReconnect = true), 0, 1)

        assertFalse(
            "While a reconnect's engine dispatch is still buffered, the re-dispatch must be held, " +
                "not left scheduled -- otherwise it fires a real, non-preserve ACTION_STOP into the " +
                "attempt that is about to launch",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // The buffer elapses, the deferred dispatch runs, and its launch throws.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertTrue(
            "A deferred reconnect dispatch whose engine launch threw must hand the re-dispatch back",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertFalse(
            "The restored re-dispatch must still deliver the real ACTION_STOP the stranded tunnel " +
                "is owed",
            realStopsDispatchedTo(app).isEmpty()
        )
    }

    // A user Disconnect landing inside the buffer window sweeps the deferred dispatch, so the start
    // that took custody never runs again to resolve it. The teardown must therefore resolve it --
    // and DROP is the correct resolution here, because that teardown delivers a real stop to the
    // engine and arms the service's own bounded confirmation/retry/STOP_FAILED escalation: exactly
    // the replacement the held Runnable was standing in for. What must not happen is the Runnable
    // being stranded in the service's custody field, neither scheduled anywhere nor owned by
    // anything that could ever reschedule it.
    @Config(sdk = [27])
    @Test
    fun reconnectStartAbandonedByUserStop_resolvesCustodyToTheTeardownItDelivered() = withSwitcherRestored {
        val app: Application = RuntimeEnvironment.getApplication()
        armRejectedBlankConfigStopRedispatch(app)

        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        Shadows.shadowOf(app).clearStartedServices()
        service.onStartCommand(startIntent(app, "client\n", isReconnect = true), 0, 1)

        val stopIntent = Intent(app, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(app), VpnManager.ACTION_STOP)
        }
        service.onStartCommand(stopIntent, 0, 2)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertFalse(
            "A user stop delivered its own real stop plus bounded escalation, so re-arming the " +
                "held re-dispatch would only schedule a second, non-preserve ACTION_STOP into a " +
                "teardown already under way",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
        assertNull(
            "The custody must be RESOLVED, not merely unscheduled: leaving the Runnable in the " +
                "service's custody field after the teardown swept the dispatch that would have " +
                "resolved it strands it where nothing can ever restore or run it",
            heldStopDispatchCustody(service)
        )
    }

    // The complementary resolution. A destroyed service delivered no stop and started no engine, and
    // sweeps every runnable it owned -- so custody must go back to ServerAutoSwitcher, whose Handler
    // outlives the service instance.
    @Config(sdk = [27])
    @Test
    fun reconnectStartAbandonedByServiceDestroy_restoresPendingStopRetryRedispatch() = withSwitcherRestored {
        val app: Application = RuntimeEnvironment.getApplication()
        armRejectedBlankConfigStopRedispatch(app)

        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        Shadows.shadowOf(app).clearStartedServices()
        service.onStartCommand(startIntent(app, "client\n", isReconnect = true), 0, 1)

        // The service is destroyed inside the buffer window. Nothing in this process is left to
        // stop the previous tunnel except the switcher's own re-dispatch, which outlives the
        // service instance.
        controller.destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertTrue(
            "A destroyed service armed no teardown of its own, so dropping the re-dispatch here " +
                "leaves the previous tunnel with nothing stopping it anywhere",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
    }

    // Taking custody is two steps -- the Runnable leaves ServerAutoSwitcher, then arrives in the
    // service's custody field -- and the drop that resolves it is reachable from the AIDL binder
    // thread (startUserStopTeardown() via maybeStartStaleStopReconciliation), so it can land BETWEEN
    // those two steps. It then finds the field still empty, resolves nothing, and the publication
    // that follows re-takes custody the teardown had already settled. This start now holds a
    // Runnable whose tunnel HAS a real replacement stop with its own bounded escalation, so its own
    // abort re-arms a stale, non-preserve ACTION_STOP into a teardown already under way.
    //
    // Genuine two-thread interleaving, not a simulated one: a Timber tree intercepts the real
    // production log line emitted INSIDE the detach (after the switcher's field is cleared, before
    // the publication) and, once, runs the real startUserStopTeardown() on a real background thread
    // -- the same technique the reconnect-dispatch tests use for binder-thread races. The start then
    // fails its engine launch, which is the outcome that makes a wrongly held custody observable:
    // its restore path hands the stale Runnable back to the switcher.
    @Config(sdk = [27])
    @Test
    fun startTakingCustodyRacedByBinderThreadTeardown_doesNotReArmAStopAgainstThatTeardown() = withSwitcherRestored {
        val app: Application = RuntimeEnvironment.getApplication()
        armRejectedBlankConfigStopRedispatch(app)

        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        Shadows.shadowOf(app).clearStartedServices()

        val messages = java.util.Collections.synchronizedList(mutableListOf<String>())
        val injected = AtomicBoolean(false)
        val injectionFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                messages += message
                if (message.contains("Detached pending stop-retry-timeout re-dispatch") &&
                    injected.compareAndSet(false, true)
                ) {
                    val binderThread = Thread {
                        try {
                            ReflectionHelpers.callInstanceMethod<Any>(
                                service,
                                "startUserStopTeardown",
                                ReflectionHelpers.ClassParameter.from(String::class.java, "stale_relaunch"),
                                ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, true)
                            )
                        } catch (e: Throwable) {
                            injectionFailure.set(e)
                        }
                    }
                    binderThread.start()
                    binderThread.join()
                }
            }
        }
        Timber.plant(tree)
        try {
            service.onStartCommand(startIntent(app, MALFORMED_OVPN_CONFIG), 0, 1)
        } finally {
            Timber.uproot(tree)
        }

        assertTrue(
            "Precondition: the race must actually have been injected inside the detach window",
            injected.get()
        )
        assertNull(
            "Precondition: the injected teardown must have run, not thrown out of the window",
            injectionFailure.get()
        )
        assertTrue(
            "Precondition: the engine launch must have failed, so this start reaches its restore " +
                "path -- the only outcome on which a wrongly held custody is observable",
            messages.any { it.contains("OVPN parse error") }
        )

        // Asserted before the looper is idled: the teardown's own cancelForUserStop() is still
        // queued on the main looper and would clear a wrongly restored re-dispatch, hiding the
        // defect behind a cancellation that happens to arrive later.
        assertFalse(
            "A teardown that landed mid-custody-transfer already delivered the replacement stop " +
                "this Runnable stood in for, so the start must not publish custody it can then hand " +
                "back: re-arming a real, non-preserve ACTION_STOP into a teardown already under way",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
        assertNull(
            "and nothing may be left stranded in the service's custody field either",
            heldStopDispatchCustody(service)
        )
    }

    // The superseded-generation bail is the one non-launching outcome that must NOT restore: a
    // newer ACTION_START has already inherited custody at its own detach site (the switcher's field
    // is null by then, so its detach returns null and the newer start keeps what the older one
    // holds). Restoring from the older attempt too would both double the Runnable and re-arm a real,
    // non-preserve ACTION_STOP against the newer, live start.
    //
    // Driving the newer start to FAILURE is what makes this observable: if custody were dropped
    // rather than inherited, the newer start would have nothing to give back and the re-dispatch
    // would be gone for good.
    @Config(sdk = [27])
    @Test
    fun supersededReconnectStart_handsCustodyToTheNewerStartRatherThanRestoringItself() = withSwitcherRestored {
        val app: Application = RuntimeEnvironment.getApplication()
        armRejectedBlankConfigStopRedispatch(app)

        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        Shadows.shadowOf(app).clearStartedServices()
        // The older attempt takes custody, then a newer reconnect supersedes it inside the buffer.
        service.onStartCommand(startIntent(app, "client\n", isReconnect = true), 0, 1)
        service.onStartCommand(startIntent(app, MALFORMED_OVPN_CONFIG, isReconnect = true), 0, 2)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertTrue(
            "The superseded attempt must leave custody to the newer start, and the newer start's " +
                "own failed launch must then restore it -- exactly once, from the attempt that " +
                "actually owned the outcome",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // Exactly once: a double restore would post the same Runnable twice and deliver two stops,
        // turning the bounded chain into two independent chains.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertEquals(
            "the restored re-dispatch must deliver exactly one ACTION_STOP",
            1,
            realStopsDispatchedTo(app).size
        )
    }

    // The harmful half of the superseded-generation bail, which the test above cannot observe.
    //
    // Above, the newer start FAILS, so a superseded attempt that wrongly restored would be
    // indistinguishable from the newer attempt correctly restoring: the AtomicReference makes the
    // second resolution a no-op, so the observable outcome (one re-dispatch, one ACTION_STOP) is
    // identical either way. Here the newer start SUCCEEDS, which is the only arrangement where the
    // two behaviours diverge -- and it is also the damaging one. A restore from the superseded
    // attempt re-arms a real, non-preserve ACTION_STOP that fires against the newer tunnel that
    // just reached the engine, and the newer attempt cannot undo it: by the time its own success
    // path runs, custody is already gone, so its drop is a no-op on an empty reference.
    @Config(sdk = [27])
    @Test
    fun supersededReconnectStart_doesNotReArmAStopAgainstTheNewerStartThatReachedTheEngine() = withSwitcherRestored {
        val app: Application = RuntimeEnvironment.getApplication()
        armRejectedBlankConfigStopRedispatch(app)

        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        Shadows.shadowOf(app).clearStartedServices()
        // The older attempt takes custody, then a newer reconnect supersedes it inside the buffer.
        // Both carry a launchable config, so the newer one reaches the engine.
        service.onStartCommand(startIntent(app, "client\n", isReconnect = true), 0, 1)
        service.onStartCommand(startIntent(app, "client\n", isReconnect = true), 0, 2)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertFalse(
            "The superseded attempt must walk away from custody rather than restore it: the newer " +
                "start reached the engine and performed the equivalent teardown, so nothing is owed " +
                "a stop any more",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
        assertNull(
            "The newer start's own success must have resolved the inherited custody",
            heldStopDispatchCustody(service)
        )

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertTrue(
            "No ACTION_STOP may reach the controller: a re-dispatch re-armed by the superseded " +
                "attempt would stop the newer, live tunnel and, via cancelForUserStop(), tear down " +
                "the new switch cycle with it",
            realStopsDispatchedTo(app).isEmpty()
        )
    }

    // The success outcome, on the deferred path. Its immediate-path twin is
    // committedActionStart_cancelsPendingStopRetryRedispatchFromRejectedBlankConfigStop above.
    // Without this, "restore on every outcome" would satisfy every other test in this group.
    @Config(sdk = [27])
    @Test
    fun reconnectStartReachingTheEngine_dropsPendingStopRetryRedispatch() = withSwitcherRestored {
        val app: Application = RuntimeEnvironment.getApplication()
        armRejectedBlankConfigStopRedispatch(app)

        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        Shadows.shadowOf(app).clearStartedServices()
        service.onStartCommand(startIntent(app, "client\n", isReconnect = true), 0, 1)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertFalse(
            "A reconnect that actually reached the engine performed the equivalent teardown for " +
                "the superseded stop, so the re-dispatch must stay dropped",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertTrue(
            "No ACTION_STOP may reach the controller after a start commits: it would stop the new " +
                "tunnel and, via cancelForUserStop(), tear down the new cycle with it",
            realStopsDispatchedTo(app).isEmpty()
        )
    }

    // The preserveReconnect ACTION_STOP twin of
    // reconnectStartAbandonedByUserStop_resolvesCustodyToTheTeardownItDelivered above. It is a
    // separate branch with its own drop call, not a variation on the user-stop path: it never
    // reaches startUserStopTeardown(), doing its own generation bump, its own
    // reconnectEngineDispatchToken sweep and its own requestStopIcsOpenVpn().
    //
    // DROP is the correct resolution for the same reason as on the user-stop path: this branch
    // delivered a real stop to the engine with the service's own bounded confirmation/retry
    // escalation armed, which is the replacement teardown the held Runnable stood in for. But
    // because the branch also sweeps the deferred dispatch that would otherwise have resolved
    // custody, resolving it here is the only thing standing between the Runnable and being stranded.
    //
    // Falsification: removing dropSupersededStopDispatch() from the preserveReconnect branch fails
    // the first assertion; restoring instead of dropping fails the second.
    @Config(sdk = [27])
    @Test
    fun reconnectStartAbandonedByPreserveReconnectStop_resolvesCustodyToTheTeardownItDelivered() = withSwitcherRestored {
        val app: Application = RuntimeEnvironment.getApplication()
        armRejectedBlankConfigStopRedispatch(app)

        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        Shadows.shadowOf(app).clearStartedServices()
        service.onStartCommand(startIntent(app, "client\n", isReconnect = true), 0, 1)

        val preserveStopIntent = Intent(app, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(app), VpnManager.ACTION_STOP)
            putExtra(VpnManager.extraPreserveReconnectKey(app), true)
        }
        service.onStartCommand(preserveStopIntent, 0, 2)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertNull(
            "The custody must be RESOLVED, not merely unscheduled: this branch swept the deferred " +
                "dispatch that would have resolved it, so leaving the Runnable in the service's " +
                "custody field strands it where nothing can ever restore or run it",
            heldStopDispatchCustody(service)
        )
        assertFalse(
            "This branch delivered its own real stop plus bounded escalation, so re-arming the " +
                "held re-dispatch would only schedule a second, non-preserve ACTION_STOP into a " +
                "teardown already under way",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
    }

    // The one superseding path that does NOT inherit custody, and so is the one place the
    // superseded-generation branch's "walk away, someone else owns it now" reasoning does not hold.
    //
    // A blank-config ACTION_START bumps the reconnect generation at
    // reconnectDispatchGuard.beginNewAttempt() and then returns at the "No config to start" guard,
    // which sits BEFORE the custody detach site. Every other superseding path -- a newer launchable
    // ACTION_START, a preserveReconnect ACTION_STOP, a confirmed stop -- either inherits the
    // Runnable or resolves it. This one used to do neither, leaving it stranded in the service:
    // unscheduled in ServerAutoSwitcher and owned by nothing that could reschedule it, which is the
    // exact "previous tunnel left with no teardown anywhere" state the whole custody model exists to
    // prevent.
    //
    // Falsification: removing restoreSupersededStopDispatchIfHeld() from that guard fails both
    // assertions below.
    @Config(sdk = [27])
    @Test
    fun reconnectStartSupersededByBlankConfigStart_restoresRatherThanStrandingCustody() = withSwitcherRestored {
        val app: Application = RuntimeEnvironment.getApplication()
        armRejectedBlankConfigStopRedispatch(app)

        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        Shadows.shadowOf(app).clearStartedServices()
        service.onStartCommand(startIntent(app, "client\n", isReconnect = true), 0, 1)
        service.onStartCommand(startIntent(app, ""), 0, 2)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertNull(
            "custody must not be stranded in the service after a superseding start that never " +
                "reached the detach site",
            heldStopDispatchCustody(service)
        )
        assertTrue(
            "no engine was started by either attempt, so the previous tunnel is still owed its " +
                "bounded stop attempt",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
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

        // An inline <ca> block with no closing tag. ConfigParser.parseConfig() throws
        // ConfigParseError("No endtag </ca> for starttag <ca> found") on it, which is the
        // malformed-payload failure startIcsOpenVpn() catches -- it returns without ever reaching
        // VPNLaunchHelper.startOpenVpn(), so no engine is asked to launch.
        private const val MALFORMED_OVPN_CONFIG = "client\n<ca>\n-----BEGIN CERTIFICATE-----\n"
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

    // Regression test for QG4-1 (fix-cycle 8,
    // docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-4.md): enterControllerForeground()'s
    // catch block used to call stopSelf() when startForeground() threw during ACTION_START's own
    // call (stopOnFailure defaulted to true there) -- converting a recoverable "could not show the
    // notification" failure into exactly the RemoteServiceException
    // $ForegroundServiceDidNotStartInTimeException crash class this bug's fix-flow exists to
    // eliminate. ACTION_START's startForegroundService() dispatch (VpnManager.startVpn()) has
    // already armed the FGS-start obligation by the time this catch block runs, so stopSelf() there
    // tore the obligation down before it could ever be discharged.
    //
    // Deliberately NOT pinned to @Config(sdk = [27]): on the project's DEFAULT Robolectric SDK,
    // NotificationCompat.Builder(...).build() inside enterControllerForeground() already throws
    // NoSuchMethodError (see the sdk=27 tests above, which pin away from this exact throw so their
    // own assertions about a successfully-posted notification aren't masked by it). That default
    // throw is precisely the fault this test needs, so no synthetic fault injection is required --
    // driving a real ACTION_START through onStartCommand() on the default SDK exercises it directly.
    @Test
    fun startAction_enterForegroundThrows_doesNotStopSelf() {
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
            "ACTION_START must report START_NOT_STICKY when enterControllerForeground() fails, " +
                "exactly like onCreate()'s pre-existing stopOnFailure=false treatment of the same " +
                "failure",
            Service.START_NOT_STICKY,
            result
        )
        assertFalse(
            "QG4-1: a startForeground() throw during ACTION_START's enterControllerForeground() " +
                "call must NOT call stopSelf() -- the FGS-start obligation registered by this " +
                "ACTION_START's own startForegroundService() dispatch is still undischarged at this " +
                "point, and stopSelf() here reproduces the exact crash this fix removes. " +
                "START_NOT_STICKY at the ACTION_START call site already handles the failure " +
                "correctly, matching the sibling onCreate() call which never stops on failure.",
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
