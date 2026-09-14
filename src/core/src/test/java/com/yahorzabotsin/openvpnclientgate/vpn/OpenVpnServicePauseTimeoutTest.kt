package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import de.blinkt.openvpn.core.ConnectionStatus
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenVpnServicePauseTimeoutTest {
    private val appContext = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    @After
    fun tearDown() {
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    @Test
    fun pauseActionTimeout_reconcilesToConnectedWhenLastKnownLevelIsConnected() {
        // Setup: Service with pause action in flight
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        // Action: Send PAUSE command
        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 1)

        // Service only forwards command; app state stays CONNECTED until VpnManager/engine update.
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)

        // Trigger timeout runnable
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Verify: no forced PAUSED, state reconciles to CONNECTED from last known level.
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
    }

    @Test
    fun pauseActionTimeout_clearsInFlightFlagOnTimeout() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 1)

        // Verify flag is set initially
        val pauseActionInFlight = ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight")
        assertEquals(true, pauseActionInFlight)

        // Run timeout
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Verify flag is cleared after timeout
        val pauseActionInFlightAfter = ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight")
        assertEquals(false, pauseActionInFlightAfter)
    }

    @Test
    fun pauseActionTimeout_doesNotTriggerIfEngineRespondsEarly() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 1)
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)

        // Engine responds quickly with PAUSED callback
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_VPNPAUSED, null)
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)

        // Run timeout handlers. Even if in-flight flag is still true in this synthetic test setup,
        // the timeout must not move state away from PAUSED.
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Verify state remains PAUSED (not overwritten)
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }

    @Test
    fun resumeAction_clearsPauseTimeoutAndSetResumeInFlight() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        // Pause
        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 1)

        // Resume before timeout
        val resumeIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_RESUME)
        }
        service.onStartCommand(resumeIntent, 0, 2)

        // Pause flag must be cleared
        val pauseInFlight = ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight")
        assertEquals(false, pauseInFlight)

        // Resume flag should now be active (timeout will guard against stall)
        val resumeInFlight = ReflectionHelpers.getField<Boolean>(service, "resumeActionInFlight")
        assertEquals(true, resumeInFlight)

        // State is still CONNECTED: VpnManager.resumeVpn calls beginResumeTransition,
        // but this test dispatches directly to the service, bypassing VpnManager.
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
    }

    @Test
    fun resumeActionTimeout_rollsBackToPausedWhenEngineStillPaused() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_VPNPAUSED, null)

        // Simulate resume tap: VpnManager sets resumeTransitionInFlight and moves state to CONNECTING
        ConnectionStateManager.beginResumeTransition()
        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)

        // Service receives ACTION_RESUME and schedules resume timeout
        val resumeIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_RESUME)
        }
        service.onStartCommand(resumeIntent, 0, 1)

        // Engine level is still VPNPAUSED — ignored because resumeTransitionInFlight is true
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_VPNPAUSED, null)
        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)

        // Force the timeout path to use the ConnectionStateManager fallback instead of
        // any incidental observed service-source state from the test environment.
        ReflectionHelpers.setField(service, "lastAidlStateUpdateMs", 0L)
        ReflectionHelpers.setField(service, "lastVpnStatusStateUpdateMs", 0L)

        // Resume timeout fires: engine still paused → roll back to PAUSED
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }

    @Test
    fun resumeActionTimeout_ignoresVpnStatusWhenAidlIsFresh() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_VPNPAUSED, null)

        ConnectionStateManager.beginResumeTransition()
        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)

        val now = System.currentTimeMillis()
        // Round 17 fix (Codex P2, comment 3736234632): isAidlFresh() now measures freshness
        // purely via elapsedRealtimeMs()/lastLiveStatusElapsedRealtimeMs, not wall clock, so this
        // fresh-live-push precondition must set the monotonic pairing too, not just
        // lastLiveStatusMs, or isAidlFresh() incorrectly reads as stale.
        val elapsedRealtimeValueMs = 500_000L
        ReflectionHelpers.setField(service, "elapsedRealtimeMs", ({ elapsedRealtimeValueMs } as () -> Long))
        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now)
        ReflectionHelpers.setField(service, "lastLiveStatusElapsedRealtimeMs", elapsedRealtimeValueMs)

        val resumeIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_RESUME)
        }
        service.onStartCommand(resumeIntent, 0, 1)

        service.updateState(
            "CONNECTED",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTED,
            null
        )

        // VPN_STATUS CONNECTED was observed, but the manager still carries stale PAUSED
        // because fresh AIDL suppressed the sync path.
        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // While AIDL is still fresh, timeout reconciliation should not trust newer VPN_STATUS.
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }

    @Test
    fun aidlPausedCallback_clearsPauseTimeoutAndInFlightFlag() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 1)

        val callbacks = ReflectionHelpers.getField<Any>(service, "statusCallbacks")
        ReflectionHelpers.callInstanceMethod<Any>(
            callbacks,
            "updateStateString",
            ReflectionHelpers.ClassParameter.from(String::class.java, "VPNPAUSED"),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ReflectionHelpers.ClassParameter.from(ConnectionStatus::class.java, ConnectionStatus.LEVEL_VPNPAUSED),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, null)
        )

        val pauseActionInFlight = ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight")
        assertEquals(false, pauseActionInFlight)
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)

        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // The timeout runnable should already be canceled by the AIDL PAUSED callback.
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }

    // A slow engine teardown (explicit-exit-notify retries, TLS session close) before the
    // management HOLD checkpoint can legitimately take several seconds
    // -- the fixed 3s watchdog gave up and forced CONNECTED back before the engine ever reported
    // PAUSED. PAUSE_CONFIRMATION_TIMEOUT_MS is now 10s, with a resend of PAUSE_VPN at the 5s
    // halfway point as a safety net for a lost intent. This verifies the resend fires once at the
    // halfway point without abandoning the pause, and the final timeout still fires if nothing
    // ever confirms.
    @Test
    fun pauseAction_resendsOnceAtRetryWindow_thenGivesUpAtFinalTimeoutIfNeverConfirmed() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 1)
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseRetrySent"))

        // Just past the 5s retry window, before the 10s final timeout.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_100L))

        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseRetrySent"))
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)

        // Past the full 10s timeout with nothing ever confirming: still gives up eventually.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5_000L))

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
    }

    @Test
    fun pauseAction_confirmedBeforeRetryWindow_cancelsScheduledResend() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 1)

        val callbacks = ReflectionHelpers.getField<Any>(service, "statusCallbacks")
        ReflectionHelpers.callInstanceMethod<Any>(
            callbacks,
            "updateStateString",
            ReflectionHelpers.ClassParameter.from(String::class.java, "VPNPAUSED"),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ReflectionHelpers.ClassParameter.from(ConnectionStatus::class.java, ConnectionStatus.LEVEL_VPNPAUSED),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, null)
        )
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)

        // Advance well past the retry window: the cancelled resend must not fire.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(6_000L))

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseRetrySent"))
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }

    // Codex PR review: if the connection reports a terminal level (LEVEL_NOTCONNECTED here) after
    // Pause was tapped -- the session ended instead of confirming PAUSED -- pauseActionInFlight was
    // left true, so PAUSE_RETRY_AT_MS would fire 5s later and resend PAUSE_VPN into whatever
    // unrelated session (e.g. a fresh reconnect) had started by then. The terminal-level branch now
    // clears the pause watch immediately.
    @Test
    fun pauseAction_aidlCallback_terminalLevelAbandonsPause_cancelsRetryAndTimeout() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 1)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        val callbacks = ReflectionHelpers.getField<Any>(service, "statusCallbacks")
        ReflectionHelpers.callInstanceMethod<Any>(
            callbacks,
            "updateStateString",
            ReflectionHelpers.ClassParameter.from(String::class.java, "NOPROCESS"),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ReflectionHelpers.ClassParameter.from(ConnectionStatus::class.java, ConnectionStatus.LEVEL_NOTCONNECTED),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, null)
        )

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        // Advance past both the retry window and the final timeout: neither should do anything --
        // pauseActionInFlight is already false, so both runnables must no-op.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(10_100L))

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseRetrySent"))
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
    }
}
