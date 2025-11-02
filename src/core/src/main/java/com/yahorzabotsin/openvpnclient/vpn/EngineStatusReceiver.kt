package com.yahorzabotsin.openvpnclient.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.MainThread
import de.blinkt.openvpn.core.ConnectionStatus

class EngineStatusReceiver : BroadcastReceiver() {
    private companion object { const val TAG = "EngineStatusReceiver" }
    @MainThread
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "de.blinkt.openvpn.VPN_STATUS") return
        val status = intent.getStringExtra("status") ?: return
        try {
            val level = ConnectionStatus.valueOf(status)
            Log.d(TAG, "Broadcast level=$level")
            ServerAutoSwitcher.onEngineLevel(context.applicationContext, level)
            ConnectionStateManager.updateFromEngine(level)
        } catch (t: Throwable) {
            Log.w(TAG, "Unknown status: $status", t)
        }
    }
}
