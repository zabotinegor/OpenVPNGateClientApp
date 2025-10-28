package com.yahorzabotsin.openvpnclient.core

import android.app.Application
import android.content.IntentFilter
import android.os.Build
import com.yahorzabotsin.openvpnclient.vpn.EngineStatusReceiver
import com.yahorzabotsin.openvpnclient.vpn.VpnManager

class CoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VpnManager.notificationProvider.ensureChannel(applicationContext)
        val filter = IntentFilter("de.blinkt.openvpn.VPN_STATUS")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(EngineStatusReceiver(), filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(
                EngineStatusReceiver(),
                filter,
                "com.yahorzabotsin.openvpnclient.core.permission.VPN_STATUS",
                null
            )
        }
    }
}
