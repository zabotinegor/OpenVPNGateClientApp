package com.yahorzabotsin.openvpnclientgate.vpn

import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VpnConnectionStateProviderTest {

    private val provider = DefaultVpnConnectionStateProvider()

    @Before
    fun reset() {
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    @Test
    fun isConnected_returnsTrueWhenStateIsConnected() {
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)

        assertTrue(provider.isConnected())
    }

    @Test
    fun isConnected_returnsTrueWhenStateIsPaused() {
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_VPNPAUSED, null)

        assertTrue(provider.isConnected())
    }

    @Test
    fun isConnected_returnsTrueWhenStateIsPausing() {
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.beginPauseTransition()

        assertTrue(provider.isConnected())
    }

    @Test
    fun isConnected_returnsFalseWhenStateIsDisconnected() {
        assertFalse(provider.isConnected())
    }
}
