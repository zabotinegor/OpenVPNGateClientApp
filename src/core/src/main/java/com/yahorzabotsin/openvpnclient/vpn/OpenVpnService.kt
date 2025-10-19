package com.yahorzabotsin.openvpnclient.vpn

import android.content.Intent
import android.net.VpnService
import android.util.Log

class OpenVpnService : VpnService() {

    private val tag = "OpenVpnService"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "onStartCommand")
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        val config = intent?.getStringExtra(VpnManager.EXTRA_CONFIG)
        if (config != null) {
            val profile = OvpnProfileParser.parse(config.byteInputStream())
            Log.d(tag, "Parsed profile: $profile")
            // TODO: Start VPN connection using the profile
        } else {
            Log.e(tag, "No config provided")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "onDestroy")
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.d(tag, "onRevoke")
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }
}