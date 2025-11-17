package com.yahorzabotsin.openvpnclient.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.MainThread
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import de.blinkt.openvpn.core.ConnectionStatus

class EngineStatusReceiver : BroadcastReceiver() {
    private companion object { const val TAG = "EngineStatusReceiver" }
    @MainThread
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "de.blinkt.openvpn.VPN_STATUS") return
        val status = intent.getStringExtra("status") ?: return
        val detail = intent.getStringExtra("detailstatus")
        try {
            val level = ConnectionStatus.valueOf(status)
            Log.d(TAG, "Broadcast level=$level detail=${detail ?: "<none>"}")
            if (level == ConnectionStatus.LEVEL_CONNECTED) {
                try {
                    val last = SelectedCountryStore.getLastStartedConfig(context)
                    val cfg = last?.second
                    val country = last?.first
                    Log.d(TAG, "On CONNECTED: lastStartedCountry=${country ?: "<none>"} hasConfig=${cfg != null}")
                    if (!cfg.isNullOrBlank()) {
                        SelectedCountryStore.saveLastSuccessfulConfig(context, country, cfg)
                        Log.d(TAG, "Saved last successful config for country=${country ?: "<none>"}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save last successful config from receiver", e)
                }
            }
            ServerAutoSwitcher.onEngineLevel(context.applicationContext, level)
            ConnectionStateManager.updateFromEngine(level, detail)
        } catch (t: Throwable) {
            Log.w(TAG, "Unknown status: $status", t)
        }
    }
}
