package com.yahorzabotsin.openvpnclient.core

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.IntentFilter
import android.os.Build
import com.yahorzabotsin.openvpnclient.vpn.EngineStatusReceiver
import com.yahorzabotsin.openvpnclient.vpn.VpnManager

class CoreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VpnManager.notificationProvider.ensureChannel(applicationContext)
        if (isMainProcess()) {
            val filter = IntentFilter("de.blinkt.openvpn.VPN_STATUS")
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(EngineStatusReceiver(), filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(EngineStatusReceiver(), filter)
            }
        }
    }

    private fun isMainProcess(): Boolean {
        val pid = android.os.Process.myPid()
        val am = getSystemService(ActivityManager::class.java)
        val name = am?.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        return name == packageName
    }
}
