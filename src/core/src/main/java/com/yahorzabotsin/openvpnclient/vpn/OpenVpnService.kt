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
class OpenVpnService : Service(), VpnStatus.StateListener, VpnStatus.LogListener, VpnStatus.ByteCountListener {

    private val tag = OpenVpnService::class.simpleName

    private var engineBinder: IOpenVPNServiceInternal? = null
    private var boundToEngine = false
    private var lastTrafficLine: String? = null

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
        // Immediately enter foreground to satisfy startForegroundService timing guarantees.
        startForeground(
            VpnManager.NOTIFICATION_ID,
            VpnManager.notificationProvider.buildNotification(this, ConnectionState.DISCONNECTED)
        )
        // Force engine to use OpenVPN 2 (minivpn), since OpenVPN3 class is not packaged.
        try {
            val prefs = de.blinkt.openvpn.core.Preferences.getDefaultSharedPreferences(this)
            if (prefs.getBoolean("ovpn3", true)) {
                prefs.edit().putBoolean("ovpn3", false).apply()
            }
            // Make the engine's "DISCONNECT" action instant (no confirmation dialog)
            if (!prefs.getBoolean("disableconfirmation", false)) {
                prefs.edit().putBoolean("disableconfirmation", true).apply()
            }
        } catch (t: Throwable) {
            Log.w(tag, "Failed to enforce ovpn2 mode", t)
        }
        VpnStatus.addStateListener(this)
        VpnStatus.addLogListener(this)
        VpnStatus.addByteCountListener(this)
        Log.d(tag, "Service created and VpnStatus listener registered")
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
        // IDs used by ICS OpenVPN engine
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
                Log.i(tag, "ACTION_START received")
                val config = intent.getStringExtra(VpnManager.EXTRA_CONFIG)
                if (config.isNullOrBlank()) {
                    Log.e(tag, "No config provided to start VPN")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(
                    VpnManager.NOTIFICATION_ID,
                    VpnManager.notificationProvider.buildNotification(this, ConnectionState.CONNECTING)
                )
                ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                startIcsOpenVpn(config)
            }
            VpnManager.ACTION_STOP -> {
                Log.i(tag, "ACTION_STOP received")
                // Promote to foreground to satisfy O+ startService background restrictions when STOP is issued from background.
                startForeground(
                    VpnManager.NOTIFICATION_ID,
                    VpnManager.notificationProvider.buildNotification(this, ConnectionState.DISCONNECTING)
                )
                ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)
                requestStopIcsOpenVpn()
            }
            else -> Log.w(tag, "Unknown or missing action: ${intent?.getStringExtra(VpnManager.ACTION_VPN)}")
        }
        return START_STICKY
    }

    private fun startIcsOpenVpn(ovpnConfig: String) {
        try {
            val cp = ConfigParser()
            val isr = InputStreamReader(ByteArrayInputStream(ovpnConfig.toByteArray()))
            cp.parseConfig(isr)
            val profile: VpnProfile = cp.convertProfile().apply {
                // Give the profile a human readable name
                mName = "OpenVPN Client"
                // Some public configs (incl. VPNGate) work best with OpenVPN 2.4 compat
                // Format: major*10000 + minor*100 + patch (e.g., 2.4.0 -> 20400)
                if (mCompatMode == 0) mCompatMode = 20400
            }
            ProfileManager.setTemporaryProfile(this, profile)
            // Assume VPN permission already granted by UI layer before calling VpnManager.startVpn
            VPNLaunchHelper.startOpenVpn(profile, applicationContext, null, true)
            Log.i(tag, "Requested ICS engine to start VPN")
        } catch (e: ConfigParseError) {
            Log.e(tag, "Failed to parse OVPN config", e)
            stopSelf()
        } catch (e: Exception) {
            Log.e(tag, "Error starting VPN", e)
            stopSelf()
        }
    }

    private fun requestStopIcsOpenVpn() {
        // Try to stop via binder to ICS engine
        if (!boundToEngine) {
            val engineIntent = Intent(this, de.blinkt.openvpn.core.OpenVPNService::class.java)
            engineIntent.action = de.blinkt.openvpn.core.OpenVPNService.START_SERVICE
            val bound = bindService(engineIntent, engineConnection, Context.BIND_AUTO_CREATE)
            Log.d(tag, "Binding to ICS OpenVPNService to stop: $bound")
            if (!bound) {
                // If binding fails, just update state and stop
                Log.w(tag, "Failed to bind ICS service for stop")
                stopSelfSafely()
            }
        } else {
            tryStopVpn()
        }
    }

    private fun tryStopVpn() {
        try {
            val stopped = engineBinder?.stopVPN(false) ?: false
            Log.i(tag, "stopVPN invoked, result=$stopped")
        } catch (e: RemoteException) {
            Log.e(tag, "Error stopping VPN via binder", e)
        } finally {
            if (boundToEngine) {
                try { unbindService(engineConnection) } catch (_: Exception) {}
                boundToEngine = false
            }
            // We rely on VpnStatus callback to transition to DISCONNECTED; still schedule safety stop
        }
    }

    private fun updateNotification(state: ConnectionState, content: String? = null) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val effectiveContent = content ?: if (state == ConnectionState.CONNECTED) lastTrafficLine else null
        val n = VpnManager.notificationProvider.buildNotification(this, state, effectiveContent)
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
        VpnStatus.removeByteCountListener(this)
        try { stopForeground(true) } catch (_: Exception) {}
        if (boundToEngine) {
            try { unbindService(engineConnection) } catch (_: Exception) {}
            boundToEngine = false
        }
        Log.d(tag, "Service destroyed and listener removed")
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
                lastTrafficLine = null
                updateNotification(ConnectionState.CONNECTING)
            }
            ConnectionStatus.LEVEL_CONNECTED -> {
                ConnectionStateManager.updateState(ConnectionState.CONNECTED)
                updateNotification(ConnectionState.CONNECTED)
                // Engine posts its own foreground notification; drop ours to avoid duplicates
                try { stopForeground(false) } catch (_: Exception) {}
            }
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_VPNPAUSED,
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                // Transition to a clean stopped state and remove foreground
                ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
                lastTrafficLine = null
                updateNotification(ConnectionState.DISCONNECTED)
                stopSelfSafely()
            }
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                // UI layer should have handled permission; keep connecting state but log.
                Log.d(tag, "Waiting for user input (permission?)")
            }
            else -> { /* ignore other transitional states */ }
        }
    }

    // VpnStatus.ByteCountListener
    override fun updateByteCount(bytesIn: Long, bytesOut: Long, diffIn: Long, diffOut: Long) {
        // Only show live traffic while connected
        lastTrafficLine = buildTrafficLine(bytesIn, bytesOut, diffIn, diffOut)
        updateNotification(ConnectionState.CONNECTED, lastTrafficLine)
    }

    private fun buildTrafficLine(totalIn: Long, totalOut: Long, diffIn: Long, diffOut: Long): String {
        val res = resources
        val interval = de.blinkt.openvpn.core.OpenVPNManagement.mBytecountInterval

        val downloadTotal = de.blinkt.openvpn.core.OpenVPNService.humanReadableByteCount(totalIn, false, res)
        val uploadTotal = de.blinkt.openvpn.core.OpenVPNService.humanReadableByteCount(totalOut, false, res)
        val downloadSpeed = de.blinkt.openvpn.core.OpenVPNService.humanReadableByteCount(
            if (interval > 0) diffIn / interval else diffIn,
            true,
            res
        )
        val uploadSpeed = de.blinkt.openvpn.core.OpenVPNService.humanReadableByteCount(
            if (interval > 0) diffOut / interval else diffOut,
            true,
            res
        )

        return String.format(
            java.util.Locale.getDefault(),
            "\u2193%s %s - \u2191%s %s",
            downloadSpeed,
            downloadTotal,
            uploadSpeed,
            uploadTotal
        )
    }

    override fun setConnectedVPN(uuid: String) {
        // Not used in this adapter service
    }

    // VpnStatus.LogListener
    override fun newLog(logItem: de.blinkt.openvpn.core.LogItem?) {
        if (logItem == null) return
        try {
            val msg = logItem.getString(this)
            // Surface engine logs to Logcat to aid debugging
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




