package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog

class DisconnectReceiver : BroadcastReceiver() {
    private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "DisconnectReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        AppLog.i(TAG, "Disconnect action received from notification.")
        VpnManager.stopVpn(context)
    }
}



