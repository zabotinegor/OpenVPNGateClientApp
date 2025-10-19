package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.content.Intent
import android.util.Base64

object VpnManager {

    const val EXTRA_CONFIG = "com.yahorzabotsin.openvpnclient.vpn.CONFIG"

    fun startVpn(context: Context, configData: String) {
        val decodedConfig = String(Base64.decode(configData, Base64.DEFAULT))
        val intent = Intent(context, OpenVpnService::class.java).apply {
            putExtra(EXTRA_CONFIG, decodedConfig)
        }
        context.startService(intent)
    }
}