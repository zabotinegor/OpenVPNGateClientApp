package com.yahorzabotsin.openvpnclientgate.vpn

import android.os.Looper
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.servers.probe.ProbeRequestQueue
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE)
class ServerAutoSwitcherTest {
    private val appContext = RuntimeEnvironment.getApplication()
    private val logTag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ":" + "ServerAutoSwitcher"
    private val source = "VPN_STATUS"
    private var originalStarter: ((android.content.Context, String, String?, Boolean) -> Boolean)? = null
    private var originalStopper: ((android.content.Context) -> Boolean)? = null
    private var originalIdleNotificationStopper: ((android.content.Context) -> Boolean)? = null
    private data class Call(val ctx: android.content.Context, val cfg: String, val title: String?, val reconnect: Boolean)
    private val calls = mutableListOf<Call>()
    private var stopCalls = 0
    // What the fake `stopper` reports back for a dispatch. Defaults to true (dispatch accepted),
    // matching VpnManager.stopVpn() on a healthy Context.startService(); the rejected-dispatch tests
    // below flip it to false to reproduce a rejected dispatch without a fake being the only
    // evidence -- they pair it with a real VpnManager.stopVpn() run against a startService()-
    // rejecting Context so both the fake-driven and the real-dispatch route are covered.
    private var stopDispatchResult = true
    private var idleNotificationStopCalls = 0
    // idleNotificationStopper is (Context) -> Boolean like `stopper`, so a rejected
    // ACTION_STOP_IF_IDLE dispatch is reproducible here. Defaults to true (accepted).
    private var idleNotificationStopResult = true

    @Before
    fun setUp() {
        ConnectionStateManager.setReconnectingHint(false)
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        ServerAutoSwitcher.setNoReplyThresholdForTest(2)
        ServerAutoSwitcher.setRepliedThresholdForTest(2)
        UserSettingsStore.saveStatusStallTimeoutSeconds(appContext, 2)
        originalStarter = ServerAutoSwitcher.starter
        ServerAutoSwitcher.starter = { ctx, config, title, reconnect -> calls.add(Call(ctx, config, title, reconnect)) }
        originalStopper = ServerAutoSwitcher.stopper
        ServerAutoSwitcher.stopper = { _ -> stopCalls += 1; stopDispatchResult }
        originalIdleNotificationStopper = ServerAutoSwitcher.idleNotificationStopper
        ServerAutoSwitcher.idleNotificationStopper = { _ -> idleNotificationStopCalls += 1; idleNotificationStopResult }
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf2")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        calls.clear()
        stopCalls = 0
        stopDispatchResult = true
        idleNotificationStopCalls = 0
        idleNotificationStopResult = true
        // ConnectionStateManager is a process-wide object: the stop-dispatch-rejected test
        // deliberately leaves DISCONNECTING + STOP_FAILED behind, so every test in this class starts
        // from a clean, explicitly known baseline instead of whatever ran before it.
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.clearStopFailure()
    }

    @After
    fun tearDown() {
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.clearStopFailure()
        originalStarter?.let { ServerAutoSwitcher.starter = it }
        originalStopper?.let { ServerAutoSwitcher.stopper = it }
        originalIdleNotificationStopper?.let { ServerAutoSwitcher.idleNotificationStopper = it }
        ServerAutoSwitcher.resetNoReplyThreshold()
        ServerAutoSwitcher.resetRepliedThreshold()
        ServerAutoSwitcher.setProbeRequestQueueForTest(null)
        ServerAutoSwitcher.v2HydrationCallback = null
        ServerAutoSwitcher.resetForTest()
    }

    // beginChainedSwitch reports whether a switch was actually begun. The watchdog relies on this
    // to avoid consuming a recovery attempt on a dispatch that never happened, so every path that
    // aborts internally must return false rather than looking like success.

    @Test
    fun beginChainedSwitch_returnsFalseWhenAutoSwitchDisabled() {
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, false)

        assertFalse(
            "a skipped switch is not a begun switch",
            ServerAutoSwitcher.beginChainedSwitch(appContext, "client\n", "RU")
        )
    }

    @Test
    fun beginChainedSwitch_returnsFalseWhenStopDispatchRejected() {
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        // VpnManager.startControllerService catches IllegalStateException from startService and
        // returns false -- the background-start restriction case.
        val rejectingContext = object : android.content.ContextWrapper(appContext) {
            override fun startService(service: android.content.Intent?): android.content.ComponentName? =
                throw IllegalStateException("background start not allowed")
        }

        assertFalse(
            "a rejected stop dispatch aborts the switch, so it must not report success",
            ServerAutoSwitcher.beginChainedSwitch(rejectingContext, "client\n", "RU")
        )
    }

    @Test
    fun switchesAfterThresholdUsingChainedStopStart() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        // Cross threshold (configured to 2s in setUp). This requests a stop and
        // arms a chained start pending NOTCONNECTED.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // No start should be triggered yet until NOTCONNECTED is observed.
        assertEquals(0, calls.size)

        // Engine reports teardown state; chained start should fire shortly after.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        // Give a bit more than the internal delay (350ms)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, calls.size)
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf2", current?.config)
    }

    // R19-3 (fix-cycle 20, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-10.md):
    // QG9-3 (fix-cycle 19) added !cfg.isNullOrBlank() hardening at both retry-commit read sites
    // (this test exercises the NOTCONNECTED-observed branch) plus a blank-rejecting write at
    // requestSwitchNow()'s pendingConfig assignment -- but shipped with zero test coverage; gate-10
    // verified by mutation that all three edits survive reversion with a green suite. Proves at
    // least one of those guards actually rejects a blank config end to end: server 2 (the "next"
    // server nextServerCircular() will select) has a blank config, so starting the engine with an
    // empty profile must never happen.
    @Test
    fun retryCommit_doesNotStartNextServerWithBlankConfig() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        // Cross threshold (configured to 2s in setUp) -- requests a stop and arms a chained start
        // to server 2, whose config is blank.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // Engine reports teardown complete; the retry-commit guard must reject the blank pending
        // config here instead of starting the engine with an empty profile.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "A blank config on the server selected by the switch must never reach starter() -- " +
                "QG9-3's !cfg.isNullOrBlank() hardening (both the requestSwitchNow() source " +
                "assignment and the retry-commit read guards) exists precisely to prevent this",
            0,
            calls.size
        )
    }

    // The blank-config fall-through above leaves the NOTCONNECTED level to reach the non-timeout
    // `else` branch in onEngineLevel(), where resetCycle = !shouldKeepCycle -- and shouldKeepCycle
    // reads reconnectingHint, which requestSwitchNow() had just set true for the (aborted) switch.
    // Pre-fix, cancel(resetCycle = false) never touched the hint, latching reconnectingHint=true with
    // no retry in flight -- which keeps OpenVpnService's reconnectPending guard satisfied and the
    // controller foreground-service notification retained indefinitely. Fixed by explicitly clearing
    // the hint, forcing state to DISCONNECTED, and clearing the controller notification in the
    // blank-config fall-through branch itself.
    //
    // Dispatching the full stopper() here (VpnManager.stopVpn() -> ACTION_STOP -> OpenVpnService's
    // user-stop teardown) would be wrong, even though the resulting DISCONNECTED -> DISCONNECTING
    // transition looks like it should be rejected as a no-op. It is not: ConnectionState.kt's
    // allowedFromDisconnected set contains DISCONNECTING, so the transition is accepted, and in the
    // adverse case (engine declines the redundant stop) it settles at a latched DISCONNECTING with
    // a spurious STOP_FAILED error instead of DISCONNECTED. A `stopCalls == 1` assertion would be
    // blind to that, because the fake `stopper` is a bare counter that never runs the real teardown
    // chain. This path therefore routes through the SEPARATE idleNotificationStopper
    // (VpnManager.stopControllerIfIdle() ->
    // ACTION_STOP_IF_IDLE), which never touches ConnectionStateManager at all -- see
    // OpenVpnServiceNotificationTest's blankConfigIdleStop_* tests below for real-path coverage of
    // that specific safety claim (not a fake). This test asserts stopper() (the ACTION_STOP /
    // DISCONNECTING-capable dispatcher) is NEVER invoked, and idleNotificationStopper is invoked
    // exactly once.
    @Test
    fun blankConfigFallThrough_clearsReconnectingHintAndUsesIdleNotificationStopperNotFullStop() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertTrue(
            "precondition: requestSwitchNow() must have set the hint true for the (blank-config) switch",
            ConnectionStateManager.reconnectingHint.value
        )

        // Simulates what OpenVpnService.syncEngineState() already did on the AIDL binder thread,
        // synchronously and BEFORE this (deferred, main-thread) onEngineLevel callback runs:
        // ConnectionStateManager.updateFromEngine() maps a terminal AIDL level to DISCONNECTED,
        // but since reconnectingHint is still true at that moment it re-maps the effective state to
        // CONNECTING instead (the "chained switch in progress, don't flash DISCONNECTED" rule) --
        // see ConnectionState.kt's updateFromEngine(). Reproducing that pre-latched state here is
        // what pins the regression: a fall-through
        // that only cleared the hint left this CONNECTING state with no later engine event ever
        // arriving to unstick it, since no retry was dispatched and the engine had already stopped.
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals("no retry should ever have been dispatched", 0, calls.size)
        assertFalse(
            "reconnectingHint must not remain latched true once the blank-config fall-through " +
                "settles with no retry in flight -- otherwise the controller FGS notification is " +
                "retained with nothing pending to justify it",
            ConnectionStateManager.reconnectingHint.value
        )
        assertEquals(
            "app state must reach a terminal DISCONNECTED, not stay latched at the CONNECTING " +
                "value syncEngineState()'s updateFromEngine() call had already retained before " +
                "this fall-through ran -- with no retry and no later engine event, nothing else " +
                "would ever move it off CONNECTING",
            ConnectionState.DISCONNECTED,
            ConnectionStateManager.state.value
        )
        assertEquals(
            "the blank-config fall-through must NOT dispatch the full stopper() (ACTION_STOP -> " +
                "OpenVpnService.startUserStopTeardown()) -- that path forces " +
                "ConnectionStateManager.updateState(DISCONNECTING) unconditionally against an " +
                "engine that has already stopped, which can latch state at " +
                "DISCONNECTING with a spurious STOP_FAILED error instead of settling at DISCONNECTED",
            0,
            stopCalls
        )
        assertEquals(
            "the blank-config fall-through must dispatch idleNotificationStopper() exactly once so " +
                "the controller's foreground notification actually clears, without going through " +
                "the full user-stop state machine",
            1,
            idleNotificationStopCalls
        )
    }

    // TIMEOUT TWIN of blankConfigFallThrough_clearsReconnectingHintAndUsesIdleNotificationStopperNotFullStop
    // above. Before this fix, scheduleStopRetryTimeout()'s runnable had a log-only `else` branch
    // for pendingConfig == null, so if the stop-retry timeout fired instead of NOTCONNECTED
    // arriving, reconnectingHint stayed true, state stayed CONNECTING, and the controller FGS
    // notification was retained forever -- the identical latch the NOTCONNECTED sibling fixes,
    // just reached via the timeout path.
    //
    // Unlike that sibling, this path must NOT route through idleNotificationStopper(): the timeout
    // fired precisely because there is no confirmation the engine is actually idle (the earlier
    // stop was dispatched with preserveReconnectHint = true, which never arms OpenVpnService's own
    // confirmation/retry machinery). This asserts the opposite dispatcher pairing from the
    // NOTCONNECTED sibling: stopper() (the real ACTION_STOP / DISCONNECTING-capable, self-resolving
    // teardown) is invoked exactly once, and idleNotificationStopper() is NEVER invoked.
    @Test
    fun stopRetryTimeoutBlankConfig_clearsReconnectingHintAndDispatchesRealStopperNotIdleNotification() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertTrue(
            "precondition: requestSwitchNow() must have set the hint true for the (blank-config) switch",
            ConnectionStateManager.reconnectingHint.value
        )

        // Simulates what OpenVpnService.syncEngineState() would have done had a stray engine level
        // arrived while reconnectingHint was still true -- see the NOTCONNECTED sibling test's
        // comment for the full mechanism. Reproducing that pre-latched state here is what proves
        // this branch, not just the hint clear alone, resolves the latch.
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        // No NOTCONNECTED ever arrives; only the STOP_RETRY_TIMEOUT_MS (5s) fallback can resolve
        // this cycle.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals("no retry should ever have been dispatched (config was blank)", 0, calls.size)
        assertFalse(
            "reconnectingHint must not remain latched true once the stop-retry timeout settles " +
                "with no retry in flight -- otherwise the controller FGS notification is retained " +
                "with nothing pending to justify it",
            ConnectionStateManager.reconnectingHint.value
        )
        // This branch must not publish DISCONNECTED itself the instant stopper() returns true.
        // `true` from VpnManager.stopVpn() only means Context.startService() ACCEPTED the intent
        // for delivery; OpenVpnService.onStartCommand() has not run, so nothing has begun tearing
        // the tunnel down yet. Publishing DISCONNECTED there was a false idle report for the whole
        // delivery window. The branch now publishes nothing on an accepted dispatch and leaves the
        // state where it was until the controller reports otherwise. The fake `stopper` here never
        // delivers the intent to a service, so CONNECTING is what a caller observes -- and that is
        // the point: this class must not be the one to move it.
        //
        // The latch this branch exists to close is still closed, just by the controller rather than
        // by this class: OpenVpnServiceNotificationTest's
        // stopRetryTimeoutBlankConfig_acceptedStopDispatch_* tests drive the delivered ACTION_STOP
        // end to end and prove CONNECTING -> DISCONNECTING -> DISCONNECTED.
        assertEquals(
            "an ACCEPTED stop dispatch must leave connection state untouched -- startService() " +
                "returning true is a delivery acknowledgment, not a teardown confirmation, and " +
                "anticipating it with DISCONNECTED reports a still-live tunnel as stopped",
            ConnectionState.CONNECTING,
            ConnectionStateManager.state.value
        )
        assertEquals(
            "an accepted dispatch is not a stop failure",
            ConnectionStateManager.VpnError.NONE,
            ConnectionStateManager.error.value
        )
        assertEquals(
            "the stop-retry-timeout branch must dispatch the REAL stopper() (ACTION_STOP -> " +
                "OpenVpnService's self-resolving user-stop teardown) exactly once -- unlike the " +
                "NOTCONNECTED sibling, this path has no confirmation the engine is actually idle, " +
                "so idleNotificationStopper()'s notification-only, no-engine-contact remedy is not " +
                "safe to reuse here",
            1,
            stopCalls
        )
        assertEquals(
            "idleNotificationStopper() must NEVER be invoked from the stop-retry-timeout branch -- " +
                "that dispatcher's whole premise is that the engine has already confirmed idle, " +
                "which does not hold when the timeout (not NOTCONNECTED) is what resolved this cycle",
            0,
            idleNotificationStopCalls
        )
    }

    // The sibling test above proves the stop-retry-timeout branch dispatches the real stopper();
    // it could not see WHETHER that dispatch was accepted, because `stopper` was typed
    // (Context) -> Unit and the branch forced DISCONNECTED before dispatching either way. When
    // Android rejects the background startService(), VpnManager.startControllerService() catches it
    // and returns false, so ACTION_STOP never reaches OpenVpnService: no startUserStopTeardown(),
    // and therefore none of the service's own confirmation/retry machinery armed either. Settling
    // to DISCONNECTED there reports a tunnel nobody managed to stop as stopped.
    //
    // Deliberately NOT driven by a bare fake returning false: the Boolean under test is produced by
    // the real VpnManager.stopVpn() -> startControllerService() rejection path (the production
    // `stopper` wiring), run against a Context whose startService() throws exactly what the
    // background-start restriction throws. Falsifiability: reverting the fix (force DISCONNECTED,
    // then dispatch, ignoring the result) fails this test on the state assertion.
    @Test
    fun stopRetryTimeoutBlankConfig_rejectedStopDispatchNeverReportsDisconnectedAndSurfacesStopFailure() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        val dispatchContext = ToggleableStartServiceContext(appContext)
        ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }

        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertTrue(
            "precondition: requestSwitchNow() must have set the hint true for the (blank-config) switch",
            ConnectionStateManager.reconnectingHint.value
        )
        // The pre-retry stop (dispatched with preserveReconnectHint = true) was accepted, which is
        // why this cycle is now waiting on a NOTCONNECTED that never comes.
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        // From here every startService() is rejected -- the background-start restriction case.
        dispatchContext.rejecting = true
        val dispatchesBeforeTimeout = dispatchContext.startServiceCalls

        // Stop-retry timeout fires (5s) -> attempt 1, then the two bounded re-dispatches 1s apart.
        // Sampled between attempts, not just at the end: settling to DISCONNECTED for even one
        // rejected attempt is the lie under test, and asserting only the final state would let a
        // "DISCONNECTED now, DISCONNECTING later" mutation through (DISCONNECTED -> DISCONNECTING
        // is an allowed transition).
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(
            "after the first rejected dispatch the state must be untouched, not DISCONNECTED",
            ConnectionState.CONNECTING,
            ConnectionStateManager.state.value
        )
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_200))
        assertEquals(
            "after the second rejected dispatch the state must still be untouched",
            ConnectionState.CONNECTING,
            ConnectionStateManager.state.value
        )
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_800))

        assertEquals("no retry start should ever have been dispatched (config was blank)", 0, calls.size)
        assertEquals(
            "a rejected stop dispatch must be re-dispatched up to the bounded attempt limit -- the " +
                "intent never reached OpenVpnService, so the service cannot retry it itself",
            3,
            dispatchContext.startServiceCalls - dispatchesBeforeTimeout
        )
        assertNotEquals(
            "a stop that was never even handed to the controller must NOT be reported as a " +
                "completed disconnect: the tunnel may still be live and nothing is pending to stop it",
            ConnectionState.DISCONNECTED,
            ConnectionStateManager.state.value
        )
        assertEquals(
            "an exhausted stop dispatch must settle on OpenVpnService.markStopFailure()'s end state " +
                "(DISCONNECTING), the only state ConnectionControlsPresenter renders the stop-failed " +
                "status for and one that keeps an ACTIVE stop button for a manual retry",
            ConnectionState.DISCONNECTING,
            ConnectionStateManager.state.value
        )
        assertEquals(
            "the user-visible error must say the stop failed, not stay silent",
            ConnectionStateManager.VpnError.STOP_FAILED,
            ConnectionStateManager.error.value
        )
    }

    // Companion to the test above: the dispatch-failure path must be observably DIFFERENT from the
    // success path, and the bounded re-dispatch must be a real re-dispatch (not just a delayed
    // give-up). Same real VpnManager.stopVpn() route as above; the rejection is lifted between
    // attempts.
    //
    // "Different" here is narrow. It is not "DISCONNECTED vs DISCONNECTING+STOP_FAILED": an
    // accepted dispatch publishes NOTHING and hands the outcome to OpenVpnService. The difference
    // is that an EXHAUSTED dispatch still
    // surfaces DISCONNECTING + STOP_FAILED from here (nothing else can -- the intent never reached
    // the service), while an accepted one leaves state alone and stops re-dispatching. This test
    // pins the accepted half: the retry chain must stop, and no stop failure may be surfaced.
    @Test
    fun stopRetryTimeoutBlankConfig_rejectedStopDispatchRetriesAndStopsEscalatingOnceAccepted() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        val dispatchContext = ToggleableStartServiceContext(appContext)
        ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }

        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        dispatchContext.rejecting = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        assertEquals(
            "the first, rejected dispatch must leave the state exactly where it was -- settling to " +
                "DISCONNECTED here is the regression this fix closes",
            ConnectionState.CONNECTING,
            ConnectionStateManager.state.value
        )

        // The restriction lifts (e.g. the app is foregrounded again) before the bounded retry runs.
        dispatchContext.rejecting = false
        val dispatchesBeforeAcceptedRetry = dispatchContext.startServiceCalls
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

        assertEquals(
            "the bounded retry must be a real re-dispatch, and the accepted attempt is the last one",
            1,
            dispatchContext.startServiceCalls - dispatchesBeforeAcceptedRetry
        )
        assertEquals(
            "once ACTION_STOP is accepted by the controller, OpenVpnService's user-stop teardown " +
                "owns the outcome -- this branch must publish no state of its own. It has " +
                "not been delivered to a service here, so CONNECTING is what remains; the delivered " +
                "continuation is covered end to end in OpenVpnServiceNotificationTest",
            ConnectionState.CONNECTING,
            ConnectionStateManager.state.value
        )
        assertEquals(
            "a dispatch that succeeded on retry is not a stop failure",
            ConnectionStateManager.VpnError.NONE,
            ConnectionStateManager.error.value
        )
        assertFalse(
            "an accepted dispatch must end the bounded re-dispatch chain -- leaving it armed " +
                "fires a stale ACTION_STOP against whatever runs next",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // Falsification guard for the assertion above: had the chain stayed armed, a further
        // TIMEOUT_STOP_DISPATCH_RETRY_DELAY_MS would produce another dispatch.
        val dispatchesAfterAccept = dispatchContext.startServiceCalls
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertEquals(
            "no further stop must be dispatched after one was accepted",
            dispatchesAfterAccept,
            dispatchContext.startServiceCalls
        )
        assertEquals("no retry start should ever have been dispatched (config was blank)", 0, calls.size)
    }

    // When the blank-config timeout's first ACTION_STOP is rejected, a re-dispatch is armed
    // TIMEOUT_STOP_DISPATCH_RETRY_DELAY_MS later. Nothing used to cancel it on a fresh cycle:
    // cancel() clears it, but beginChainedSwitch() never called cancel(), so a switch beginning
    // inside that one-second window kept the stale retry armed -- and when the retry then succeeded
    // (typically right after the app returned to the foreground, which is also what lifts the
    // background-start restriction that caused the rejection), it dispatched a real NON-PRESERVE
    // ACTION_STOP against the cycle that had just started. That reaches
    // OpenVpnService.startUserStopTeardown(), which calls ServerAutoSwitcher.cancelForUserStop() --
    // so the stale retry does not merely stop the engine, it also tears down the new switch cycle.
    //
    // Asserted on the dispatched intents rather than a raw dispatch count, because the fresh cycle
    // legitimately dispatches its own ACTION_STOP (preserveReconnectHint = true). Only a
    // non-preserve ACTION_STOP after the new cycle begins is the defect.
    @Test
    fun stopRetryTimeoutBlankConfig_pendingStopRedispatchIsCancelledByAFreshChainedSwitch() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        val dispatchContext = ToggleableStartServiceContext(appContext)
        ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }

        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        // The blank-config timeout fires while the background-start restriction is active, so its
        // ACTION_STOP is rejected and a bounded re-dispatch is armed.
        dispatchContext.rejecting = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        assertTrue(
            "precondition: a rejected stop dispatch must arm a bounded re-dispatch",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // The user comes back to the foreground and a fresh cycle starts INSIDE the retry window.
        dispatchContext.rejecting = false
        dispatchContext.dispatchedIntents.clear()
        assertTrue(
            "precondition: the fresh chained switch must actually begin",
            ServerAutoSwitcher.beginChainedSwitch(dispatchContext, "conf1", "RU")
        )

        assertFalse(
            "a fresh cycle must supersede the stale stop re-dispatch, not run alongside it",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // Let the window the stale retry would have fired in elapse.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

        val staleStops = dispatchContext.dispatchedIntents.filter {
            it.getStringExtra(VpnManager.actionKey(appContext)) == VpnManager.ACTION_STOP &&
                !it.getBooleanExtra(VpnManager.extraPreserveReconnectKey(appContext), false)
        }
        assertEquals(
            "no non-preserve ACTION_STOP may be dispatched once a fresh cycle has begun -- that is " +
                "the stale retry stopping the connection the user just started (and, via " +
                "startUserStopTeardown() -> cancelForUserStop(), killing the new cycle with it)",
            0,
            staleStops.size
        )
        assertTrue(
            "sanity: the fresh cycle's own preserve-branch ACTION_STOP must still have been dispatched",
            dispatchContext.dispatchedIntents.any {
                it.getStringExtra(VpnManager.actionKey(appContext)) == VpnManager.ACTION_STOP &&
                    it.getBooleanExtra(VpnManager.extraPreserveReconnectKey(appContext), false)
            }
        )
    }

    // Supersession of the pending stop re-dispatch is only earned once the superseding stop has
    // actually been ACCEPTED for delivery. beginChainedSwitch() used to drop it up front, before
    // dispatching its own preserve-branch ACTION_STOP; when that replacement dispatch was itself
    // rejected (same background-start restriction that caused the first rejection), the abort path's
    // cancel(resetCycle = true) then also removed the freshly armed stop-retry timeout -- so the
    // prior, possibly still live tunnel was left with no delivered stop anywhere: not in this class
    // (both runnables gone) and not in OpenVpnService either (the rejected ACTION_STOP never reached
    // it, so none of its confirmation-timeout/retry machinery was ever armed). The abort must put the
    // detached re-dispatch back.
    @Test
    fun beginChainedSwitch_rejectedReplacementStopRestoresThePriorStopRedispatch() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        val dispatchContext = ToggleableStartServiceContext(appContext)
        ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }

        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        // The blank-config timeout's ACTION_STOP is rejected, arming the bounded re-dispatch. At this
        // point that re-dispatch is the ONLY teardown attempt still owed to the prior tunnel.
        dispatchContext.rejecting = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        assertTrue(
            "precondition: a rejected stop dispatch must arm a bounded re-dispatch",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // A chained switch begins inside the retry window while the restriction is STILL active, so
        // its own replacement stop is rejected too and the switch aborts.
        dispatchContext.dispatchedIntents.clear()
        assertFalse(
            "precondition: the replacement stop dispatch is rejected, so the switch must abort",
            ServerAutoSwitcher.beginChainedSwitch(dispatchContext, "conf1", "RU")
        )

        assertTrue(
            "an aborted chained switch supersedes nothing: dropping the prior re-dispatch here " +
                "leaves the possibly live prior tunnel with no delivered stop and no bounded " +
                "teardown retry anywhere",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // The restriction lifts and the restored re-dispatch runs, proving it was genuinely
        // re-scheduled and still executable -- not merely a non-null field.
        dispatchContext.rejecting = false
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

        val realStops = dispatchContext.dispatchedIntents.filter {
            it.getStringExtra(VpnManager.actionKey(appContext)) == VpnManager.ACTION_STOP &&
                !it.getBooleanExtra(VpnManager.extraPreserveReconnectKey(appContext), false)
        }
        assertEquals(
            "the restored re-dispatch must actually fire and deliver the real, non-preserve " +
                "ACTION_STOP the prior tunnel is still owed",
            1,
            realStops.size
        )
        assertFalse(
            "an accepted dispatch ends the bounded chain",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
        assertTrue(
            "the restoration must be recorded, not silent",
            ShadowLog.getLogs().any {
                it.tag == logTag &&
                    it.msg.contains("restoring the superseded stop-retry-timeout re-dispatch")
            }
        )
        assertEquals("no retry start should ever have been dispatched (config was blank)", 0, calls.size)
    }

    // Companion to the test above, pinning the OTHER half of restoring a superseded re-dispatch:
    // the restore must put back the SAME Runnable, not arm an equivalent-looking fresh chain.
    //
    // The bounded teardown chain carries its attempt number inside the posted Runnable's closure
    // (dispatchStopAfterStopRetryTimeout captures `attempt + 1`), so re-posting that exact instance
    // is what makes the chain still terminate at TIMEOUT_STOP_DISPATCH_MAX_ATTEMPTS. Re-arming a
    // fresh attempt=1 chain instead would satisfy every assertion in the test above -- the restore
    // still happens, still logs, still fires a real ACTION_STOP -- while silently converting a
    // bounded chain into an unbounded one: every aborted chained switch would hand the tunnel three
    // more attempts, so a device stuck under the background-start restriction would re-dispatch
    // forever and NEVER escalate to the STOP_FAILED state that tells the user the stop failed and
    // gives them a manual retry button.
    //
    // Driving the abort twice is what makes this falsifiable: the chain only reaches its cap
    // (attempt 1 rejected up front, then 2 and 3 across the two restores) if the attempt count
    // genuinely survives each restore.
    @Test
    fun beginChainedSwitch_restoredStopRedispatchKeepsTheBoundedChainsAttemptCount() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        val dispatchContext = ToggleableStartServiceContext(appContext)
        ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }

        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        // The restriction stays active for the whole test, so every dispatch below is rejected.
        dispatchContext.rejecting = true

        // Attempt 1 is rejected by the blank-config timeout; the chain arms attempt 2.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(
            "precondition: a rejected stop dispatch must arm a bounded re-dispatch",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // Abort #1 detaches and restores the attempt-2 re-dispatch, which then runs and is rejected,
        // arming attempt 3.
        assertFalse(
            "precondition: the replacement stop dispatch is rejected, so the switch must abort",
            ServerAutoSwitcher.beginChainedSwitch(dispatchContext, "conf1", "RU")
        )
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertTrue(
            "attempt 2 was rejected and the cap is not reached yet, so attempt 3 must be armed",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
        assertEquals(
            "the chain has not been exhausted yet, so no stop failure may be surfaced",
            ConnectionStateManager.VpnError.NONE,
            ConnectionStateManager.error.value
        )

        // Abort #2 detaches and restores the attempt-3 re-dispatch. That attempt is the cap.
        assertFalse(
            "precondition: the second replacement stop dispatch is rejected too",
            ServerAutoSwitcher.beginChainedSwitch(dispatchContext, "conf1", "RU")
        )
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

        assertFalse(
            "the restored chain must still terminate at TIMEOUT_STOP_DISPATCH_MAX_ATTEMPTS -- an " +
                "attempt counter reset by the restore makes the teardown chain unbounded and it " +
                "never escalates",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
        assertEquals(
            "an exhausted restored chain must settle on markStopFailure()'s end state, exactly like " +
                "an exhausted un-restored one",
            ConnectionState.DISCONNECTING,
            ConnectionStateManager.state.value
        )
        assertEquals(
            "the user must be told the stop failed rather than left with a silent retry loop",
            ConnectionStateManager.VpnError.STOP_FAILED,
            ConnectionStateManager.error.value
        )
        assertEquals("no retry start should ever have been dispatched (config was blank)", 0, calls.size)
    }

    // The same custody rule as beginChainedSwitch_rejectedReplacementStopRestoresThePriorStopRedispatch
    // above, at requestSwitchNow()'s switch branch. That branch has a cancel(resetCycle = false) of
    // its own ahead of its replacement stop, and cancel() clears timeoutStopDispatchRunnable
    // unconditionally -- so the prior cycle's re-dispatch was discarded BEFORE the replacement stop
    // was even attempted. When that replacement dispatch was then rejected, the abort branch's
    // cancel(resetCycle = true) cleared the freshly armed stop-retry timeout too, leaving the prior,
    // possibly still live tunnel with no stop anywhere: not in this class, and not in OpenVpnService
    // either, since the original ACTION_STOP was rejected before the service ever saw it, so none of
    // its confirmation-timeout/retry/STOP_FAILED machinery was armed. The switch branch must detach
    // the re-dispatch and hand it back on abort, exactly as the chained-switch entry point does.
    @Test
    fun immediateSwitch_rejectedReplacementStopRestoresThePriorStopRedispatch() {
        // Three servers so the second switch still has a next server that is not the cycle start.
        val blankMiddleServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", ""),
            Server(3, "n3", "c3", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf3")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankMiddleServers)
        SelectedCountryStore.resetIndex(appContext)

        val dispatchContext = ToggleableStartServiceContext(appContext)
        ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }

        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        // The blank-config timeout's ACTION_STOP is rejected, arming the bounded re-dispatch. From
        // here it is the ONLY teardown attempt still owed to the prior tunnel.
        dispatchContext.rejecting = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(
            "precondition: a rejected stop dispatch must arm a bounded re-dispatch",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // An AUTH_FAILED level arrives inside the retry window while the restriction is STILL
        // active, so requestSwitchNow()'s switch branch runs and its own replacement stop is
        // rejected too.
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        dispatchContext.dispatchedIntents.clear()
        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_AUTH_FAILED, source)

        assertTrue(
            "an aborted immediate switch supersedes nothing: its cancel() dropped the prior " +
                "re-dispatch before the replacement stop was even attempted, so without a restore " +
                "the possibly live prior tunnel is left with no delivered stop and no bounded " +
                "teardown retry anywhere",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // The restriction lifts and the restored re-dispatch runs, proving it was genuinely
        // re-scheduled and still executable -- not merely a non-null field.
        dispatchContext.rejecting = false
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

        val realStops = dispatchContext.dispatchedIntents.filter {
            it.getStringExtra(VpnManager.actionKey(appContext)) == VpnManager.ACTION_STOP &&
                !it.getBooleanExtra(VpnManager.extraPreserveReconnectKey(appContext), false)
        }
        assertEquals(
            "the restored re-dispatch must actually fire and deliver the real, non-preserve " +
                "ACTION_STOP the prior tunnel is still owed",
            1,
            realStops.size
        )
        assertFalse(
            "an accepted dispatch ends the bounded chain",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
        assertTrue(
            "the restoration must be recorded, not silent",
            ShadowLog.getLogs().any {
                it.tag == logTag &&
                    it.msg.contains("restoring the superseded stop-retry-timeout re-dispatch")
            }
        )
    }

    // Third instance of the same custody rule, on the path that has no superseding dispatch at all:
    // onEngineLevel()'s generic timer cancellation. Once the blank-config timeout's ACTION_STOP is
    // rejected, the bounded re-dispatch is the ONLY teardown attempt still owed to the prior tunnel
    // (that ACTION_STOP never reached OpenVpnService, so none of the service's own
    // confirmation-timeout/retry/STOP_FAILED machinery was armed). Any ordinary engine level landing
    // inside the one-second retry window used to reach onEngineLevel()'s non-timeout `else` branch,
    // whose unconditional cancel() removed the re-dispatch -- without dispatching any replacement
    // stop, because observing a level tears nothing down. LEVEL_CONNECTED is the expected traffic in
    // that window: the timeout fired precisely because nothing ever confirmed the engine stopped, so
    // the live old tunnel keeps reporting itself connected.
    @Test
    fun engineLevelDuringRetryWindow_keepsTheRejectedStopRedispatchArmed() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        val dispatchContext = ToggleableStartServiceContext(appContext)
        ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }

        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        // The blank-config timeout's ACTION_STOP is rejected, arming the bounded re-dispatch.
        dispatchContext.rejecting = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(
            "precondition: a rejected stop dispatch must arm a bounded re-dispatch",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // The old tunnel -- which nobody has managed to stop -- reports itself connected inside the
        // one-second retry window.
        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_CONNECTED, source)

        assertTrue(
            "an ordinary engine level cancels this object's timers but dispatches no replacement " +
                "teardown, so it must not take the prior tunnel's last remaining stop attempt with " +
                "it -- nothing else anywhere is still trying to stop that tunnel",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // The restriction lifts and the preserved re-dispatch runs, proving it stayed genuinely
        // scheduled and executable -- not merely a non-null field.
        dispatchContext.rejecting = false
        dispatchContext.dispatchedIntents.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

        val realStops = dispatchContext.dispatchedIntents.filter {
            it.getStringExtra(VpnManager.actionKey(appContext)) == VpnManager.ACTION_STOP &&
                !it.getBooleanExtra(VpnManager.extraPreserveReconnectKey(appContext), false)
        }
        assertEquals(
            "the preserved re-dispatch must actually fire and deliver the real, non-preserve " +
                "ACTION_STOP the prior tunnel is still owed",
            1,
            realStops.size
        )
        assertFalse(
            "an accepted dispatch ends the bounded chain",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )
    }

    // The counterpart to the test above: LEVEL_NOTCONNECTED is the one level that legitimately
    // drops the re-dispatch, because it IS the engine confirming teardown. Preserving it there would
    // fire a real ACTION_STOP at an already-idle engine, whose startUserStopTeardown() forces
    // DISCONNECTING with no guaranteed further engine event to resolve it -- the latched
    // DISCONNECTING + spurious STOP_FAILED documented on idleNotificationStopper.
    @Test
    fun engineConfirmedNotConnectedDuringRetryWindow_dropsTheRejectedStopRedispatch() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        val dispatchContext = ToggleableStartServiceContext(appContext)
        ServerAutoSwitcher.stopper = { ctx -> VpnManager.stopVpn(ctx) }

        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        dispatchContext.rejecting = true
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertTrue(
            "precondition: a rejected stop dispatch must arm a bounded re-dispatch",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        // The engine confirms it is down. Nothing is owed any more.
        ServerAutoSwitcher.onEngineLevel(dispatchContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)

        assertFalse(
            "an engine-confirmed teardown discharges the obligation: keeping the re-dispatch armed " +
                "would send a real ACTION_STOP to an already-idle engine and risk latching at " +
                "DISCONNECTING with a spurious STOP_FAILED",
            ServerAutoSwitcher.hasPendingStopDispatchForTest()
        )

        dispatchContext.rejecting = false
        dispatchContext.dispatchedIntents.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

        assertTrue(
            "and no stop may be dispatched afterwards either",
            dispatchContext.dispatchedIntents.none {
                it.getStringExtra(VpnManager.actionKey(appContext)) == VpnManager.ACTION_STOP
            }
        )
    }

    // The NOTCONNECTED blank-config fall-through inspects idleNotificationStopper()'s dispatch
    // result rather than discarding it -- but deliberately only LOGS it, NOT escalating the way
    // the timeout twin's rejected
    // stopper() is. This test pins that asymmetry, because the obvious "make both twins identical"
    // refactor would be a regression: this path is reached only AFTER the engine reported
    // LEVEL_NOTCONNECTED, so DISCONNECTED is the truthful state and forcing DISCONNECTING +
    // STOP_FAILED would re-open exactly the DISCONNECTING latch this branch exists to avoid.
    @Test
    fun blankConfigFallThrough_rejectedIdleNotificationDispatchStaysDisconnectedAndDoesNotEscalate() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        // The controller refuses the ACTION_STOP_IF_IDLE dispatch (background-start restriction).
        idleNotificationStopResult = false

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))

        assertEquals(
            "the rejection must have been observed at all -- the whole point of typing " +
                "idleNotificationStopper as (Context) -> Boolean",
            1,
            idleNotificationStopCalls
        )
        assertEquals(
            "state must stay DISCONNECTED even though the notification-cleanup dispatch was " +
                "refused: this path is only reachable after the engine reported LEVEL_NOTCONNECTED, " +
                "so DISCONNECTED is truthful and MainViewModel offering Connect is correct. A " +
                "rejected ACTION_STOP_IF_IDLE costs only the controller reap, never state accuracy",
            ConnectionState.DISCONNECTED,
            ConnectionStateManager.state.value
        )
        assertEquals(
            "a refused notification cleanup must NOT be surfaced as a stop failure -- the engine " +
                "already stopped, so STOP_FAILED would reintroduce the DISCONNECTING latch",
            ConnectionStateManager.VpnError.NONE,
            ConnectionStateManager.error.value
        )
        assertEquals(
            "the rejection must NOT escalate into the timeout twin's real stopper()/ACTION_STOP " +
                "bounded retry: that dispatcher forces DISCONNECTING via startUserStopTeardown() " +
                "against an engine that has already confirmed idle",
            0,
            stopCalls
        )
        assertEquals(
            "and it must not be re-dispatched either -- syncEngineState()'s in-process " +
                "exitControllerForeground() on the next snapshot re-poll already clears the " +
                "residue unconditionally, so a bounded retry here would be duplicate machinery",
            1,
            idleNotificationStopCalls
        )
        assertTrue(
            "the refused dispatch must be recorded, not swallowed",
            ShadowLog.getLogs().any {
                it.tag == logTag && it.msg.contains("Controller notification cleanup dispatch rejected")
            }
        )
    }

    // Rejects startService() the way Android's background-start restriction does, on demand.
    // VpnManager.startControllerService() catches IllegalStateException and returns false, which is
    // the exact production signal the rejected-dispatch handling inspects.
    private class ToggleableStartServiceContext(base: android.content.Context) :
        android.content.ContextWrapper(base) {
        var rejecting = false
        var startServiceCalls = 0
        // The stale-re-dispatch test must tell a STALE non-preserve ACTION_STOP apart from a fresh cycle's own
        // preserve-branch ACTION_STOP, so counting dispatches is not enough -- record them.
        val dispatchedIntents = mutableListOf<android.content.Intent>()

        override fun startService(service: android.content.Intent?): android.content.ComponentName? {
            startServiceCalls += 1
            if (rejecting) throw IllegalStateException("background start not allowed")
            service?.let { dispatchedIntents += it }
            return super.startService(service)
        }
    }

    @Test
    fun startsTimerForServerRepliedAndSwitches() {
        // Trigger timer on SERVER_REPLIED
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED, source)
        // After 1s, remaining should be 4 (replied threshold is 5s with settings=2)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(4, ServerAutoSwitcher.remainingSeconds.value)
        // Cross threshold (5s) -> should request stop and arm chained start
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        // No immediate start until NOTCONNECTED
        assertEquals(0, calls.size)
        // Now report NOTCONNECTED and allow delayed start
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, calls.size)
        assertEquals(true, calls.first().reconnect)
    }

    @Test
    fun cancelsOnStateChange() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        // Cancel before crossing the (test) threshold of 2 seconds
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_START, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, calls.size)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf1", current?.config)
    }

    @Test
    fun authFailedStartsChainedSwitchImmediately() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        assertEquals(2, ServerAutoSwitcher.remainingSeconds.value)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_AUTH_FAILED, source)
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)
        assertEquals(0, calls.size)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, calls.size)
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
    }

    // PR #126 round 13 (Codex P1, comment 3734974189): the poll loop can re-deliver the SAME
    // cached terminal snapshot (e.g. LEVEL_NONETWORK) on every ~2s poll cycle without it ever
    // going stale, because applyStatusSnapshot() restores lastStatusSnapshotMs to the snapshot's
    // OWN timestamp, not "now". Before the fix, a duplicate dispatch of an already-in-progress
    // immediate-switch level fell through to the generic timeoutLevels/else block and hit
    // `else -> cancel(...)`, silently cancelling the switch the FIRST dispatch had already
    // correctly begun. Verify the duplicate is a no-op and the original switch still completes.
    @Test
    fun duplicateImmediateSwitchDispatchDoesNotCancelInProgressSwitch() {
        // Get an active timer running so the first LEVEL_NONETWORK dispatch takes the
        // immediate-switch fast path (timerActive || isConnecting).
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)

        // First dispatch: triggers requestSwitchNow() -> waitingStopForRetry=true, pending
        // config armed, engine stop requested. No start yet until NOTCONNECTED is observed.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NONETWORK, "AIDL")
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)
        assertEquals(0, calls.size)

        // Duplicate dispatch of the IDENTICAL level while the switch is still in progress
        // (waitingStopForRetry still true). Must be a no-op: the pending switch must survive.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NONETWORK, "AIDL")

        // The switch armed by the FIRST dispatch must still complete normally.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "duplicate dispatch must not cancel the switch already in progress",
            1,
            calls.size
        )
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
    }

    // Bug 86cb35fbt, fix-cycle 6 (manual QA B23, docs/qa-evidence/86cb35fbt-vpn-foreground-
    // service-crash-qa-2.md "Secondary finding" section): a stale/re-delivered engine level
    // (e.g. a spurious LEVEL_CONNECTED flash from a Service instance racing an unrelated stop
    // path) arriving WHILE waitingStopForRetry is true used to fall through to the generic
    // `else -> cancel(...)` branch, silently discarding the pending retry -- with almost no log
    // trace, since cancel()'s only log line requires timerActive/seconds, both already reset to
    // false/0 during this wait window. The real, later LEVEL_NOTCONNECTED confirmation then had
    // no pending retry left to act on, so the promised switch to the next server was silently
    // dropped. Verify the spurious level is ignored and the real NOTCONNECTED still completes
    // the retry.
    @Test
    fun staleLevelDuringStopForRetryDoesNotSilentlyDropPendingRetry() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        // Cross threshold (2s) -> requests stop, arms waitingStopForRetry with pendingConfig=conf2.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // Spurious/stale level (e.g. a stray AIDL snapshot) arrives before the real NOTCONNECTED.
        // Before the fix this reached the unconditional else-branch cancel(...), wiping the
        // pending retry.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, "AIDL")
        assertEquals(
            "a stale level while waiting for the stop-before-retry confirmation must not start a switch itself",
            0,
            calls.size
        )

        // The real NOTCONNECTED confirmation must still resolve the pending retry.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "the pending retry must not be silently dropped by a stale intervening level",
            1,
            calls.size
        )
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
    }

    // Bug 86cb35fbt, fix-cycle 6 (manual QA B24, same evidence file): a stale/re-delivered
    // timeoutLevels level (e.g. a re-delivered LEVEL_CONNECTING_NO_SERVER_REPLY_YET snapshot)
    // arriving WHILE waitingStopForRetry is true used to reach the timeoutLevels branch and
    // start(...) a brand-new competing timer, since timerActive is false during this wait
    // window. Verify no competing timer is started and the real NOTCONNECTED confirmation still
    // drives exactly one retry.
    @Test
    fun staleTimeoutLevelDuringStopForRetryDoesNotStartCompetingTimer() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)

        // Stale re-delivered timeoutLevels snapshot while waiting for the stop-before-retry
        // confirmation. Before the fix this would call start(...), reporting a fresh
        // remainingSeconds value and racing the pending retry.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, "AIDL")
        assertEquals(
            "a stale timeoutLevels snapshot must not start a competing timer while waiting for stop-before-retry confirmation",
            null,
            ServerAutoSwitcher.remainingSeconds.value
        )

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "exactly one retry must fire, driven only by the real NOTCONNECTED confirmation",
            1,
            calls.size
        )
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
    }

    // Fix-cycle 7 review (docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-7.md,
    // R7-1/R7-4): the cycle-6 guard above stops ServerAutoSwitcher from silently dropping the
    // pending retry, but ServerAutoSwitcher is not the only consumer of an engine level --
    // OpenVpnService.syncEngineState() also forwards every level to
    // ConnectionStateManager.updateFromEngine(), unconditionally. Before the R7-1 fix, the same
    // stale LEVEL_CONNECTED this guard ignores still reached updateFromEngine() and cleared
    // reconnectingHint / flipped state CONNECTING -> CONNECTED, so by the time this retry's
    // ACTION_START fired, ConnectionStateManager read state=DISCONNECTED / hint=false -- exactly
    // the condition that defeats OpenVpnService's stopAfterOneShotSyncConfirmedRunnable,
    // VpnManager.stopControllerIfIdle, and syncEngineState's reconnectPending FGS guard (see the
    // review file's Verification section, PROBE detail, for the exact assertion this test
    // inverts: REVIEW-PROBE calls=1 stateAtStart=DISCONNECTED hintAtStart=false / expected
    // CONNECTING but was DISCONNECTED). This test interleaves updateFromEngine() after each
    // onEngineLevel() call, matching OpenVpnService.syncEngineState()'s real production ordering,
    // and asserts the invariant holds AT THE MOMENT starter() is invoked -- the only place R7-1 is
    // observable. Falsifiability: reverting the two ConnectionStateManager re-assertion calls
    // added at both retry-commit sites in ServerAutoSwitcher.kt must make this fail with
    // expected:<CONNECTING> but was:<DISCONNECTED>.
    @Test
    fun staleLevelDuringStopForRetry_reconnectInvariantHoldsAtRetryDispatch() {
        var stateAtDispatch: ConnectionState? = null
        var hintAtDispatch: Boolean? = null
        ServerAutoSwitcher.starter = { ctx, config, title, reconnect ->
            stateAtDispatch = ConnectionStateManager.state.value
            hintAtDispatch = ConnectionStateManager.reconnectingHint.value
            calls.add(Call(ctx, config, title, reconnect))
        }
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, null)
        // Cross threshold (2s) -> requests stop, arms waitingStopForRetry with pendingConfig=conf2.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // Spurious/stale LEVEL_CONNECTED, interleaved with updateFromEngine() exactly as
        // OpenVpnService.syncEngineState() does in production -- this is what corrupts
        // ConnectionStateManager's state/hint if the R7-1 fix is absent or reverted.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, "AIDL")
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_CONNECTED, null)
        assertEquals(
            "precondition: ServerAutoSwitcher itself must still ignore the stale level (cycle 6)",
            0,
            calls.size
        )

        // The real NOTCONNECTED confirmation resolves the pending retry and fires starter().
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, calls.size)
        assertEquals(
            "the retry's ACTION_START must be dispatched with the reconnect invariant intact " +
                "(state != DISCONNECTED), otherwise it defeats OpenVpnService's " +
                "stopAfterOneShotSyncConfirmedRunnable/stopControllerIfIdle/reconnectPending FGS " +
                "guards (review-7 R7-1)",
            ConnectionState.CONNECTING,
            stateAtDispatch
        )
        assertEquals(
            "reconnectingHint must also be re-asserted at retry-commit time",
            true,
            hintAtDispatch
        )
    }

    @Test
    fun noAlternativeServersDoesNotSwitch() {
        val single = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", single)
        SelectedCountryStore.resetIndex(appContext)
        ShadowLog.clear()

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

        assertEquals(0, calls.size)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf1", current?.config)

        val hadFullCycleLog = ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains("completed full server cycle") }
        assertEquals(true, hadFullCycleLog)
        assertEquals(1, stopCalls)
    }

    @Test
    fun fullCycleRestoresStartIndex() {
        UserSettingsStore.saveStatusStallTimeoutSeconds(appContext, 1)
        ServerAutoSwitcher.setNoReplyThresholdForTest(1)
        ServerAutoSwitcher.setRepliedThresholdForTest(1)
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf2"),
            Server(3, "n3", "c3", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf3")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.setCurrentIndex(appContext, 1)
        calls.clear()
        stopCalls = 0
        ShadowLog.clear()

        // 1) Switch to conf3
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        // 2) Switch to conf1
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        // 3) Full cycle completes -> stop and restore start index
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))

        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf2", current?.config)
        assertEquals(true, calls.isNotEmpty())
        assertEquals(1, stopCalls)
    }

    // Regression test for QG4-2(a) (fix-cycle 8, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-gate-4.md): the retry-commit dispatch used to be posted as an anonymous, untracked
    // handler.postDelayed lambda that cancel() could not reference, let alone remove. A user
    // Disconnect landing inside the START_AFTER_STOP_DELAY_MS (350ms) window used to leave that
    // lambda armed, so the app auto-reconnected ~350ms after an explicit Disconnect despite
    // cancelForUserStop() having already run (ACTION_START unconditionally clears
    // userInitiatedStop, so nothing downstream re-blocked it either). See
    // OpenVpnServiceNotificationTest's finishStopFlowConfirmed_abortsStopSelf_... tests for the
    // crash-adjacent half of this same finding (QG4-2(b)).
    // Falsifiability: this must fail if the shared retryStartRunnable tracking field is reverted
    // back to an untracked postDelayed lambda.
    @Test
    fun cancelForUserStop_withinRetryDelayWindow_preventsOrphanedReconnect() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // Real NOTCONNECTED confirmation arms the retry-commit dispatch for
        // START_AFTER_STOP_DELAY_MS (350ms) from here.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)

        // A user Disconnect lands inside the 350ms window.
        ServerAutoSwitcher.cancelForUserStop()

        // Advance well past the retry-commit delay.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "cancelForUserStop() landing inside the retry-commit delay window must prevent the " +
                "previously-untracked posted lambda from firing an orphaned reconnect after an " +
                "explicit user Disconnect",
            0,
            calls.size
        )
    }

    // Sibling coverage: the STOP_RETRY_TIMEOUT_MS fallback path (no NOTCONNECTED ever observed)
    // posts its own retry-commit dispatch through the same shared retryStartRunnable field --
    // verify cancel() reaches that one too.
    @Test
    fun cancelForUserStop_withinRetryDelayWindow_afterTimeoutPath_preventsOrphanedReconnect() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // No NOTCONNECTED ever arrives; the STOP_RETRY_TIMEOUT_MS (5s) fallback commits the retry
        // and arms the same 350ms retry-commit dispatch.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        // A user Disconnect lands inside the resulting 350ms window.
        ServerAutoSwitcher.cancelForUserStop()

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "cancelForUserStop() must also prevent the timeout-path retry-commit dispatch from firing",
            0,
            calls.size
        )
    }

    // R12-1 (fix-cycle 12, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-12.md):
    // review-12 proved rollBackFailedRetryDispatch() itself was never executed by any test --
    // deleting both `if (!starter(...)) rollBackFailedRetryDispatch()` call sites (leaving a bare
    // `starter(...)`) still left the scoped vpn suite 244/244 green, because every starter test
    // double in this suite returns true. These two tests drive a starter failure through each of
    // the two independently-edited retry-commit sites (NOTCONNECTED-observed and
    // stop-retry-timeout) and assert the rollback actually lands: ConnectionStateManager must
    // return to DISCONNECTED with reconnectingHint=false rather than being stranded on the
    // CONNECTING re-assertion R7-1 makes just ahead of the dispatch. Falsifiability: each must
    // fail (state stays CONNECTING) if its site's `if (!starter(...)) rollBackFailedRetryDispatch()`
    // is reverted to a bare `starter(...)`.

    @Test
    fun retryCommitDispatchFailure_notConnectedPath_rollsBackToDisconnected() {
        ServerAutoSwitcher.starter = { _, _, _, _ -> false }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // Real NOTCONNECTED confirmation arms the retry-commit dispatch (350ms from here) and
        // re-asserts CONNECTING/reconnectingHint=true (R7-1) just ahead of it.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        assertEquals(
            "precondition: the NOTCONNECTED-observed retry-commit site must re-assert CONNECTING " +
                "before dispatching",
            ConnectionState.CONNECTING,
            ConnectionStateManager.state.value
        )
        assertTrue(
            "precondition: reconnectingHint must be re-asserted before the retry-commit dispatch",
            ConnectionStateManager.reconnectingHint.value
        )
        assertTrue(
            "precondition: the timed switch that led here must have set cycleStartIndex (via " +
                "beginChainedSwitch), or the F11 assertion below proves nothing",
            ServerAutoSwitcher.cycleStartIndexForTest() != null
        )

        // Advance past START_AFTER_STOP_DELAY_MS (350ms): the retry-commit dispatch fires, starter
        // returns false, and rollBackFailedRetryDispatch() must run.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "a failed retry-commit dispatch on the NOTCONNECTED-observed path must roll " +
                "ConnectionStateManager back to DISCONNECTED, not strand it on CONNECTING with no " +
                "ACTION_START ever delivered to move it off",
            ConnectionState.DISCONNECTED,
            ConnectionStateManager.state.value
        )
        assertFalse(
            "reconnectingHint must be cleared by the rollback",
            ConnectionStateManager.reconnectingHint.value
        )
        // F11 (docs/qa-evidence/release-review-2.md): a failed retry-commit dispatch must also
        // reset the switch cycle so a stale cycleStartIndex cannot make the next auto-switch
        // cycle's wrap detection give up early.
        assertEquals(
            "rollBackFailedRetryDispatch() must reset cycleStartIndex so the next auto-switch " +
                "cycle does not inherit a stale wrap-detection start index from this aborted attempt",
            null,
            ServerAutoSwitcher.cycleStartIndexForTest()
        )
    }

    @Test
    fun retryCommitDispatchFailure_timeoutPath_rollsBackToDisconnected() {
        ServerAutoSwitcher.starter = { _, _, _, _ -> false }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // No NOTCONNECTED ever arrives; the STOP_RETRY_TIMEOUT_MS (5s) fallback commits the retry,
        // re-asserting CONNECTING/reconnectingHint=true (R7-1) just ahead of its own dispatch.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(
            "precondition: the stop-retry-timeout site must re-assert CONNECTING before dispatching",
            ConnectionState.CONNECTING,
            ConnectionStateManager.state.value
        )
        assertTrue(
            "precondition: reconnectingHint must be re-asserted before the retry-commit dispatch",
            ConnectionStateManager.reconnectingHint.value
        )
        assertTrue(
            "precondition: the timed switch that led here must have set cycleStartIndex (via " +
                "beginChainedSwitch), or the F11 assertion below proves nothing",
            ServerAutoSwitcher.cycleStartIndexForTest() != null
        )

        // Advance past START_AFTER_STOP_DELAY_MS (350ms): the retry-commit dispatch fires, starter
        // returns false, and rollBackFailedRetryDispatch() must run.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "a failed retry-commit dispatch on the stop-retry-timeout path must roll " +
                "ConnectionStateManager back to DISCONNECTED, not strand it on CONNECTING with no " +
                "ACTION_START ever delivered to move it off",
            ConnectionState.DISCONNECTED,
            ConnectionStateManager.state.value
        )
        assertFalse(
            "reconnectingHint must be cleared by the rollback",
            ConnectionStateManager.reconnectingHint.value
        )
        // F11 (docs/qa-evidence/release-review-2.md): a failed retry-commit dispatch must also
        // reset the switch cycle so a stale cycleStartIndex cannot make the next auto-switch
        // cycle's wrap detection give up early.
        assertEquals(
            "rollBackFailedRetryDispatch() must reset cycleStartIndex so the next auto-switch " +
                "cycle does not inherit a stale wrap-detection start index from this aborted attempt",
            null,
            ServerAutoSwitcher.cycleStartIndexForTest()
        )
    }

    // Regression tests for R9-1 (fix-cycle 9, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-review-9.md): QG4-2's fix above made retryStartRunnable trackable so cancel() could
    // remove it for a genuine cancelForUserStop() -- but onEngineLevel()'s own generic
    // `else -> cancel(...)` branch is ALSO reached by any stray/re-delivered engine level landing
    // inside the same START_AFTER_STOP_DELAY_MS (350ms) retry-commit window, since
    // waitingStopForRetry is already cleared by the time the window opens and no longer shields it
    // (see the cycle-6 stale-level guard just above in onEngineLevel(), and retryCommitInFlight's
    // declaration comment in ServerAutoSwitcher.kt). Before the R9-1 fix, either stray level below
    // silently discarded the pending retry -- no VPN process, no pending reconnect, UI stuck on
    // "Connecting" forever, the exact symptom fixed once already in e8fa60e (bug 86cb21563).
    // Falsifiability: these must fail (retry does not fire, calls.size stays 0) if
    // retryCommitInFlight is removed or its onEngineLevel() check is removed, while the two
    // cancelForUserStop_withinRetryDelayWindow_* tests above must keep passing (a genuine user
    // Disconnect must still cancel the retry).

    @Test
    fun strayDuplicateNotConnectedInRetryCommitWindow_doesNotCancelPendingRetry() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // Real NOTCONNECTED confirmation arms the retry-commit dispatch for
        // START_AFTER_STOP_DELAY_MS (350ms) from here; waitingStopForRetry is cleared immediately.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)

        // A stray duplicate NOTCONNECTED lands inside the 350ms window -- e.g. the engine emitting
        // LEVEL_NOTCONNECTED twice back-to-back for EXITING then NOPROCESS during the same
        // teardown. This is not a user Disconnect; the pending retry must survive it.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "a stray duplicate NOTCONNECTED landing inside the retry-commit window must not " +
                "cancel the pending retry -- doing so leaves the app stuck on Connecting with no " +
                "VPN process and no pending reconnect",
            1,
            calls.size
        )
        assertEquals(true, calls.first().reconnect)
    }

    @Test
    fun strayConnectedInRetryCommitWindow_doesNotCancelPendingRetry() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)

        // A stray/stale LEVEL_CONNECTED lands inside the 350ms window -- e.g. a re-delivered cached
        // terminal snapshot from the poll loop. This is not a user Disconnect; the pending retry
        // must survive it.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, source)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "a stray LEVEL_CONNECTED landing inside the retry-commit window must not cancel the " +
                "pending retry -- doing so leaves the app stuck on Connecting with no VPN process " +
                "and no pending reconnect",
            1,
            calls.size
        )
        assertEquals(true, calls.first().reconnect)
    }

    // Sibling coverage: the same stray-level protection must also apply to the retry armed by the
    // STOP_RETRY_TIMEOUT_MS fallback path (no NOTCONNECTED ever observed), not just the
    // NOTCONNECTED-observed path above.
    @Test
    fun strayConnectedInRetryCommitWindow_afterTimeoutPath_doesNotCancelPendingRetry() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // No NOTCONNECTED ever arrives; the STOP_RETRY_TIMEOUT_MS (5s) fallback commits the retry
        // and arms the same 350ms retry-commit dispatch.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, source)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "a stray LEVEL_CONNECTED landing inside the timeout-path retry-commit window must not " +
                "cancel the pending retry",
            1,
            calls.size
        )
        assertEquals(true, calls.first().reconnect)
    }

    @Test
    fun stopRetryTimeoutStartsNextServerWithoutNotConnected() {
        ShadowLog.clear()

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // No NOTCONNECTED is emitted; timeout should still trigger a start.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, calls.size)
        assertEquals(true, calls.first().reconnect)
    }

    // Fix-cycle 7 review R7-1/R7-4: the same reconnect-invariant re-assertion is required at the
    // SECOND retry-commit site -- the STOP_RETRY_TIMEOUT_MS fallback runnable, reached when the
    // real NOTCONNECTED confirmation never arrives at all within the 5s window. Mirrors
    // staleLevelDuringStopForRetry_reconnectInvariantHoldsAtRetryDispatch above but drives the
    // stale-level corruption via a stale LEVEL_CONNECTED with no NOTCONNECTED follow-up, letting
    // the timeout path itself fire the retry.
    @Test
    fun stopRetryTimeout_reconnectInvariantHoldsAtRetryDispatch() {
        var stateAtDispatch: ConnectionState? = null
        var hintAtDispatch: Boolean? = null
        ServerAutoSwitcher.starter = { ctx, config, title, reconnect ->
            stateAtDispatch = ConnectionStateManager.state.value
            hintAtDispatch = ConnectionStateManager.reconnectingHint.value
            calls.add(Call(ctx, config, title, reconnect))
        }
        ShadowLog.clear()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, null)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // Stale LEVEL_CONNECTED corrupts ConnectionStateManager exactly as in the sibling test --
        // but no NOTCONNECTED ever follows, so only the STOP_RETRY_TIMEOUT_MS fallback resolves it.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, "AIDL")
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_CONNECTED, null)

        // No NOTCONNECTED is emitted; the 5s STOP_RETRY_TIMEOUT_MS fallback must still trigger a
        // start, with the invariant re-asserted.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, calls.size)
        assertEquals(
            "the timeout-path retry's ACTION_START must also be dispatched with the reconnect " +
                "invariant intact (review-7 R7-1)",
            ConnectionState.CONNECTING,
            stateAtDispatch
        )
        assertEquals(true, hintAtDispatch)
    }

    @Test
    fun idleToleranceWaitsBeforeStartingTimer() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.UNKNOWN_LEVEL, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, ServerAutoSwitcher.remainingSeconds.value)
    }

    // R7-3 (fix-cycle 7 review): beginChainedSwitch() must cancel any idle-tolerance runnable
    // armed just before it runs. requestSwitchNow()'s equivalent transition into
    // waitingStopForRetry=true goes through cancel(resetCycle=false), which already cancels idle
    // tolerance as a side effect; beginChainedSwitch() did not, so an idleToleranceRunnable armed
    // within UNKNOWN_PAUSED_GRACE_MS before a beginChainedSwitch() call (its production callers:
    // OpenVpnService's VPN_STATUS auto-switch path and the watchdog-recovery starter) could still
    // fire mid-window via start(appContext, level) called directly -- not through onEngineLevel()
    // -- bypassing the waitingStopForRetry guard and starting a competing timer (B24's mechanism
    // through a different door).
    @Test
    fun beginChainedSwitch_cancelsPendingIdleTolerance() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.UNKNOWN_LEVEL, source)
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)

        val begun = ServerAutoSwitcher.beginChainedSwitch(appContext, "client\n", "RU")
        assertTrue(begun)

        // Advance well past UNKNOWN_PAUSED_GRACE_MS (3s) but under STOP_RETRY_TIMEOUT_MS (5s). If
        // idle tolerance were not cancelled, it would fire start(appContext, UNKNOWN_LEVEL) here,
        // directly bypassing onEngineLevel()'s waitingStopForRetry guard, and remainingSeconds
        // would become non-null.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))

        assertEquals(
            "an idle-tolerance runnable armed before beginChainedSwitch() must not survive to " +
                "start a competing timer mid-window",
            null,
            ServerAutoSwitcher.remainingSeconds.value
        )
    }

    // US-12 AC-2: DEFAULT_V2 hydration gap — hydration callback is triggered and probe code is reachable.
    // Note: when the server store is empty (total==0), getCurrentServerIdIfMatchingLastStarted also
    // returns 0 (no current server to match), so the probe guard (failingServerId != 0) correctly
    // prevents spurious enqueues. This test verifies that: (a) the hydration path is entered,
    // (b) the probe guard works (no enqueue for id=0), and (c) no crash occurs.
    @Test
    fun defaultV2HydrationGap_hydrationPathEnteredAndNoProbeForEmptyStore() {
        // Set ServerSource to DEFAULT_V2 so the hydration path is taken
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        try {
            // Empty server list triggers the DEFAULT_V2 hydration path (total==0)
            SelectedCountryStore.saveSelection(appContext, "RU", emptyList())

            val fakeQueue = object : ProbeRequestQueue {
                val enqueuedIds = mutableListOf<Int>()
                override fun enqueue(serverId: Int) { enqueuedIds.add(serverId) }
            }
            ServerAutoSwitcher.setProbeRequestQueueForTest(fakeQueue)

            // Wire a v2HydrationCallback that records the call but does NOT invoke onDone
            var hydrationCallbackInvoked = false
            ServerAutoSwitcher.v2HydrationCallback = { _, _ ->
                hydrationCallbackInvoked = true
            }

            // Start the timer and let it expire — will hit the DEFAULT_V2 hydration path
            ConnectionStateManager.setReconnectingHint(false)
            ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
            ConnectionStateManager.updateState(ConnectionState.CONNECTING)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

            assertTrue("Hydration callback must be invoked when store is empty and source=DEFAULT_V2", hydrationCallbackInvoked)
            // When total==0, currentServer() is null → failingServerId==0 → probe guard prevents enqueue
            assertTrue("No probe enqueued when failingServerId=0 (empty store)", fakeQueue.enqueuedIds.isEmpty())
        } finally {
            UserSettingsStore.saveServerSource(appContext, ServerSource.VPNGATE)
        }
    }

}

