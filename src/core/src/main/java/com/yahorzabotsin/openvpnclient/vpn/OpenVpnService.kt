package com.yahorzabotsin.openvpnclient.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import com.yahorzabotsin.openvpnclient.core.R
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConfigParser.ConfigParseError
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

class OpenVpnService : Service(), VpnStatus.StateListener, VpnStatus.LogListener {

    private companion object {
        const val TAG = "OpenVpnService"
        private val AUTO_SWITCH_LEVELS = setOf(
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_VPNPAUSED,
            ConnectionStatus.LEVEL_AUTH_FAILED
        )
    }

    private var engineBinder: IOpenVPNServiceInternal? = null
    private var boundToEngine = false
    private var userInitiatedStart = false
    private var userInitiatedStop = false
    private var suppressEngineState = true
    

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
        ensureEngineNotificationChannels()
        try {
            val prefs = de.blinkt.openvpn.core.Preferences.getDefaultSharedPreferences(this)
            if (prefs.getBoolean("ovpn3", true)) prefs.edit().putBoolean("ovpn3", false).apply()
            if (!prefs.getBoolean("disableconfirmation", false)) prefs.edit().putBoolean("disableconfirmation", true).apply()
        } catch (t: Throwable) { Log.w(TAG, "Failed to set default OpenVPN preferences (ovpn3=false, disableconfirmation=true)", t) }
        VpnStatus.addStateListener(this)
        VpnStatus.addLogListener(this)
        Log.d(TAG, "Service created and listeners registered")
    }

    private fun ensureEngineNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.notificationChannels.map { it.id }.toSet()
        fun createIfMissing(id: String, name: String, importance: Int, desc: String) {
            if (!existing.contains(id)) nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { description = desc })
        }
        createIfMissing(de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_BG_ID, "OpenVPN Background", NotificationManager.IMPORTANCE_MIN, "Background status")
        createIfMissing(de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID, "OpenVPN Status", NotificationManager.IMPORTANCE_LOW, "Connection status updates")
        createIfMissing(de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID, "OpenVPN Requests", NotificationManager.IMPORTANCE_HIGH, "User requests")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(VpnManager.actionKey(this))) {
            VpnManager.ACTION_START -> {
                Log.i(TAG, "ACTION_START")
                val config = intent.getStringExtra(VpnManager.extraConfigKey(this))
                val title = intent.getStringExtra(VpnManager.extraTitleKey(this))
                userInitiatedStart = true
                userInitiatedStop = false
                val isReconnect = intent.getBooleanExtra(VpnManager.extraAutoSwitchKey(this), false)
                if (isReconnect) {
                    try { ConnectionStateManager.setReconnectingHint(true); Log.d(TAG, "reconnectHint=true (auto-switch start)") } catch (_: Exception) {}
                } else {
                    try { ConnectionStateManager.setReconnectingHint(false); Log.d(TAG, "reconnectHint=false (manual start)") } catch (_: Exception) {}
                }
                if (config.isNullOrBlank()) { Log.e(TAG, "No config to start"); stopSelf(); return START_NOT_STICKY }
                ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                suppressEngineState = false
                startIcsOpenVpn(config, title)
            }
            VpnManager.ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP")
                userInitiatedStop = true
                userInitiatedStart = false
                try { ConnectionStateManager.setReconnectingHint(false); Log.d(TAG, "reconnectHint=false (user stop)") } catch (_: Exception) {}
                ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)
                requestStopIcsOpenVpn()
            }
            else -> Log.w(TAG, "Unknown/missing action: ${intent?.getStringExtra(VpnManager.actionKey(this))}")
        }
        return START_NOT_STICKY
    }

    private fun startIcsOpenVpn(ovpnConfig: String, displayName: String?) {
        try {
            val cp = ConfigParser()
            val isr = InputStreamReader(ByteArrayInputStream(ovpnConfig.toByteArray()))
            cp.parseConfig(isr)
            val profile: VpnProfile = cp.convertProfile().apply {
                mName = displayName?.ifBlank { null } ?: (try { getString(R.string.app_name) } catch (_: Exception) { applicationInfo.loadLabel(packageManager)?.toString() ?: "VPN" })
                if (mCompatMode == 0) mCompatMode = 20400
            }
            ProfileManager.setTemporaryProfile(this, profile)
            VPNLaunchHelper.startOpenVpn(profile, applicationContext, null, true)
            Log.i(TAG, "Requested engine start")
        } catch (e: ConfigParseError) {
            Log.e(TAG, "OVPN parse error", e); stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Start error", e); stopSelf()
        }
    }

    private fun requestStopIcsOpenVpn() {
        if (!boundToEngine) {
            val engineIntent = Intent(this, de.blinkt.openvpn.core.OpenVPNService::class.java).apply {
                action = de.blinkt.openvpn.core.OpenVPNService.START_SERVICE
            }
            val bound = bindService(engineIntent, engineConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Binding engine to stop: $bound")
            if (!bound) {
                Log.w(TAG, "Bind failed; launching DisconnectVPN")
                try {
                    startActivity(Intent().apply {
                        setClassName(applicationContext, "de.blinkt.openvpn.activities.DisconnectVPN")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (_: Exception) {}
                stopSelfSafely()
            }
        } else tryStopVpn()
    }

    private fun tryStopVpn() {
        try {
            val stopped = engineBinder?.stopVPN(false) ?: false
            Log.i(TAG, "stopVPN invoked, result=$stopped")
        } catch (e: RemoteException) {
            Log.e(TAG, "Binder stop error", e)
        } finally {
            if (boundToEngine) { try { unbindService(engineConnection) } catch (_: Exception) {}; boundToEngine = false }
            if (userInitiatedStop) { ConnectionStateManager.updateState(ConnectionState.DISCONNECTED); userInitiatedStop = false }
        }
    }

    private fun stopSelfSafely() { try { stopForeground(true) } catch (e: Exception) { Log.w(TAG, "Failed to stop foreground service in stopSelfSafely", e) }; stopSelf() }

    override fun onDestroy() {
        super.onDestroy()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeLogListener(this)
        try { stopForeground(true) } catch (e: Exception) { Log.w(TAG, "Failed to stop foreground service on destroy", e) }
        if (boundToEngine) { try { unbindService(engineConnection) } catch (_: Exception) {}; boundToEngine = false }
        
        if (!userInitiatedStart) ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        Log.d(TAG, "Service destroyed and listener removed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @androidx.annotation.MainThread
    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus,
        intent: Intent?
    ) {
        if (suppressEngineState) return

        
        if (userInitiatedStart && level in AUTO_SWITCH_LEVELS) {
            val candidates = try { SelectedCountryStore.getServers(applicationContext).size } catch (_: Exception) { -1 }
            if (candidates >= 0) Log.d(TAG, "Auto-switch candidates in selected country: ${candidates}")
            val next = SelectedCountryStore.nextServer(applicationContext)
            val title = SelectedCountryStore.getSelectedCountry(applicationContext)
            if (next != null) {
                Log.i(TAG, "Auto-switching to next server in country list: ${title} -> ${next.city}")
                try { stopForeground(true) } catch (e: Exception) { Log.w(TAG, "Failed to stop foreground service during server switch", e) }
                try { ConnectionStateManager.setReconnectingHint(true); Log.d(TAG, "reconnectHint=true (engine auto-switch)") } catch (_: Exception) {}
                VpnManager.startVpn(applicationContext, next.config, title, true)
                return
            } else {
                userInitiatedStart = false
                try { ConnectionStateManager.setReconnectingHint(false); Log.d(TAG, "reconnectHint=false (no more servers)") } catch (_: Exception) {}
            }
        }
        ConnectionStateManager.updateFromEngine(level)
        when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                userInitiatedStart = false
                userInitiatedStop = false
                try { stopForeground(true) } catch (e: Exception) { Log.w(TAG, "Failed to stop foreground service after connect", e) }
                stopSelfSafely()
            }
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_VPNPAUSED,
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                if (userInitiatedStop) { userInitiatedStop = false }
                stopSelfSafely()
            }
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                Log.d(TAG, "Waiting for user input")
            }
            else -> {}
        }
    }

    

    override fun setConnectedVPN(uuid: String) { /* not used */ }

    override fun newLog(logItem: de.blinkt.openvpn.core.LogItem?) {
        if (logItem == null) return
        try {
            val msg = logItem.getString(this)
            when (logItem.logLevel) {
                VpnStatus.LogLevel.ERROR -> Log.e("OpenVPN", msg)
                VpnStatus.LogLevel.WARNING -> Log.w("OpenVPN", msg)
                VpnStatus.LogLevel.INFO -> Log.i("OpenVPN", msg)
                VpnStatus.LogLevel.VERBOSE -> Log.d("OpenVPN", msg)
                else -> Log.d("OpenVPN", msg)
            }
        } catch (_: Exception) { }
    }
}
