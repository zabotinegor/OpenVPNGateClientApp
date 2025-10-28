package com.yahorzabotsin.openvpnclient.core

import android.app.Application
import android.content.IntentFilter
import android.Manifest
import com.yahorzabotsin.openvpnclient.vpn.EngineStatusReceiver
import com.yahorzabotsin.openvpnclient.vpn.VpnManager

class CoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VpnManager.notificationProvider.ensureChannel(applicationContext)
        val filter = IntentFilter("de.blinkt.openvpn.VPN_STATUS")
        registerReceiver(EngineStatusReceiver(), filter, Manifest.permission.ACCESS_NETWORK_STATE, null)
    }
}
