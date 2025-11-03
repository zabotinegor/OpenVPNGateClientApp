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
    private val _engineLevel = MutableStateFlow<ConnectionStatus?>(null)
    val engineLevel = _engineLevel.asStateFlow()
    private val _engineDetail = MutableStateFlow<String?>(null)
    val engineDetail = _engineDetail.asStateFlow()
    private val _reconnectingHint = MutableStateFlow(false)
    val reconnectingHint = _reconnectingHint.asStateFlow()

    fun setReconnectingHint(value: Boolean) {
        Log.d(tag, "setReconnectingHint=$value (was=${_reconnectingHint.value})")
        _reconnectingHint.value = value
    }

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
    fun updateFromEngine(level: ConnectionStatus, detail: String? = null) {
        _engineLevel.value = level
        _engineDetail.value = detail
        Log.d(tag, "updateFromEngine level=$level detail=${detail ?: "<none>"}")

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

        Log.d(tag, "Engine level=$level -> mapped=$mapped hint=${_reconnectingHint.value}")

        if (level == ConnectionStatus.LEVEL_AUTH_FAILED) {
            _error.value = VpnError.AUTH
        } else if (mapped != ConnectionState.DISCONNECTED) {
            _error.value = VpnError.NONE
        }

        if (mapped == ConnectionState.CONNECTED) _reconnectingHint.value = false

        // Smooth transient DISCONNECTED during engine (re)start/teardown
        val current = _state.value
        val d = detail ?: ""
        var effective = mapped

        if (mapped == ConnectionState.DISCONNECTED) {
            if (_reconnectingHint.value) {
                effective = ConnectionState.CONNECTING
            } else if (current == ConnectionState.CONNECTING && (d == "NOPROCESS" || d == "EXITING")) {
                effective = ConnectionState.CONNECTING
            } else if (current == ConnectionState.DISCONNECTING && (d == "NOPROCESS" || d == "EXITING")) {
                effective = ConnectionState.DISCONNECTING
            }
        }

        if (effective != mapped) {
            Log.d(tag, "Masking $mapped to $effective (current=$current, detail='${d}')")
        }

        updateState(effective)
    }
}
