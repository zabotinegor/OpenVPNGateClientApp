package com.yahorzabotsin.openvpnclient.vpn

import android.util.Log
import androidx.annotation.MainThread
import de.blinkt.openvpn.core.ConnectionStatus
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
    enum class VpnError { NONE, AUTH }
    private val _error = MutableStateFlow(VpnError.NONE)
    val error = _error.asStateFlow()

    @MainThread
    internal fun updateState(newState: ConnectionState) {
        val current = _state.value
        if (current == newState) return

        val allowed = when (current) {
            ConnectionState.DISCONNECTED -> setOf(ConnectionState.CONNECTING)
            ConnectionState.CONNECTING -> setOf(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED)
            ConnectionState.CONNECTED -> setOf(ConnectionState.DISCONNECTING, ConnectionState.DISCONNECTED)
            ConnectionState.DISCONNECTING -> setOf(ConnectionState.DISCONNECTED)
        }

        if (newState in allowed) {
            Log.d(tag, "State changed from $current to $newState")
            _state.value = newState
        } else {
            Log.d(tag, "Ignored transition $current -> $newState")
        }
    }

    @MainThread
    fun updateFromEngine(level: ConnectionStatus) {
        val mapped = when (level) {
            ConnectionStatus.LEVEL_START,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> ConnectionState.CONNECTING
            ConnectionStatus.LEVEL_CONNECTED -> ConnectionState.CONNECTED
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_VPNPAUSED,
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.UNKNOWN_LEVEL -> ConnectionState.DISCONNECTED
        }
        Log.d(tag, "Engine level=$level -> $mapped")
        if (level == ConnectionStatus.LEVEL_AUTH_FAILED) {
            _error.value = VpnError.AUTH
        } else if (mapped != ConnectionState.DISCONNECTED) {
            _error.value = VpnError.NONE
        }
        updateState(mapped)
    }
}
