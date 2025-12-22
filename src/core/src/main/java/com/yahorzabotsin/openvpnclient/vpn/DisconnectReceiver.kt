package com.yahorzabotsin.openvpnclient.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DisconnectReceiver : BroadcastReceiver() {
    private val TAG = com.yahorzabotsin.openvpnclient.core.logging.LogTags.APP + ':' + "DisconnectReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Disconnect action received from notification.")
        VpnManager.stopVpn(context)
    }
}

