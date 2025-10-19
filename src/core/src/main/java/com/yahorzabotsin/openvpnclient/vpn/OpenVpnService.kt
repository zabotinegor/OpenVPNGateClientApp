package com.yahorzabotsin.openvpnclient.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.IOException

class OpenVpnService : VpnService() {

    private val tag = "OpenVpnService"
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(tag, "onStartCommand")

        when (intent?.getStringExtra(VpnManager.ACTION_VPN)) {
            "start" -> {
                ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                val config = intent.getStringExtra(VpnManager.EXTRA_CONFIG)
                if (config != null) {
                    val profile = OvpnProfileParser.parse(config.byteInputStream())
                    Log.d(tag, "Parsed profile: $profile")
                    startVpn(profile)
                } else {
                    Log.e(tag, "No config provided")
                    stopVpn()
                }
            }
            "stop" -> {
                stopVpn()
            }
        }

        return START_STICKY
    }

    private fun startVpn(profile: OvpnProfile) {
        try {
            val builder = Builder()
                .setSession(profile.name ?: "OpenVPN")
                .addAddress("192.168.0.1", 24) // Dummy address
                .addDnsServer("8.8.8.8")
                .addRoute("0.0.0.0", 0)

            vpnInterface = builder.establish() ?: throw IOException("Failed to establish VPN interface")

            Log.d(tag, "VPN interface established.")
            ConnectionStateManager.updateState(ConnectionState.CONNECTED)
            // TODO: Pass the vpnInterface FD to the native OpenVPN engine

        } catch (e: Exception) {
            Log.e(tag, "Error starting VPN", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.d(tag, "Stopping VPN")
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.e(tag, "Error closing VPN interface", e)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(tag, "onDestroy")
        // Ensure we cleanup if the service is destroyed unexpectedly
        if (vpnInterface != null) {
            stopVpn()
        }
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    override fun onRevoke() {
        super.onRevoke()
        Log.d(tag, "onRevoke")
        stopVpn()
    }
}