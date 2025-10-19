package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log

object VpnManager {

    const val EXTRA_CONFIG = "com.yahorzabotsin.openvpnclient.vpn.CONFIG"
    const val ACTION_VPN = "com.yahorzabotsin.openvpnclient.vpn.ACTION"
    private const val TAG = "VpnManager"

    fun startVpn(context: Context, configData: String) {
        Log.d(TAG, "startVpn called")
        val decodedConfig = String(Base64.decode(configData, Base64.DEFAULT))
        val intent = Intent(context, OpenVpnService::class.java).apply {
            putExtra(EXTRA_CONFIG, decodedConfig)
            putExtra(ACTION_VPN, "start")
        }
        context.startService(intent)
    }

    fun stopVpn(context: Context) {
        Log.d(TAG, "stopVpn called")
        val intent = Intent(context, OpenVpnService::class.java).apply {
            putExtra(ACTION_VPN, "stop")
        }
        context.startService(intent)
    }
}