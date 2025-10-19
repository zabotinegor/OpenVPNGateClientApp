package com.yahorzabotsin.openvpnclient.vpn

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

object ConnectionStateManager {

    private val tag = ConnectionStateManager::class.simpleName
    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state = _state.asStateFlow()

    internal fun updateState(newState: ConnectionState) {
        if (_state.value != newState) {
            Log.d(tag, "State changed from ${_state.value} to $newState")
            _state.value = newState
        }
    }
}