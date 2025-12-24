package com.yahorzabotsin.openvpnclientgate.vpn

import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ConnectionStateManagerTest {

    @Before
    fun reset() {
        // ensure a clean baseline for each test
        ConnectionStateManager.setReconnectingHint(false)
        // Force baseline state
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    @Test
    fun masksNoProcessWhileConnecting() {
        // Given UI already in CONNECTING (initial engine process spin-up)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        // When engine reports transient NOTCONNECTED with NOPROCESS
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, "NOPROCESS")

        // Then state remains CONNECTING (no flicker to DISCONNECTED)
        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)
    }

    @Test
    fun reconnectHintMasksDisconnectedToConnecting() {
        // Given reconnecting flow is in progress
        ConnectionStateManager.setReconnectingHint(true)

        // When engine transiently reports DISCONNECTED
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)

        // Then effective state is CONNECTING (masked by hint)
        assertEquals(ConnectionState.CONNECTING, ConnectionStateManager.state.value)
    }

    @Test
    fun keepsDisconnectingDuringEngineExit() {
        // Given user initiated a stop (drive valid transitions)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)

        // When engine reports NOTCONNECTED with EXITING while tearing down
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, "EXITING")

        // Then UI remains in DISCONNECTING until final DISCONNECTED transition
        assertEquals(ConnectionState.DISCONNECTING, ConnectionStateManager.state.value)
    }

    @Test
    fun clearsReconnectHintOnConnected() {
        // Given hint true from previous reconnect path
        ConnectionStateManager.setReconnectingHint(true)
        // and we are in CONNECTING (engine starting up)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        // When connected
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_CONNECTED, null)

        // Then state CONNECTED and hint cleared
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
        // updating again with DISCONNECTED should not be masked anymore
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        assertEquals(ConnectionState.DISCONNECTED, ConnectionStateManager.state.value)
    }

    @Test
    fun treatsConnectedDetailAsConnected() {
        ConnectionStateManager.setReconnectingHint(true)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, "CONNECTED")

        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
        assertEquals(ConnectionStatus.LEVEL_CONNECTED, ConnectionStateManager.engineLevel.value)
    }

    @Test
    fun clearsReconnectHintOnConnectedDetail() {
        ConnectionStateManager.setReconnectingHint(true)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, "CONNECTED")

        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
        assertEquals(false, ConnectionStateManager.reconnectingHint.value)
    }

    @Test
    fun setsAuthErrorOnAuthFailed() {
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_AUTH_FAILED, null)
        assertEquals(ConnectionState.DISCONNECTED, ConnectionStateManager.state.value)
        assertEquals(ConnectionStateManager.VpnError.AUTH, ConnectionStateManager.error.value)
    }

    @Test
    fun syncConnectionStartTimeOverridesWhenDifferent() {
        val initial = 1_000L
        val updated = 20_000L
        ConnectionStateManager.syncConnectionStartTime(initial)

        ConnectionStateManager.syncConnectionStartTime(updated)

        assertEquals(updated, ConnectionStateManager.connectionStartTimeMs.value)
    }

    @Test
    fun syncConnectionStartTimeIgnoresSmallDelta() {
        val base = 1_000L
        ConnectionStateManager.syncConnectionStartTime(base)

        ConnectionStateManager.syncConnectionStartTime(base + 2_000L)

        assertEquals(base, ConnectionStateManager.connectionStartTimeMs.value)
    }
}

