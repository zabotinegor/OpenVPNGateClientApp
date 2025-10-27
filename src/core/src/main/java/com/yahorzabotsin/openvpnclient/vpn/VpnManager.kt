package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat

object VpnManager {

    const val EXTRA_CONFIG = "com.yahorzabotsin.openvpnclient.vpn.CONFIG"
    const val ACTION_VPN = "com.yahorzabotsin.openvpnclient.vpn.ACTION"
    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"
    const val NOTIFICATION_ID = 1001
    var notificationProvider: NotificationProvider = DefaultNotificationProvider
    private val TAG = VpnManager::class.simpleName

    fun startVpn(context: Context, base64Config: String) {
        Log.d(TAG, "startVpn called")
        val decodedConfig = try {
            String(Base64.decode(base64Config, Base64.DEFAULT))
        } catch (_: IllegalArgumentException) {
            // Fallback: treat the input as plain config if not valid Base64
            base64Config
        }
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(EXTRA_CONFIG, decodedConfig)
            putExtra(ACTION_VPN, ACTION_START)
        }
        // Use foreground service start to satisfy Android O+ background restrictions
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    fun stopVpn(context: Context) {
        Log.d(TAG, "stopVpn called")
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(ACTION_VPN, ACTION_STOP)
        }
        // Start as foreground service for robust background delivery on O+
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }
}
