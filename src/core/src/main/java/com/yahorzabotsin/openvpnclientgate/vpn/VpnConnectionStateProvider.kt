package com.yahorzabotsin.openvpnclientgate.vpn

import kotlinx.coroutines.flow.StateFlow

interface VpnConnectionStateProvider {
    val state: StateFlow<ConnectionState>
    fun isConnected(): Boolean
}

class DefaultVpnConnectionStateProvider : VpnConnectionStateProvider {
    override val state: StateFlow<ConnectionState> = ConnectionStateManager.state

    override fun isConnected(): Boolean =
        ConnectionStateManager.state.value == ConnectionState.CONNECTED ||
    ConnectionStateManager.state.value == ConnectionState.PAUSING ||
        ConnectionStateManager.state.value == ConnectionState.PAUSED
}
