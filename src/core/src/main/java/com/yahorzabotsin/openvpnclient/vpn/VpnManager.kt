package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat

object VpnManager {

    const val EXTRA_CONFIG = "com.yahorzabotsin.openvpnclient.vpn.CONFIG"
    const val EXTRA_TITLE = "com.yahorzabotsin.openvpnclient.vpn.TITLE"
    const val ACTION_VPN = "com.yahorzabotsin.openvpnclient.vpn.ACTION"
    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"
    const val NOTIFICATION_ID = 1001
    var notificationProvider: NotificationProvider = DefaultNotificationProvider
    private const val TAG = "VpnManager"

    fun startVpn(context: Context, base64Config: String, displayName: String? = null) {
        Log.d(TAG, "startVpn")
        val decodedConfig = try {
            String(Base64.decode(base64Config, Base64.DEFAULT))
        } catch (_: IllegalArgumentException) {
            base64Config
        }
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(EXTRA_CONFIG, decodedConfig)
            if (!displayName.isNullOrBlank()) putExtra(EXTRA_TITLE, displayName)
            putExtra(ACTION_VPN, ACTION_START)
        }
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    fun stopVpn(context: Context) {
        Log.d(TAG, "stopVpn")
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(ACTION_VPN, ACTION_STOP)
        }
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }
}
