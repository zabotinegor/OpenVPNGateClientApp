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
        val decodedConfig = String(Base64.decode(base64Config, Base64.DEFAULT))
        val intent = Intent(context, OpenVpnService::class.java).apply {
            putExtra(EXTRA_CONFIG, decodedConfig)
            putExtra(ACTION_VPN, ACTION_START)
        }
        // Start as normal service; UI triggers this while app is in foreground.
        // The service itself promotes to foreground immediately.
        context.startService(intent)
    }

    fun stopVpn(context: Context) {
        Log.d(TAG, "stopVpn called")
        val intent = Intent(context, OpenVpnService::class.java).apply {
            putExtra(ACTION_VPN, ACTION_STOP)
        }
        // For STOP we do not strictly need a foreground start; start as normal service.
        // The service itself will promote to foreground (briefly) while stopping to satisfy O+ rules.
        context.startService(intent)
    }
}
