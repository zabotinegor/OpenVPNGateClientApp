package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.Handler
import android.app.PendingIntent
import android.content.res.Configuration
import android.os.LocaleList
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.NotificationCompat
import com.yahorzabotsin.openvpnclientgate.core.BuildConfig
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.settings.DnsOption
import com.yahorzabotsin.openvpnclientgate.core.settings.DnsOptions
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConfigParser.ConfigParseError
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.IServiceStatus
import de.blinkt.openvpn.core.IStatusCallbacks
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import de.blinkt.openvpn.core.TrafficHistory
import de.blinkt.openvpn.core.StatusSnapshot
import com.yahorzabotsin.openvpnclientgate.core.filter.AppFilterStore
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

class OpenVpnService : Service(), VpnStatus.StateListener, VpnStatus.LogListener, VpnStatus.ByteCountListener {

    private companion object {
        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "OpenVpnService"
        const val DEFAULT_COMPAT_MODE = 20400
        const val KEY_OVPN3 = "ovpn3"
        const val KEY_DISABLE_CONFIRMATION = "disableconfirmation"
        const val FOREGROUND_NOTIFICATION_ID = 1101
        const val FOREGROUND_CHANNEL_ID = "openvpn_controller"
        private val AUTO_SWITCH_LEVELS = setOf(
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_AUTH_FAILED
        )
    }

    // Track engine binding for start/stop coordination
    private var engineBinder: IOpenVPNServiceInternal? = null
    private var boundToEngine = false

    // Remember whether start/stop were user-driven vs auto-switch
    private var userInitiatedStart = false
    private var userInitiatedStop = false

    // Suppress duplicate engine state callbacks while we manage retries
    private var suppressEngineState = true

    // Track per-session auto-switch attempts
    private var sessionTotalServers: Int = -1
    private var sessionAttempt: Int = 0
    private var foregroundStarted = false

    // Byte count tracking for local listener vs AIDL callbacks
    private var lastLocalByteUpdateTs: Long = 0L
    private var aidlLastInBytes: Long = 0L
    private var aidlLastOutBytes: Long = 0L
    private var lastAidlByteUpdateTs: Long = 0L

    // Binding to status service for engine logs/metrics
    private var statusBinder: IServiceStatus? = null
    private var boundToStatus = false
    private var statusRebindDelayMs = 500L
    private var lastStatusSnapshotMs: Long = 0L
    private var lastLiveStatusMs: Long = 0L
    private var staleSnapshotCount: Int = 0
    private enum class StatusSource { AIDL, VPN_STATUS }
    private var statusSource: StatusSource? = null
    private var lastStatusSourceSwitchMs: Long = 0L
    private val aidlFreshWindowMs = 3_000L
    private val staleSnapshotTimeoutLevels = setOf(
        ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
        ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
        ConnectionStatus.LEVEL_AUTH_FAILED,
        ConnectionStatus.UNKNOWN_LEVEL
    )
    private val staleSnapshotMaxAgeMs = 10_000L
    private val liveStatusGraceMs = 5_000L
    private val statusHandler = Handler(Looper.getMainLooper())
    private val trafficHandler = Handler(Looper.getMainLooper())
    private var lastPolledDatapoint: TrafficHistory.TrafficDatapoint? = null
    private var lastPolledState: ConnectionState? = null
    private var lastAidlLevel: ConnectionStatus? = null
    private var lastAidlState: String? = null
    private var lastVpnStatusLevel: ConnectionStatus? = null
    private var lastVpnStatusState: String? = null
    private var lastEngineLevel: ConnectionStatus? = null
    private var lastEngineDetail: String? = null
    private var lastEngineLevelLogMs: Long = 0L

    private fun totalServersStr(): String =
        if (sessionTotalServers >= 0) sessionTotalServers.toString() else "unknown"
    

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
        Log.i(TAG, "Service created")
        ensureServiceNotificationChannel()
        startForegroundIfNeeded()
        ensureEngineNotificationChannels()
        ensureEnginePreferences()
        VpnStatus.addStateListener(this)
        VpnStatus.addLogListener(this)
        VpnStatus.addByteCountListener(this)
        bindStatusService()

        trafficHandler.post(trafficPollRunnable)
    }

    private fun updateStatusSource(source: StatusSource, reason: String) {
        if (statusSource != source) {
            statusSource = source
            lastStatusSourceSwitchMs = System.currentTimeMillis()
            Log.i(TAG, "Status source -> ${source.name} (${reason})")
        }
    }

    private fun logEngineStateChange(
        source: String,
        level: ConnectionStatus,
        state: String?
    ) {
        val previousLevel: ConnectionStatus?
        val previousState: String?
        when (source) {
            "AIDL" -> {
                previousLevel = lastAidlLevel
                previousState = lastAidlState
                lastAidlLevel = level
                lastAidlState = state
            }
            "VPN_STATUS" -> {
                previousLevel = lastVpnStatusLevel
                previousState = lastVpnStatusState
                lastVpnStatusLevel = level
                lastVpnStatusState = state
            }
            else -> {
                previousLevel = null
                previousState = null
            }
        }
        if (previousLevel != level || previousState != state) {
            Log.d(TAG, "Engine state (${source}): level=${level} state=${state ?: "<null>"}")
        }
    }

    private fun isAidlFresh(): Boolean {
        val now = System.currentTimeMillis()
        return boundToStatus && lastLiveStatusMs > 0L && (now - lastLiveStatusMs) <= aidlFreshWindowMs
    }

    private fun shouldUseVpnStatus(): Boolean = !isAidlFresh()
    private fun bindStatusService() {
        try {
            val statusIntent = Intent().apply { setClassName(applicationContext, "de.blinkt.openvpn.core.OpenVPNStatusService") }
            try {
                startService(statusIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start status service", e)
            }
            boundToStatus = bindService(statusIntent, statusConnection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Binding status service: $boundToStatus")
            if (!boundToStatus) {
                scheduleStatusRebind()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to bind status service", t)
            scheduleStatusRebind()
        }
    }

    private val statusDeathRecipient = IBinder.DeathRecipient {
        Log.w(TAG, "Status binder died; scheduling rebind")
        statusBinder = null
        boundToStatus = false
        updateStatusSource(StatusSource.VPN_STATUS, "status binder died")
        scheduleStatusRebind()
    }

    private fun scheduleStatusRebind() {
        statusHandler.removeCallbacks(statusRebindRunnable)
        statusHandler.postDelayed(statusRebindRunnable, statusRebindDelayMs)
        Log.d(TAG, "Scheduled status rebind in ${statusRebindDelayMs}ms")
        statusRebindDelayMs = (statusRebindDelayMs * 2).coerceAtMost(8_000L)
    }

    private val statusRebindRunnable = Runnable {
        if (boundToStatus) return@Runnable
        bindStatusService()
    }

    private fun ensureEnginePreferences() {
        try {
            val prefs = de.blinkt.openvpn.core.Preferences.getDefaultSharedPreferences(this)
            if (prefs.getBoolean(KEY_OVPN3, true)) prefs.edit().putBoolean(KEY_OVPN3, false).apply()
            if (!prefs.getBoolean(KEY_DISABLE_CONFIRMATION, false)) prefs.edit().putBoolean(KEY_DISABLE_CONFIRMATION, true).apply()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set default OpenVPN preferences (ovpn3=false, disableconfirmation=true)", t)
        }
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

    private fun ensureServiceNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.notificationChannels.any { it.id == FOREGROUND_CHANNEL_ID }) return
        logNotificationStatus("before channel create")
        nm.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "VPN Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "VPN controller running in background" }
        )
        logNotificationStatus("after channel create")
    }

    private fun buildForegroundNotification(titleRes: Int, textRes: Int): Notification {
        val localizedCtx = localizedContext()
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val title = safeString(localizedCtx, titleRes, "VPN")
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_icon_system)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun safeString(ctx: Context, resId: Int, fallback: String): String =
        try {
            ctx.getString(resId)
        } catch (_: Exception) {
            fallback
        }

    private fun localizedContext(): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return this
        val config = Configuration(resources.configuration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
        } else {
            @Suppress("DEPRECATION")
            config.setLocale(locales[0])
        }
        return createConfigurationContext(config)
    }

    private fun startForegroundIfNeeded() {
        if (foregroundStarted) return
        if (isRobolectric()) {
            foregroundStarted = true
            return
        }
        logNotificationStatus("before startForeground")
        val notification = buildForegroundNotification(
            R.string.vpn_notification_title_service_running,
            R.string.vpn_notification_text_connecting
        )
        try {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
            foregroundStarted = true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start foreground", e)
        }
    }

    private fun logNotificationStatus(reason: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.getNotificationChannel(FOREGROUND_CHANNEL_ID)
        } else {
            null
        }
        val notificationsEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            nm.areNotificationsEnabled()
        } else {
            true
        }
        val permissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            channel?.importance?.toString() ?: "null"
        } else {
            "n/a"
        }
        Log.i(
            TAG,
            "Notification status (${reason}): enabled=${notificationsEnabled}, permission=${permissionGranted}, channel=${channel?.id ?: "null"}, importance=${importance}"
        )
    }

    private fun stopForegroundIfStarted() {
        if (!foregroundStarted) return
        try {
            stopForeground(true)
            foregroundStarted = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop foreground notification", e)
        }
    }

    private fun refreshForegroundNotification() {
        if (!foregroundStarted || isRobolectric()) return
        val (titleRes, textRes) = if (ConnectionStateManager.state.value == ConnectionState.CONNECTED) {
            R.string.vpn_notification_title_connected to R.string.vpn_notification_text_connected
        } else {
            R.string.vpn_notification_title_connecting to R.string.vpn_notification_text_connecting
        }
        updateForegroundNotification(titleRes, textRes)
    }

    private fun handleForegroundForLevel(level: ConnectionStatus) {
        when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                stopForegroundIfStarted()
            }
            ConnectionStatus.LEVEL_START,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                stopForegroundIfStarted()
            }
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_VPNPAUSED,
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                startForegroundIfNeeded()
                updateForegroundNotification(
                    R.string.vpn_notification_title_service_running,
                    R.string.vpn_notification_text_connecting
                )
            }
            else -> {}
        }
    }

    private fun updateForegroundNotification(titleRes: Int, textRes: Int) {
        if (isRobolectric()) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification(titleRes, textRes)
        )
    }

    private fun isRobolectric(): Boolean =
        try {
            Class.forName("org.robolectric.RuntimeEnvironment")
            true
        } catch (_: Throwable) {
            false
        }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(VpnManager.actionKey(this))) {
            VpnManager.ACTION_START -> {
                Log.i(TAG, "ACTION_START")
                startForegroundIfNeeded()
                val config = intent.getStringExtra(VpnManager.extraConfigKey(this))
                val title = intent.getStringExtra(VpnManager.extraTitleKey(this))
                userInitiatedStart = true
                userInitiatedStop = false
                val isReconnect = intent.getBooleanExtra(VpnManager.extraAutoSwitchKey(this), false)
                try {
                    ConnectionStateManager.setReconnectingHint(isReconnect)
                    Log.d(TAG, "reconnectHint=${isReconnect} (start)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set reconnecting hint on start", e)
                }
                  if (isReconnect) {
                    sessionAttempt = if (sessionAttempt <= 0) 1 else sessionAttempt + 1
                  } else {
                      sessionTotalServers = try { SelectedCountryStore.getServers(applicationContext).size } catch (_: Exception) { -1 }
                      sessionAttempt = 1
                  }
                if (config.isNullOrBlank()) { Log.e(TAG, "No config to start"); stopSelf(); return START_NOT_STICKY }
                val targetIp = runCatching { SelectedCountryStore.getIpForConfig(applicationContext, config) }.getOrNull()
                    ?: runCatching { SelectedCountryStore.currentServer(applicationContext)?.ip }.getOrNull()
                try {
                    SelectedCountryStore.ensureIndexForConfig(applicationContext, config, targetIp)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to align server index with config being started", e)
                }
                run {
                    val titleStr = title?.let { ": $it" } ?: ""
                    val position = runCatching { SelectedCountryStore.getCurrentPosition(applicationContext) }.getOrNull()
                    val positionStr = position?.let { "${it.first}/${it.second}" } ?: "unknown"
                    val ipStr = targetIp ?: runCatching { SelectedCountryStore.currentServer(applicationContext)?.ip }.getOrNull()
                    Log.i(TAG, "Session attempt ${sessionAttempt} (serversInCountry=${totalServersStr()}, server=${positionStr}, ip=${ipStr ?: "<none>"})${titleStr}")
                }
                try {
                    SelectedCountryStore.saveLastStartedConfig(applicationContext, title, config, targetIp)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist last started config", e)
                }
                ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                suppressEngineState = false
                startIcsOpenVpn(config, title)
            }
            VpnManager.ACTION_STOP -> {
                Log.i(TAG, "ACTION_STOP")
                val preserveReconnect = intent.getBooleanExtra(VpnManager.extraPreserveReconnectKey(this), false)
                if (preserveReconnect) {
                    // Auto-switch retry: keep hint and state, just stop engine
                    Log.d(TAG, "Preserving reconnect hint/state for retry stop")
                    userInitiatedStop = false
                    userInitiatedStart = true
                    requestStopIcsOpenVpn()
                } else {
                userInitiatedStop = true
                userInitiatedStart = false
                try { ConnectionStateManager.setReconnectingHint(false); Log.d(TAG, "reconnectHint=false (user stop)") } catch (e: Exception) { Log.w(TAG, "Failed to clear reconnecting hint on user stop", e) }
                try { ConnectionStateManager.updateSpeedMbps(0.0) } catch (_: Exception) {}
                ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)
                requestStopIcsOpenVpn()
            }
            }
            VpnManager.ACTION_REFRESH_NOTIFICATION -> {
                Log.d(TAG, "ACTION_REFRESH_NOTIFICATION")
                refreshForegroundNotification()
            }
            else -> if (intent != null) {
                Log.w(TAG, "Unknown/missing action: ${intent.getStringExtra(VpnManager.actionKey(this))}")
            }
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
                if (mCompatMode == 0) mCompatMode = DEFAULT_COMPAT_MODE
            }
            applyAppFilter(profile)
            applyDnsSettings(profile)
            ProfileManager.setTemporaryProfile(this, profile)
            VPNLaunchHelper.startOpenVpn(profile, applicationContext, null, true)
            Log.i(TAG, "Requested engine start (profile=${profile.mName})")
            stopForegroundIfStarted()
        } catch (e: ConfigParseError) {
            Log.e(TAG, "OVPN parse error", e); stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Start error", e); stopSelf()
        }
    }

    private fun applyAppFilter(profile: VpnProfile) {
        try {
            val excluded = AppFilterStore.loadExcludedPackages(applicationContext)
            profile.mAllowedAppsVpn.clear()
            profile.mAllowedAppsVpnAreDisallowed = true
            if (excluded.isNotEmpty()) {
                profile.mAllowedAppsVpn.addAll(excluded)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to apply app filter", t)
        }
    }

    private fun applyDnsSettings(profile: VpnProfile) {
        val option = try {
            UserSettingsStore.load(applicationContext).dnsOption
        } catch (_: Exception) {
            DnsOption.SERVER
        }
        val config = DnsOptions.resolve(option)
        if (!config.overrideDns) {
            profile.mOverrideDNS = false
            Log.i(TAG, "DNS apply: option=${option.name}, override=false (use server DNS)")
            return
        }
        profile.mOverrideDNS = true
        profile.mDNS1 = config.primary ?: ""
        profile.mDNS2 = config.secondary ?: ""
        Log.i(TAG, "DNS apply: option=${option.name}, dns1=${profile.mDNS1}, dns2=${profile.mDNS2}")
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
                } catch (e: Exception) { Log.w(TAG, "Failed to start DisconnectVPN activity", e) }
                stopSelfSafely()
            }
        } else tryStopVpn()
    }

    private fun tryStopVpn() {
        val userStop = userInitiatedStop
        try {
            val stopped = engineBinder?.stopVPN(false) ?: false
            Log.i(TAG, "stopVPN invoked, result=$stopped")
            if (!stopped && userStop) {
                Log.w(TAG, "stopVPN returned false on user stop; launching DisconnectVPN")
                try {
                    startActivity(Intent().apply {
                        setClassName(applicationContext, "de.blinkt.openvpn.activities.DisconnectVPN")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) { Log.w(TAG, "Failed to start DisconnectVPN activity", e) }
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "Binder stop error", e)
        } finally {
            if (boundToEngine) { try { unbindService(engineConnection) } catch (e: Exception) { Log.w(TAG, "Failed to unbind engine after stop", e) }; boundToEngine = false }
            if (userInitiatedStop) { ConnectionStateManager.updateState(ConnectionState.DISCONNECTED); userInitiatedStop = false }
        }
    }

    private fun stopSelfSafely() { try { stopForeground(true) } catch (e: Exception) { Log.w(TAG, "Failed to stop foreground service in stopSelfSafely", e) }; stopSelf() }

    override fun onDestroy() {
        super.onDestroy()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeLogListener(this)
        try { VpnStatus.removeByteCountListener(this) } catch (_: Exception) {}
        statusHandler.removeCallbacks(statusRebindRunnable)
        trafficHandler.removeCallbacks(trafficPollRunnable)
        lastPolledDatapoint = null
        lastPolledState = null
        if (boundToStatus) {
            try { statusBinder?.unregisterStatusCallback(statusCallbacks) } catch (_: Exception) {}
            try { unbindService(statusConnection) } catch (_: Exception) {}
            boundToStatus = false
            statusBinder = null
        }
        try { stopForeground(true) } catch (e: Exception) { Log.w(TAG, "Failed to stop foreground service on destroy", e) }
        if (boundToEngine) { try { unbindService(engineConnection) } catch (e: Exception) { Log.w(TAG, "Failed to unbind engine on destroy", e) }; boundToEngine = false }
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
        if (!shouldUseVpnStatus()) {
            updateStatusSource(StatusSource.AIDL, "AIDL fresh; ignore VpnStatus")
            logEngineStateChange("VPN_STATUS", level, state)
            return
        }
        updateStatusSource(StatusSource.VPN_STATUS, "VpnStatus update")
        logEngineStateChange("VPN_STATUS", level, state)
        Log.d(TAG, "Auto-switch source=VPN_STATUS (updateState)")
        try { ServerAutoSwitcher.onEngineLevel(applicationContext, level, "VPN_STATUS") } catch (e: Exception) { Log.w(TAG, "Failed to notify auto-switcher from updateState", e) }
        ConnectionStateManager.updateFromEngine(level, state)
        handleForegroundForLevel(level)
        if (suppressEngineState) return

        if (userInitiatedStart && level in AUTO_SWITCH_LEVELS && !ConnectionStateManager.reconnectingHint.value) {
            val autoSwitchEnabled = try { com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore.load(applicationContext).autoSwitchWithinCountry } catch (_: Exception) { true }
            if (!autoSwitchEnabled) {
                Log.d(TAG, "Auto-switch disabled; skipping engine auto-switch path")
            } else {
                val candidates = try { SelectedCountryStore.getServers(applicationContext).size } catch (_: Exception) { -1 }
                if (candidates >= 0) Log.d(TAG, "Auto-switch candidates in selected country: ${candidates}")
                val next = SelectedCountryStore.nextServer(applicationContext)
                val title = SelectedCountryStore.getSelectedCountry(applicationContext)
                if (next != null) {
                val position = runCatching { SelectedCountryStore.getCurrentPosition(applicationContext) }.getOrNull()
                val positionStr = position?.let { "${it.first}/${it.second}" } ?: "unknown"
                Log.i(TAG, "Auto-switching to next server in country list: ${title} -> ${next.city} (server=${positionStr}, ip=${next.ip ?: "<none>"})")
                try { stopForeground(true) } catch (e: Exception) { Log.w(TAG, "Failed to stop foreground service during server switch", e) }
                try { ConnectionStateManager.setReconnectingHint(true); Log.d(TAG, "reconnectHint=true (engine auto-switch)") } catch (e: Exception) { Log.w(TAG, "Failed to set reconnecting hint for engine auto-switch", e) }
                try { ServerAutoSwitcher.beginChainedSwitch(applicationContext, next.config, title) } catch (e: Exception) { Log.e(TAG, "Failed to begin chained server switch", e) }
                return
              } else {
                  userInitiatedStart = false
                  try { ConnectionStateManager.setReconnectingHint(false); Log.d(TAG, "reconnectHint=false (no more servers)") } catch (e: Exception) { Log.w(TAG, "Failed to clear reconnecting hint when no more servers", e) }
                Log.i(TAG, "Exhausted server list without success after ${sessionAttempt} attempts (serversInCountry=${totalServersStr()})")
              }
            }
        }
        when (level) {
              ConnectionStatus.LEVEL_CONNECTED -> {
                  userInitiatedStart = false
                  userInitiatedStop = false
                Log.i(TAG, "Connected after attempt ${sessionAttempt} (serversInCountry=${totalServersStr()})")
            }
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_VPNPAUSED,
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                if (userInitiatedStop) { userInitiatedStop = false }
            }
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                Log.d(TAG, "Waiting for user input")
            }
            else -> {}
        }
    }

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        if (boundToStatus) return
        val now = System.currentTimeMillis()
        val last = lastLocalByteUpdateTs
        lastLocalByteUpdateTs = now
        val deltaMs = if (last > 0) (now - last).coerceAtLeast(1) else 1000L
        val totalDiffBytes = (diffIn + diffOut).coerceAtLeast(0)
        val bitsPerSec = (totalDiffBytes * 8.0) * (1000.0 / deltaMs.toDouble())
        val mbps = bitsPerSec / 1_000_000.0
        ConnectionStateManager.updateSpeedMbps(mbps)
        ConnectionStateManager.updateTraffic(inBytes, outBytes)
    }

    private val statusCallbacks = object : IStatusCallbacks.Stub() {
        override fun newLogItem(item: de.blinkt.openvpn.core.LogItem?) { }

        override fun updateStateString(
            state: String?,
            msg: String?,
            resid: Int,
            level: ConnectionStatus?,
            intent: Intent?
        ) {
            if (level == null) return
            lastStatusSnapshotMs = System.currentTimeMillis()
            lastLiveStatusMs = lastStatusSnapshotMs
            staleSnapshotCount = 0
            updateStatusSource(StatusSource.AIDL, "AIDL update")
            logEngineStateChange("AIDL", level, state)
            try {
                syncEngineState(level, state, allowAutoSwitch = true)
                if (level == ConnectionStatus.LEVEL_CONNECTED) {
                    persistLastSuccessfulConfig()
                    tryRestoreTrafficSnapshot()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to sync state from status service: level=$level state=$state", t)
            }
        }

        override fun connectedVPN(uuid: String?) { }

        override fun notifyProfileVersionChanged(uuid: String?, profileVersion: Int) { }

        override fun updateByteCount(inBytes: Long, outBytes: Long) {
            val now = System.currentTimeMillis()
            val last = lastAidlByteUpdateTs
            val prevIn = aidlLastInBytes
            val prevOut = aidlLastOutBytes
            aidlLastInBytes = inBytes
            aidlLastOutBytes = outBytes
            lastAidlByteUpdateTs = now
            val deltaMs = if (last > 0) (now - last).coerceAtLeast(1) else 1000L
            val diffIn = (inBytes - prevIn).coerceAtLeast(0)
            val diffOut = (outBytes - prevOut).coerceAtLeast(0)
            val totalDiffBytes = diffIn + diffOut
            val bitsPerSec = (totalDiffBytes * 8.0) * (1000.0 / deltaMs.toDouble())
            val mbps = bitsPerSec / 1_000_000.0
            ConnectionStateManager.updateSpeedMbps(mbps)
            ConnectionStateManager.updateTraffic(inBytes, outBytes)
        }
    }

    private fun tryRestoreTrafficSnapshot() {
        val binder = statusBinder ?: return
        val history: TrafficHistory = try {
            binder.trafficHistory
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to get traffic history from status service", e)
            return
        } ?: return

        val seconds = history.seconds
        val minutes = history.minutes
        val hours = history.hours

        val nonEmptyLists = listOf(seconds, minutes, hours).filter { it.isNotEmpty() }
        if (nonEmptyLists.isEmpty()) return

        val earliest = nonEmptyLists
            .map { it.first() }
            .minByOrNull { it.timestamp }
            ?: return
        val latest = nonEmptyLists
            .map { it.last() }
            .maxByOrNull { it.timestamp }
            ?: return

        ConnectionStateManager.restoreConnectionStartIfEmpty(earliest.timestamp)
        ConnectionStateManager.updateTraffic(latest.`in`, latest.out)
    }

    private val statusConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                Log.w(TAG, "Status service connected with null binder; scheduling rebind")
                statusBinder = null
                boundToStatus = false
                scheduleStatusRebind()
                return
            }
            statusBinder = IServiceStatus.Stub.asInterface(service)
            boundToStatus = true
            statusRebindDelayMs = 500L
            updateStatusSource(StatusSource.AIDL, "status service connected")
            Log.i(TAG, "Status service connected")
            try {
                service?.linkToDeath(statusDeathRecipient, 0)
            } catch (e: RemoteException) {
                Log.w(TAG, "Failed to link status binder death", e)
            }
            try {
                statusBinder?.registerStatusCallback(statusCallbacks)
            } catch (e: RemoteException) {
                Log.e(TAG, "Failed to register status callback", e)
            }
            trySyncStatusSnapshot()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            statusBinder = null
            boundToStatus = false
            updateStatusSource(StatusSource.VPN_STATUS, "status service disconnected")
            Log.w(TAG, "Status service disconnected")
            scheduleStatusRebind()
        }
    }

    private val trafficPollRunnable = object : Runnable {
        override fun run() {
            try {
                val snapshotBinder = statusBinder
                if (snapshotBinder != null) {
                    val now = System.currentTimeMillis()
                    if (lastStatusSnapshotMs == 0L || now - lastStatusSnapshotMs > 5_000L) {
                        trySyncStatusSnapshot()
                    }
                }

                val currentState = ConnectionStateManager.state.value
                if (currentState != lastPolledState) {
                    if (currentState != ConnectionState.CONNECTED) {
                        lastPolledDatapoint = null
                    }
                    lastPolledState = currentState
                }

                if (currentState == ConnectionState.CONNECTED) {
                    val trafficBinder = statusBinder
                    if (trafficBinder != null) {
                        val history = try {
                            trafficBinder.trafficHistory
                        } catch (_: Exception) {
                            null
                        }

                        if (history != null) {
                            val seconds = history.seconds
                            val minutes = history.minutes
                            val hours = history.hours
                            val nonEmptyLists = listOf(seconds, minutes, hours).filter { it.isNotEmpty() }
                            if (nonEmptyLists.isNotEmpty()) {
                                val latest = nonEmptyLists.maxByOrNull { it.last().timestamp }!!.last()
                                val previous = lastPolledDatapoint

                                if (previous != null && latest.timestamp > previous.timestamp) {
                                    val deltaMs = (latest.timestamp - previous.timestamp).coerceAtLeast(1L)
                                    val diffIn = (latest.`in` - previous.`in`).coerceAtLeast(0L)
                                    val diffOut = (latest.out - previous.out).coerceAtLeast(0L)
                                    val totalDiffBytes = diffIn + diffOut
                                    val bitsPerSec = (totalDiffBytes * 8.0) * (1000.0 / deltaMs.toDouble())
                                    val mbps = bitsPerSec / 1_000_000.0
                                    ConnectionStateManager.updateSpeedMbps(mbps)
                                }

                                ConnectionStateManager.updateTraffic(latest.`in`, latest.out)
                                lastPolledDatapoint = latest
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "Error in trafficPollRunnable", e)
                }
            }

            trafficHandler.postDelayed(this, 2_000L)
        }
    }

    override fun setConnectedVPN(uuid: String) { /* not used */ }

    override fun newLog(logItem: de.blinkt.openvpn.core.LogItem?) {
        if (logItem == null) return
        try {
            val msg = logItem.getString(this)
            when (logItem.logLevel) {
                VpnStatus.LogLevel.ERROR -> Log.e(TAG, msg)
                VpnStatus.LogLevel.WARNING -> Log.w(TAG, msg)
                VpnStatus.LogLevel.INFO -> Log.i(TAG, msg)
                VpnStatus.LogLevel.VERBOSE -> Log.d(TAG, msg)
                else -> Log.d(TAG, msg)
            }
        } catch (e: Exception) { Log.w(TAG, "Failed to format OpenVPN log item", e) }
    }

    private fun trySyncStatusSnapshot() {
        val binder = statusBinder ?: return
        val snapshot = try {
            binder.lastStatusSnapshot
        } catch (e: RemoteException) {
            Log.w(TAG, "Failed to read status snapshot", e)
            statusBinder = null
            boundToStatus = false
            scheduleStatusRebind()
            null
        } ?: return
        updateStatusSource(StatusSource.AIDL, "AIDL snapshot")
        applyStatusSnapshot(snapshot)
    }

    private fun applyStatusSnapshot(snapshot: StatusSnapshot) {
        val level = snapshot.level ?: return
        val now = System.currentTimeMillis()
        val ts = snapshot.timestampMs
        if (ts > 0L && level in staleSnapshotTimeoutLevels) {
            val ageMs = now - ts
            if (ageMs > staleSnapshotMaxAgeMs) {
                if (now - lastLiveStatusMs <= liveStatusGraceMs) {
                    Log.w(TAG, "Skipping stale snapshot (live updates present) level=$level age=${ageMs}ms")
                    return
                }
                Log.w(TAG, "Skipping stale snapshot level=$level age=${ageMs}ms count=${staleSnapshotCount + 1}")
                staleSnapshotCount += 1
                if (staleSnapshotCount >= 3 && now - lastLiveStatusMs > staleSnapshotMaxAgeMs) {
                    forceRebindStatusService("stale snapshots age=${ageMs}ms")
                }
                return
            }
        }
        staleSnapshotCount = 0
        lastStatusSnapshotMs = if (ts > 0L) ts else now
        logEngineStateChange("AIDL", level, snapshot.state)
        syncEngineState(level, snapshot.state, allowAutoSwitch = false)
        if (level == ConnectionStatus.LEVEL_CONNECTED) {
            if (snapshot.connectedSinceMs > 0L) {
                ConnectionStateManager.syncConnectionStartTime(snapshot.connectedSinceMs)
            }
            persistLastSuccessfulConfig()
            tryRestoreTrafficSnapshot()
        }
    }

    private fun forceRebindStatusService(reason: String) {
        Log.w(TAG, "Forcing status rebind: $reason")
        statusHandler.removeCallbacks(statusRebindRunnable)
        if (boundToStatus) {
            try { statusBinder?.unregisterStatusCallback(statusCallbacks) } catch (_: Exception) {}
            try { unbindService(statusConnection) } catch (_: Exception) {}
        }
        boundToStatus = false
        statusBinder = null
        updateStatusSource(StatusSource.VPN_STATUS, "force rebind ($reason)")
        scheduleStatusRebind()
    }

    private fun logEngineLevel(level: ConnectionStatus, detail: String?) {
        val now = System.currentTimeMillis()
        val detailChanged = detail != lastEngineDetail
        val levelChanged = level != lastEngineLevel
        if (levelChanged || detailChanged || now - lastEngineLevelLogMs > 5_000L) {
            Log.i(TAG, "Engine level=${level} detail=${detail ?: "<none>"} source=${statusSource ?: StatusSource.VPN_STATUS}")
            lastEngineLevel = level
            lastEngineDetail = detail
            lastEngineLevelLogMs = now
        }
    }

    private fun syncEngineState(level: ConnectionStatus, detail: String?, allowAutoSwitch: Boolean) {
        logEngineLevel(level, detail)
        statusHandler.post { handleForegroundForLevel(level) }
        if (allowAutoSwitch) {
            try { ServerAutoSwitcher.onEngineLevel(applicationContext, level, "AIDL") } catch (_: Exception) { }
        }
        ConnectionStateManager.updateFromEngine(level, detail)
    }

    private fun persistLastSuccessfulConfig() {
        try {
            val last = SelectedCountryStore.getLastStartedConfig(applicationContext)
            val cfg = last?.config
            val country = last?.country
            val ip = last?.ip
            if (!cfg.isNullOrBlank()) {
                SelectedCountryStore.saveLastSuccessfulConfig(applicationContext, country, cfg, ip)
                try {
                    SelectedCountryStore.ensureIndexForConfig(applicationContext, cfg)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to ensure index for last successful config", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save last successful config from status", e)
        }
    }
}


