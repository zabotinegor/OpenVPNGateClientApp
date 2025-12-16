package com.yahorzabotsin.openvpnclient.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DisconnectReceiver : BroadcastReceiver() {
    private val tag = DisconnectReceiver::class.simpleName

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(tag, "Disconnect action received from notification.")
        VpnManager.stopVpn(context)
    }
}

