package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import android.os.Handler
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    fun resumeAction_clearsPauseTimeoutHandler() {
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

        // Flag should be cleared
        val pauseActionInFlight = ReflectionHelpers.getField<Boolean>(service, "pauseActionInFlight")
        assertEquals(false, pauseActionInFlight)

        // Run timeout handlers - no state change expected
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Service ACTION_RESUME clears timeout tracking and does not force PAUSED on timeout.
        // App state transition to CONNECTING is initiated by VpnManager, not by service command forwarding.
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
    }
}
