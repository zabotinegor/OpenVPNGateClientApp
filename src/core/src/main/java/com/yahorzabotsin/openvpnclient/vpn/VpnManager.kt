package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat

object VpnManager {

    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"
    private val TAG = com.yahorzabotsin.openvpnclient.core.logging.LogTags.APP + ':' + "VpnManager"

    fun extraConfigKey(context: Context) = "${context.packageName}.vpn.CONFIG"
    fun extraTitleKey(context: Context) = "${context.packageName}.vpn.TITLE"
    fun actionKey(context: Context) = "${context.packageName}.vpn.ACTION"

    fun extraAutoSwitchKey(context: Context) = "${context.packageName}.vpn.AUTOSWITCH"
    fun extraPreserveReconnectKey(context: Context) = "${context.packageName}.vpn.PRESERVE_RECONNECT"

    fun startVpn(context: Context, base64Config: String, displayName: String? = null, isReconnect: Boolean = false) {
        Log.d(TAG, "startVpn")
        val decodedConfig = try {
            String(Base64.decode(base64Config, Base64.DEFAULT))
        } catch (_: IllegalArgumentException) {
            base64Config
        }
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(extraConfigKey(context), decodedConfig)
            if (!displayName.isNullOrBlank()) putExtra(extraTitleKey(context), displayName)
            putExtra(actionKey(context), ACTION_START)
            putExtra(extraAutoSwitchKey(context), isReconnect)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopVpn(context: Context, preserveReconnectHint: Boolean = false) {
        Log.d(TAG, "stopVpn")
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(actionKey(context), ACTION_STOP)
            putExtra(extraPreserveReconnectKey(context), preserveReconnectHint)
        }
        ContextCompat.startForegroundService(context, intent)
    }
}

