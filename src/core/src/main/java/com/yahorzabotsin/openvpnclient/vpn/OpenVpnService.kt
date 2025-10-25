package com.yahorzabotsin.openvpnclient.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException

class OpenVpnService : VpnService() {

    private val tag = OpenVpnService::class.simpleName
    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationId = VpnManager.NOTIFICATION_ID

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service created.")
        VpnManager.notificationProvider.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(VpnManager.ACTION_VPN)
        Log.d(tag, "onStartCommand with action: $action")

        when (action) {
            VpnManager.ACTION_START -> {
                val config = intent.getStringExtra(VpnManager.EXTRA_CONFIG)
                if (config != null) {
                    // Show foreground notification immediately to comply with FGS requirements
                    val notification = VpnManager.notificationProvider.buildNotification(this, ConnectionState.CONNECTING)
                    startForeground(notificationId, notification)
                    serviceScope.launch {
                        startVpnInternal(config)
                    }
                } else {
                    Log.e(tag, "Attempted to start VPN without a config. Stopping service.")
                    stopVpn()
                }
            }
            VpnManager.ACTION_STOP -> {
                stopVpn()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun startVpnInternal(config: String) {
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        try {
            val profile = withContext(Dispatchers.Default) {
                OvpnProfileParser.parse(config.byteInputStream())
            }
            Log.d(tag, "Parsed profile: $profile")

            val builder = Builder().apply {
                setSession(profile.name ?: "OpenVPN")
                addAddress("192.168.0.1", 24) // Dummy address
                addDnsServer("8.8.8.8")
                addRoute("0.0.0.0", 0)
            }

            vpnInterface = builder.establish() ?: throw IOException("establish() returned null")
            Log.i(tag, "VPN interface established.")
            ConnectionStateManager.updateState(ConnectionState.CONNECTED)
            // Update notification to Connected state
            val updated = VpnManager.notificationProvider.buildNotification(this, ConnectionState.CONNECTED)
            // Using startForeground again ensures notification stays in foreground state and updates content
            startForeground(notificationId, updated)
            // TODO: Pass the vpnInterface FD to the native OpenVPN engine

        } catch (e: Exception) {
            Log.e(tag, "Error starting VPN, stopping service.", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        Log.i(tag, "Stopping VPN...")
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)
        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (e: IOException) {
            Log.w(tag, "Error closing VPN interface", e)
        }
        serviceScope.coroutineContext.cancelChildren()
        Log.d(tag, "Requesting to stop self.")
        // Remove foreground notification and stop service
        stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (vpnInterface != null) {
            Log.w(tag, "Service destroyed unexpectedly. Cleaning up.")
            stopVpn()
        }
        serviceScope.cancel()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        Log.i(tag, "VPN Service destroyed and disconnected.")
    }

    override fun onRevoke() {
        Log.w(tag, "VPN permission revoked!")
        stopVpn()
        super.onRevoke()
    }
}
