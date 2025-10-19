package com.yahorzabotsin.openvpnclient.vpn

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import android.util.Log

class OpenVpnService : VpnService() {

    private val tag = "OpenVpnService"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "onDestroy")
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.d(tag, "onRevoke")
    }
}