package com.yahorzabotsin.openvpnclient.vpn

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

    private val _connectionStartTimeMs = MutableStateFlow<Long?>(null)
    val connectionStartTimeMs = _connectionStartTimeMs.asStateFlow()

    private val _speedMbps = MutableStateFlow(0.0)
    val speedMbps = _speedMbps.asStateFlow()
    private val _downloadedBytes = MutableStateFlow(0L)
    val downloadedBytes = _downloadedBytes.asStateFlow()
    private val _uploadedBytes = MutableStateFlow(0L)
    val uploadedBytes = _uploadedBytes.asStateFlow()

    fun setReconnectingHint(value: Boolean) {
        _reconnectingHint.value = value
    }

    @MainThread
    internal fun updateState(newState: ConnectionState) {
        val current = _state.value
        if (current == newState) return

        val allowed = when (current) {
            ConnectionState.DISCONNECTED -> setOf(ConnectionState.CONNECTING, ConnectionState.CONNECTED)
            ConnectionState.CONNECTING -> setOf(ConnectionState.CONNECTED, ConnectionState.DISCONNECTED)
            ConnectionState.CONNECTED -> setOf(ConnectionState.DISCONNECTING, ConnectionState.DISCONNECTED)
            ConnectionState.DISCONNECTING -> setOf(ConnectionState.DISCONNECTED)
        }

        if (newState in allowed) {
            _state.value = newState
            when (newState) {
                ConnectionState.DISCONNECTED -> {
                    _speedMbps.value = 0.0
                    _downloadedBytes.value = 0L
                    _uploadedBytes.value = 0L
                    _connectionStartTimeMs.value = null
                }
                ConnectionState.CONNECTED -> {
                    if (current != ConnectionState.CONNECTED) {
                        _connectionStartTimeMs.value = System.currentTimeMillis()
                    }
                }
                ConnectionState.CONNECTING,
                ConnectionState.DISCONNECTING -> Unit
            }
        }
    }

    @MainThread
    fun updateFromEngine(level: ConnectionStatus, detail: String? = null) {
        _engineLevel.value = level
        _engineDetail.value = detail

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

        if (level == ConnectionStatus.LEVEL_AUTH_FAILED) {
            _error.value = VpnError.AUTH
        } else if (mapped != ConnectionState.DISCONNECTED) {
            _error.value = VpnError.NONE
        }

        if (mapped == ConnectionState.CONNECTED) _reconnectingHint.value = false

        val current = _state.value
        val d = detail ?: ""
        var effective = mapped

        if (mapped == ConnectionState.DISCONNECTED) {
            effective = when {
                _reconnectingHint.value -> ConnectionState.CONNECTING
                current == ConnectionState.CONNECTING && (d in setOf("NOPROCESS", "EXITING")) -> ConnectionState.CONNECTING
                current == ConnectionState.DISCONNECTING && (d in setOf("NOPROCESS", "EXITING")) -> ConnectionState.DISCONNECTING
                else -> mapped
            }
        }

        updateState(effective)
    }

    fun updateSpeedMbps(mbps: Double) {
        _speedMbps.value = if (mbps.isFinite() && mbps >= 0) mbps else 0.0
    }

    fun updateTraffic(inBytes: Long, outBytes: Long) {
        _downloadedBytes.value = inBytes.coerceAtLeast(0L)
        _uploadedBytes.value = outBytes.coerceAtLeast(0L)
    }

    @MainThread
    fun restoreConnectionStartIfEmpty(startTimeMs: Long) {
        if (_connectionStartTimeMs.value == null && startTimeMs > 0L) {
            _connectionStartTimeMs.value = startTimeMs
        }
    }
}
