package com.yahorzabotsin.openvpnclientgate.core.ui.common.components

import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionState
import java.util.Locale

class ConnectionControlsUseCase {

    fun shouldStopForUserSelection(
        state: ConnectionState,
        previousConfig: String?,
        newConfig: String?
    ): Boolean {
        if (previousConfig.isNullOrBlank() || newConfig.isNullOrBlank() || previousConfig == newConfig) return false
        return state != ConnectionState.DISCONNECTED && state != ConnectionState.DISCONNECTING
    }

    fun mapEngineDetailToResId(detail: String?): Int? = when (detail) {
        "CONNECTING" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_connecting
        "WAIT" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_wait
        "AUTH" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_auth
        "VPN_GENERATE_CONFIG" -> com.yahorzabotsin.openvpnclientgate.core.R.string.building_configuration
        "GET_CONFIG" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_get_config
        "ASSIGN_IP" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_assign_ip
        "ADD_ROUTES" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_add_routes
        "CONNECTED" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_connected
        "DISCONNECTED" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_disconnected
        "CONNECTRETRY" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_reconnecting
        "RECONNECTING" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_reconnecting
        "EXITING" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_exiting
        "RESOLVE" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_resolve
        "TCP_CONNECT" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_tcp_connect
        "AUTH_PENDING" -> com.yahorzabotsin.openvpnclientgate.core.R.string.state_auth_pending
        else -> null
    }

    fun trimEllipsis(text: String): String {
        val trimmed = text.trimEnd()
        return when {
            trimmed.endsWith("...") -> trimmed.removeSuffix("...").trimEnd()
            trimmed.endsWith("…") -> trimmed.removeSuffix("…").trimEnd()
            else -> trimmed
        }
    }

    fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        val value = bytes.toDouble()
        return when {
            value >= gb -> String.format(Locale.US, "%.2f GB", value / gb)
            value >= mb -> String.format(Locale.US, "%.2f MB", value / mb)
            value >= kb -> String.format(Locale.US, "%.2f KB", value / kb)
            else -> String.format(Locale.US, "%.0f B", value)
        }
    }
}
