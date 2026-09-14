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

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight"))
        assertEquals(ConnectionState.PAUSED, ConnectionStateManager.state.value)
    }
}
