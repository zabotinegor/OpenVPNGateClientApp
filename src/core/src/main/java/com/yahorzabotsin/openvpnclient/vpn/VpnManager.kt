package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log

object VpnManager {

    const val EXTRA_CONFIG = "com.yahorzabotsin.openvpnclient.vpn.CONFIG"
    const val ACTION_VPN = "com.yahorzabotsin.openvpnclient.vpn.ACTION"
    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"
    private val TAG = VpnManager::class.simpleName

    fun startVpn(context: Context, base64Config: String) {
        Log.d(TAG, "startVpn called")
        val decodedConfig = String(Base64.decode(base64Config, Base64.DEFAULT))
        val intent = Intent(context, OpenVpnService::class.java).apply {
            putExtra(EXTRA_CONFIG, decodedConfig)
            putExtra(ACTION_VPN, ACTION_START)
        }
        context.startService(intent)
    }

    fun stopVpn(context: Context) {
        Log.d(TAG, "stopVpn called")
        val intent = Intent(context, OpenVpnService::class.java).apply {
            putExtra(ACTION_VPN, ACTION_STOP)
        }
        context.startService(intent)
    }
}