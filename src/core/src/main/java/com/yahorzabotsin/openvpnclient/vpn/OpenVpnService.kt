package com.yahorzabotsin.openvpnclient.vpn

import android.app.NotificationManager
import android.app.NotificationChannel
import android.os.Build
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConfigParser.ConfigParseError
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

/**
 * Bridge service that adapts our app-level VpnManager to the ICS OpenVPN engine.
 *
 * Responsibilities:
 * - Receive start/stop intents from VpnManager
 * - Parse OVPN config and hand off to ICS engine (VPNLaunchHelper)
 * - Track engine state via VpnStatus and reflect it to ConnectionStateManager
 * - Maintain a foreground notification via NotificationProvider
 */
class OpenVpnService : Service(), VpnStatus.StateListener, VpnStatus.LogListener {

    private companion object { const val TAG = "OpenVpnService" }

    private var engineBinder: IOpenVPNServiceInternal? = null
    private var boundToEngine = false

    private val engineConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            engineBinder = IOpenVPNServiceInternal.Stub.asInterface(service)
            boundToEngine = true
            tryStopVpn()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engineBinder = null
            boundToEngine = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        VpnManager.notificationProvider.ensureChannel(this)
        ensureEngineNotificationChannels()
        startForeground(
            VpnManager.NOTIFICATION_ID,
            VpnManager.notificationProvider.buildNotification(this, ConnectionState.DISCONNECTED)
        )
        try {
            val prefs = de.blinkt.openvpn.core.Preferences.getDefaultSharedPreferences(this)
            if (prefs.getBoolean("ovpn3", true)) {
                prefs.edit().putBoolean("ovpn3", false).apply()
            }
            if (!prefs.getBoolean("disableconfirmation", false)) {
                prefs.edit().putBoolean("disableconfirmation", true).apply()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to enforce ovpn2 mode", t)
        }
        VpnStatus.addStateListener(this)
        VpnStatus.addLogListener(this)
        Log.d(TAG, "Service created and listeners registered")
    }

    private fun ensureEngineNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existingIds = nm.notificationChannels.map { it.id }.toSet()
        fun createIfMissing(id: String, name: String, importance: Int, desc: String) {
            if (!existingIds.contains(id)) {
                val ch = NotificationChannel(id, name, importance).apply {
                    description = desc
                }
                nm.createNotificationChannel(ch)
            }
        }
        createIfMissing(
            de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_BG_ID,
            "OpenVPN Background",
            NotificationManager.IMPORTANCE_MIN,
            "Background status of OpenVPN"
        )
        createIfMissing(
            de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID,
            "OpenVPN Status",
            NotificationManager.IMPORTANCE_LOW,
            "Connection status updates"
        )
        createIfMissing(
            de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID,
            "OpenVPN Requests",
            NotificationManager.IMPORTANCE_HIGH,
            "User action requests (e.g. auth)"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(VpnManager.ACTION_VPN)) {
            VpnManager.ACTION_START -> {
                Log.i(TAG, "ACTION_START")
                val config = intent.getStringExtra(VpnManager.EXTRA_CONFIG)
                val title = intent.getStringExtra(VpnManager.EXTRA_TITLE)
                if (config.isNullOrBlank()) { Log.e(TAG, "No config provided to start VPN"); stopSelf(); return START_NOT_STICKY }
                startForeground(
                    VpnManager.NOTIFICATION_ID,
                    VpnManager.notificationProvider.buildNotification(this, ConnectionState.CONNECTING)
                )
                ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                startIcsOpenVpn(config, title)
            }
            VpnManager.ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP received")
                // Promote to foreground to satisfy O+ startService background restrictions when STOP is issued from background.
                startForeground(
                    VpnManager.NOTIFICATION_ID,
                    VpnManager.notificationProvider.buildNotification(this, ConnectionState.DISCONNECTING)
                )
                ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)
                requestStopIcsOpenVpn()
            }
            else -> Log.w(TAG, "Unknown/missing action: ${intent?.getStringExtra(VpnManager.ACTION_VPN)}")
        }
        return START_STICKY
    }

    private fun startIcsOpenVpn(ovpnConfig: String, displayName: String?) {
        try {
            val cp = ConfigParser()
            val isr = InputStreamReader(ByteArrayInputStream(ovpnConfig.toByteArray()))
            cp.parseConfig(isr)
            val profile: VpnProfile = cp.convertProfile().apply {
                // Set profile display name to country (or app name fallback)
                mName = displayName?.ifBlank { null }
                    ?: try { getString(com.yahorzabotsin.openvpnclient.core.R.string.app_name) } catch (_: Exception) { applicationInfo.loadLabel(packageManager)?.toString() ?: "VPN" }
                // Some public configs (incl. VPNGate) work best with OpenVPN 2.4 compat
                // Format: major*10000 + minor*100 + patch (e.g., 2.4.0 -> 20400)
                if (mCompatMode == 0) mCompatMode = 20400
            }
            ProfileManager.setTemporaryProfile(this, profile)
            // Assume VPN permission already granted by UI layer before calling VpnManager.startVpn
            VPNLaunchHelper.startOpenVpn(profile, applicationContext, null, true)
            Log.i(TAG, "Requested engine start")
        } catch (e: ConfigParseError) {
            Log.e(TAG, "OVPN parse error", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Start error", e)
            stopSelf()
        }
    }

    private fun requestStopIcsOpenVpn() {
        // Try to stop via binder to ICS engine
        if (!boundToEngine) {
            val engineIntent = Intent(this, de.blinkt.openvpn.core.OpenVPNService::class.java)
            engineIntent.action = de.blinkt.openvpn.core.OpenVPNService.START_SERVICE
            val bound = bindService(engineIntent, engineConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Binding engine to stop: $bound")
            if (!bound) { Log.w(TAG, "Failed to bind ICS service for stop"); stopSelfSafely() }
        } else {
            tryStopVpn()
        }
    }

    private fun tryStopVpn() {
        try {
            val stopped = engineBinder?.stopVPN(false) ?: false
            Log.i(TAG, "stopVPN invoked, result=$stopped")
        } catch (e: RemoteException) {
            Log.e(TAG, "Binder stop error", e)
        } finally {
            if (boundToEngine) {
                try { unbindService(engineConnection) } catch (_: Exception) {}
                boundToEngine = false
            }
            
        }
    }

    private fun updateNotification(state: ConnectionState, content: String? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = VpnManager.notificationProvider.buildNotification(this, state, content)
        nm.notify(VpnManager.NOTIFICATION_ID, n)
    }

    private fun stopSelfSafely() {
        try { stopForeground(true) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeLogListener(this)
        try { stopForeground(true) } catch (_: Exception) {}
        if (boundToEngine) {
            try { unbindService(engineConnection) } catch (_: Exception) {}
            boundToEngine = false
        }
        Log.d(TAG, "Service destroyed and listener removed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // VpnStatus.StateListener
    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus,
        intent: Intent?
    ) {
        when (level) {
            ConnectionStatus.LEVEL_START,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> {
                ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                updateNotification(ConnectionState.CONNECTING)
            }
            ConnectionStatus.LEVEL_CONNECTED -> {
                ConnectionStateManager.updateState(ConnectionState.CONNECTED)
                // Hide our adapter notification to avoid duplicates; engine shows its own
                try {
                    stopForeground(true)
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(VpnManager.NOTIFICATION_ID)
                } catch (_: Exception) {}
            }
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_VPNPAUSED,
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
                updateNotification(ConnectionState.DISCONNECTED)
                stopSelfSafely()
            }
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                // UI layer should have handled permission; keep connecting state but log.
                Log.d(TAG, "Waiting for user input")
            }
            else -> { /* ignore other transitional states */ }
        }
    }

    override fun setConnectedVPN(uuid: String) {
        // Not used
    }

    // VpnStatus.LogListener
    override fun newLog(logItem: de.blinkt.openvpn.core.LogItem?) {
        if (logItem == null) return
        try {
            val msg = logItem.getString(this)
            when (logItem.getLogLevel()) {
                de.blinkt.openvpn.core.VpnStatus.LogLevel.ERROR -> Log.e("OpenVPN", msg)
                de.blinkt.openvpn.core.VpnStatus.LogLevel.WARNING -> Log.w("OpenVPN", msg)
                de.blinkt.openvpn.core.VpnStatus.LogLevel.INFO -> Log.i("OpenVPN", msg)
                de.blinkt.openvpn.core.VpnStatus.LogLevel.VERBOSE -> Log.d("OpenVPN", msg)
                else -> Log.d("OpenVPN", msg)
            }
        } catch (_: Exception) {
        }
    }
}







