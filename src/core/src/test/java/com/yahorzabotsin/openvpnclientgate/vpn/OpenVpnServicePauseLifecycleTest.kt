package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenVpnServicePauseLifecycleTest {
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

    // sdk = [27]: this test drives a real ACTION_START through enterControllerForeground() with
    // controllerForegroundActive already true (ClickUp 86cb35fbt regression scenario), which now
    // always attempts NotificationCompat.Builder(...).build(). On the project's default Robolectric
    // SDK, that call throws NoSuchMethodError (an unrelated AndroidX-core/Robolectric shadow-jar
    // mismatch) -- pinning sdk=27 avoids it. The other two tests in this class do not exercise that
    // code path and pass at the default SDK, so the pin is scoped to this method only. The pattern
    // of pinning sdk=27 to route around this mismatch is precedented elsewhere in this suite (e.g.
    // OpenVpnServiceSessionLoggingTest), though that file's pin carries no comment explaining why.
    @Config(sdk = [27])
    @Test
    fun pauseLifecycle_clearsInFlightFlagOnActionStart() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "controllerForegroundActive", true)
        ReflectionHelpers.setField(service, "pauseActionInFlight", true)
        
        val startIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(startIntent, 0, 1)
        
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
    }

    @Test
    fun pauseLifecycle_clearsInFlightFlagOnActionStop() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "pauseActionInFlight", true)
        ReflectionHelpers.setField(service, "resumeActionInFlight", true)
        
        val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
        }
        service.onStartCommand(stopIntent, 0, 2)
        
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "resumeActionInFlight"))
    }

    @Test
    fun pauseResumeSequence_staysValid() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "suppressEngineState", false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        
        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 3)

        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        service.updateState("PAUSED", null, 0, ConnectionStatus.LEVEL_VPNPAUSED, null)

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
        
        val resumeIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_RESUME)
        }
        service.onStartCommand(resumeIntent, 0, 4)

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "resumeActionInFlight"))

        service.updateState("CONNECTED", null, 0, ConnectionStatus.LEVEL_CONNECTED, null)

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "resumeActionInFlight"))
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
    }

    // The VpnStatus fallback path's terminal-level branch abandons an in-flight pause on
    // LEVEL_NOTCONNECTED/LEVEL_NONETWORK/LEVEL_AUTH_FAILED, but omitted UNKNOWN_LEVEL, unlike the
    // AIDL path's STOP_TERMINAL_LEVELS set which already treats it as terminal. Left unguarded, a
    // session ending with an unrecognized/unknown level would leave pauseActionInFlight true and
    // PAUSE_RETRY_AT_MS would resend PAUSE_VPN 5s later into whatever unrelated session has started
    // by then.
    @Test
    fun pauseAction_vpnStatusUnknownLevel_abandonsInFlightPause() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "suppressEngineState", false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 5)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        service.updateState("UNKNOWN", null, 0, ConnectionStatus.UNKNOWN_LEVEL, null)

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
    }

    // A status queued by the engine just before it actually applied a pause request could still
    // arrive afterward as a transient connecting-family level. Forwarding that
    // stale status flashed the UI to CONNECTING for a frame between CONNECTED and PAUSED. This
    // reproduces the exact device sequence (CONNECTED -> pauseVpn -> stale
    // LEVEL_CONNECTING_NO_SERVER_REPLY_YET -> LEVEL_VPNPAUSED) and asserts the state goes straight
    // to PAUSED without ever observing CONNECTING in between.
    @Test
    fun pauseAction_ignoresStaleConnectingStatus_neverFlickersToConnecting() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "suppressEngineState", false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 5)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        // Stale status from the engine, queued before the pause was actually applied.
        service.updateState(
            "CONNECTING",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            null
        )

        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        service.updateState("PAUSED", null, 0, ConnectionStatus.LEVEL_VPNPAUSED, null)

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }

    // While connected and bound, isAidlFresh() is true, so updateState() (the VpnStatus fallback
    // exercised by the test above) returns early
    // and never reaches the guard -- the device actually runs the AIDL path, entered synchronously
    // on the binder thread via statusCallbacks.updateStateString() -> syncEngineState(). This drives
    // that callback directly (the same ReflectionHelpers pattern as
    // OpenVpnServicePauseTimeoutTest.aidlPausedCallback_clearsPauseTimeoutAndInFlightFlag) and
    // reproduces the real pauseVpn sequence: CONNECTED -> beginPauseTransition (state -> PAUSING)
    // -> stale AIDL LEVEL_CONNECTING_NO_SERVER_REPLY_YET -> LEVEL_VPNPAUSED.
    @Test
    fun pauseAction_aidlCallback_ignoresStaleConnectingStatus_neverFlickersToConnecting() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "suppressEngineState", false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        // Mirrors VpnManager.pauseVpn: move state to PAUSING before the engine confirms, then
        // dispatch ACTION_PAUSE so the service arms the in-flight flag and timeout.
        ConnectionStateManager.beginPauseTransition()
        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 6)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        val callbacks = ReflectionHelpers.getField<Any>(service, "statusCallbacks")

        // Stale status delivered on the AIDL binder thread before the engine actually applied pause.
        ReflectionHelpers.callInstanceMethod<Any>(
            callbacks,
            "updateStateString",
            ReflectionHelpers.ClassParameter.from(String::class.java, "CONNECTING"),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ReflectionHelpers.ClassParameter.from(
                ConnectionStatus::class.java,
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET
            ),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, null)
        )

        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        ReflectionHelpers.callInstanceMethod<Any>(
            callbacks,
            "updateStateString",
            ReflectionHelpers.ClassParameter.from(String::class.java, "VPNPAUSED"),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ReflectionHelpers.ClassParameter.from(ConnectionStatus::class.java, ConnectionStatus.LEVEL_VPNPAUSED),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, null)
        )
        // updateStateString()'s LEVEL_VPNPAUSED handling is posted to statusHandler (serialized
        // against the main-thread pause retry/timeout runnables), so pump the looper before asserting.
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }

    // ConnectionStateManager.normalizeEngineLevel() maps any non-CONNECTED level whose detail is
    // exactly "CONNECTED" to LEVEL_CONNECTED -- a mapping outside PAUSE_TRANSIENT_CONNECTING_LEVELS.
    // The AIDL guard used to test the *normalized* level, so a stale connecting-family callback
    // carrying detail="CONNECTED" bypassed it and reached ConnectionStateManager.updateFromEngine()
    // and the auto-switcher dispatch. The app's visible *state* is shielded from the worst case by
    // ConnectionStateManager's own transition whitelist (PAUSING -> CONNECTED is outside
    // allowedFromPausing, so _state.value can't be corrupted this way even with the bug present) --
    // but engineLevel, which updateFromEngine() sets unconditionally before that whitelist check,
    // still got corrupted to LEVEL_CONNECTED, and the auto-switcher dispatch this guard exists to
    // also block still fired. Guard must test the raw level, not the normalized one.
    @Test
    fun pauseAction_aidlCallback_ignoresStaleConnectingStatusWithConnectedDetail() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "suppressEngineState", false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        // engineLevel is left at LEVEL_NOTCONNECTED by setUp() and untouched by the state
        // transitions above (those go through updateState() directly, not updateFromEngine()) --
        // it doubles as the sentinel the assertion below checks: distinct from LEVEL_CONNECTED, so
        // a bypassed guard (which would leave engineLevel at LEVEL_CONNECTED) is observable.

        ConnectionStateManager.beginPauseTransition()
        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 7)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        val callbacks = ReflectionHelpers.getField<Any>(service, "statusCallbacks")

        // Stale connecting-family status whose detail string happens to be "CONNECTED" --
        // normalizeEngineLevel() would turn this into LEVEL_CONNECTED if the guard read the
        // normalized value instead of this raw level.
        ReflectionHelpers.callInstanceMethod<Any>(
            callbacks,
            "updateStateString",
            ReflectionHelpers.ClassParameter.from(String::class.java, "CONNECTED"),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ReflectionHelpers.ClassParameter.from(
                ConnectionStatus::class.java,
                ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED
            ),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, null)
        )

        // A working guard returns before updateFromEngine() ever runs, so engineLevel stays at the
        // sentinel; a bypassed guard (checking normalizedLevel) would leave it at LEVEL_CONNECTED.
        assertEquals(ConnectionStatus.LEVEL_NOTCONNECTED, ConnectionStateManager.engineLevel.value)
        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        ReflectionHelpers.callInstanceMethod<Any>(
            callbacks,
            "updateStateString",
            ReflectionHelpers.ClassParameter.from(String::class.java, "VPNPAUSED"),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ReflectionHelpers.ClassParameter.from(ConnectionStatus::class.java, ConnectionStatus.LEVEL_VPNPAUSED),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, null)
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }

    // VpnManager.pauseVpn() moves ConnectionState to PAUSING synchronously via
    // beginPauseTransition(), then dispatches ACTION_PAUSE via startService() -- an asynchronous
    // call. Between those two steps, pauseActionInFlight is still false (only onStartCommand sets
    // it), so a stale connecting-family status arriving in that exact gap must still be caught by
    // the guard via ConnectionState.PAUSING, not just the service-local flag. This drives the
    // callback BEFORE onStartCommand runs, unlike the other AIDL guard tests in this class.
    @Test
    fun pauseAction_aidlCallback_ignoresStaleConnectingStatus_beforeServiceProcessesActionPause() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "suppressEngineState", false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        // Mirrors VpnManager.pauseVpn's synchronous half only -- the ACTION_PAUSE dispatch has not
        // reached onStartCommand yet, so pauseActionInFlight is still false here.
        ConnectionStateManager.beginPauseTransition()
        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        val callbacks = ReflectionHelpers.getField<Any>(service, "statusCallbacks")

        // Stale status delivered in the dispatch gap, before onStartCommand ever runs.
        ReflectionHelpers.callInstanceMethod<Any>(
            callbacks,
            "updateStateString",
            ReflectionHelpers.ClassParameter.from(String::class.java, "CONNECTING"),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ReflectionHelpers.ClassParameter.from(
                ConnectionStatus::class.java,
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET
            ),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, null)
        )

        // PAUSING -> CONNECTING is an allowed transition (unlike PAUSING -> CONNECTED), so an
        // unguarded callback here really would have moved the state and hidden the pause button.
        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)

        // The dispatch finally arrives; the rest of the flow proceeds normally.
        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 8)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))

        ReflectionHelpers.callInstanceMethod<Any>(
            callbacks,
            "updateStateString",
            ReflectionHelpers.ClassParameter.from(String::class.java, "VPNPAUSED"),
            ReflectionHelpers.ClassParameter.from(String::class.java, null),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0),
            ReflectionHelpers.ClassParameter.from(ConnectionStatus::class.java, ConnectionStatus.LEVEL_VPNPAUSED),
            ReflectionHelpers.ClassParameter.from(Intent::class.java, null)
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }
}
