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
    fun resumeActionTimeout_usesNewerVpnStatusConnectedOverStaleManagerLevel() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_VPNPAUSED, null)

        ConnectionStateManager.beginResumeTransition()
        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)

        val now = System.currentTimeMillis()
        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now)

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

        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
    }
}
