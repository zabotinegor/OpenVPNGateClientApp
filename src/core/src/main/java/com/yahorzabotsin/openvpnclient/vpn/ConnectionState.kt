package com.yahorzabotsin.openvpnclient.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

object ConnectionStateManager {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state = _state.asStateFlow()

    fun updateState(newState: ConnectionState) {
        _state.value = newState
    }
}