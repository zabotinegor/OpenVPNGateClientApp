package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class VpnManagerPauseRaceConditionsTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    @Test
    fun pauseFromDisconnected_isRejected() {
        val result = VpnManager.pauseVpn(app)

        assertFalse("Pause from DISCONNECTED should be rejected", result)
        assertEquals(ConnectionState.DISCONNECTED, ConnectionStateManager.state.value)
    }

    @Test
    fun resumeFromDisconnected_transitionsToConnecting() {
        VpnManager.resumeVpn(app)

        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)
        val shadowApp = Shadows.shadowOf(app)
        val started = shadowApp.nextStartedService
        assertEquals(VpnManager.ACTION_RESUME, started.getStringExtra(VpnManager.actionKey(app)))
    }

    @Test
    fun pauseAfterResume_statesTransitionCorrectly() {
        // Setup: Connected -> Pause -> Resume -> Pause again
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        // First pause
        val pauseResult1 = VpnManager.pauseVpn(app)
        assertTrue(pauseResult1)
        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)

        // Resume
        VpnManager.resumeVpn(app)
        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)

        // Simulate engine response: reconnected
        ConnectionStateManager.updateFromEngine(de.blinkt.openvpn.core.ConnectionStatus.LEVEL_CONNECTED, null)
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)

        // Second pause
        val pauseResult2 = VpnManager.pauseVpn(app)
        assertTrue(pauseResult2)
        assertEquals(ConnectionState.PAUSING, ConnectionStateManager.state.value)
    }
}
