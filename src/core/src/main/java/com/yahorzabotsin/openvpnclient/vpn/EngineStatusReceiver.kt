package com.yahorzabotsin.openvpnclient.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.blinkt.openvpn.core.ConnectionStatus

class EngineStatusReceiver : BroadcastReceiver() {
    private companion object { const val TAG = "EngineStatusReceiver" }
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "de.blinkt.openvpn.VPN_STATUS") return
        val status = intent.getStringExtra("status") ?: return
        try {
            val level = ConnectionStatus.valueOf(status)
            when (level) {
                ConnectionStatus.LEVEL_CONNECTED -> ConnectionStateManager.updateState(ConnectionState.CONNECTED)
                ConnectionStatus.LEVEL_START,
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                ConnectionStatus.LEVEL_NOTCONNECTED,
                ConnectionStatus.LEVEL_NONETWORK,
                ConnectionStatus.LEVEL_VPNPAUSED,
                ConnectionStatus.LEVEL_AUTH_FAILED -> ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
                else -> { /* ignore */ }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Unknown status: $status", t)
        }
    }
}
