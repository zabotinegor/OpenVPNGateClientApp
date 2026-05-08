package com.yahorzabotsin.openvpnclientgate.vpn

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
import androidx.core.app.NotificationCompat
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.BuildConfig
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOption
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOptions
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2SyncCoordinator
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConfigParser.ConfigParseError
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.IServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import de.blinkt.openvpn.core.IStatusCallbacks
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.ui.main.MainSelectionInteractor
import de.blinkt.openvpn.core.TrafficHistory
import de.blinkt.openvpn.core.StatusSnapshot
import com.yahorzabotsin.openvpnclientgate.core.filter.AppFilterStore
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

class OpenVpnService : Service(), VpnStatus.StateListener, VpnStatus.LogListener, VpnStatus.ByteCountListener {

    private companion object {
        private const val ENGINE_ACTION_PAUSE_VPN = "de.blinkt.openvpn.PAUSE_VPN"
        private const val ENGINE_ACTION_RESUME_VPN = "de.blinkt.openvpn.RESUME_VPN"

        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "OpenVpnService"
        const val DEFAULT_COMPAT_MODE = 20400
        const val KEY_OVPN3 = "ovpn3"
        const val KEY_DISABLE_CONFIRMATION = "disableconfirmation"
        private val AUTO_SWITCH_LEVELS = setOf(
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_AUTH_FAILED
        )
        private val numberRegex = Regex("\\d+")
        private val ipv4Regex = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b")
        private val urlRegex = Regex("\\bhttps?://\\S+\\b")
        private val hexRegex = Regex("\\b[0-9a-fA-F]{8,}\\b")
        private const val MAX_THROTTLE_KEY_LENGTH = 96
        private const val ONE_SHOT_STOP_DELAY_MS = 1_000L
        private const val ONE_SHOT_SYNC_TIMEOUT_MS = 15_000L
        private const val CONTROLLER_NOTIFICATION_ID = 7014
        private const val PAUSE_CONFIRMATION_TIMEOUT_MS = 3_000L
        private const val RESUME_CONFIRMATION_TIMEOUT_MS = 5_000L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track engine binding for start/stop coordination
    private var engineBinder: IOpenVPNServiceInternal? = null
    private var boundToEngine = false

    // Remember whether start/stop were user-driven vs auto-switch
    private var userInitiatedStart = false
    private var userInitiatedStop = false
    private var ignoreConnectedUntilNotConnected = false

    // Suppress duplicate engine state callbacks while we manage retries
    private var suppressEngineState = true

    // Track per-session auto-switch attempts
    private var sessionTotalServers: Int = -1
    private var sessionAttempt: Int = 0

    // Byte count tracking for local listener vs AIDL callbacks
    private var lastLocalByteUpdateTs: Long = 0L
    private var aidlLastInBytes: Long = 0L
    private var aidlLastOutBytes: Long = 0L
    private var lastAidlByteUpdateTs: Long = 0L
    private var controllerForegroundActive = false

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
    
    // Track pause action to ensure PAUSED state is reached
    private var pauseActionInFlight = false
    private var pauseActionStartedMs: Long = 0L
    // Track resume action to detect engine stall and roll back to PAUSED
    private var resumeActionInFlight = false
    private var lastAidlLevel: ConnectionStatus? = null
    private var lastAidlState: String? = null
    private var lastAidlStateUpdateMs: Long = 0L
    private var lastVpnStatusLevel: ConnectionStatus? = null
    private var lastVpnStatusState: String? = null
    private var lastVpnStatusStateUpdateMs: Long = 0L
    private var lastEngineLevel: ConnectionStatus? = null
    private var lastEngineDetail: String? = null
    private var lastEngineLevelLogMs: Long = 0L
    private var oneShotSyncRequested = false
    private var oneShotSyncReceivedInitialState = false
    private val stopAfterOneShotSyncRunnable = Runnable {
        if (!oneShotSyncRequested) return@Runnable
        if (!oneShotSyncReceivedInitialState) {
            AppLog.d(TAG, "One-shot status sync pending; keep controller alive")
            return@Runnable
        }
        if (userInitiatedStart || userInitiatedStop) return@Runnable
        if (ConnectionStateManager.state.value == ConnectionState.CONNECTED) {
            AppLog.d(TAG, "One-shot sync keeping controller alive while VPN is connected")
            return@Runnable
        }
        oneShotSyncRequested = false
        oneShotSyncReceivedInitialState = false
        AppLog.d(TAG, "One-shot status sync complete; stopping controller service")
        stopSelf()
    }
    private val oneShotSyncTimeoutRunnable = Runnable {
        if (!oneShotSyncRequested || oneShotSyncReceivedInitialState) return@Runnable
        AppLog.w(TAG, "One-shot sync timeout; stopping controller with current state")
        oneShotSyncReceivedInitialState = true
        scheduleOneShotStop(0L)
    }

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
        AppLog.i(TAG, "Service created")
        ensureEngineNotificationChannels()
        ensureEnginePreferences()
        VpnStatus.addStateListener(this)
        VpnStatus.addLogListener(this)
        VpnStatus.addByteCountListener(this)
        bindStatusService()

        trafficHandler.post(trafficPollRunnable)

        runCatching {
            val v2Sync = GlobalContext.get().get<ServersV2SyncCoordinator>()
            val selectionInteractor = GlobalContext.get().get<MainSelectionInteractor>()
            ServerAutoSwitcher.v2HydrationCallback = { ctx, onDone ->
                serviceScope.launch {
                    try {
                        val hasCountry = !SelectedCountryStore.getSelectedCountry(ctx).isNullOrBlank()
                        if (hasCountry) {
                            v2Sync.syncSelectedCountryServers(ctx)
                        } else {
                            AppLog.i(TAG, "DEFAULT_V2 hydration: no selected country, bootstrapping initial selection")
                            selectionInteractor.loadInitialSelection(cacheOnly = false)
                        }
                    } catch (e: Exception) {
                        AppLog.w(TAG, "DEFAULT_V2 on-demand hydration failed", e)
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) { onDone() }
                    }
                }
            }
        }.onFailure { e ->
            AppLog.w(TAG, "Failed to wire DEFAULT_V2 hydration callback", e)
        }
    }

    private fun updateStatusSource(source: StatusSource, reason: String) {
        if (statusSource != source) {
            statusSource = source
            lastStatusSourceSwitchMs = System.currentTimeMillis()
            AppLog.i(TAG, "Status source -> ${source.name} (${reason})")
        }
    }

    private fun logEngineStateChange(
        source: String,
        level: ConnectionStatus,
        state: String?
    ) {
        val now = System.currentTimeMillis()
        val previousLevel: ConnectionStatus?
        val previousState: String?
        when (source) {
            "AIDL" -> {
                previousLevel = lastAidlLevel
                previousState = lastAidlState
                lastAidlLevel = level
                lastAidlState = state
                lastAidlStateUpdateMs = now
            }
            "VPN_STATUS" -> {
                previousLevel = lastVpnStatusLevel
                previousState = lastVpnStatusState
                lastVpnStatusLevel = level
                lastVpnStatusState = state
                lastVpnStatusStateUpdateMs = now
            }
            else -> {
                previousLevel = null
                previousState = null
            }
        }
        if (previousLevel != level || previousState != state) {
            AppLog.d(TAG, "Engine state (${source}): level=${level} state=${state ?: "<null>"}")
        }
    }

    private fun getLatestObservedEngineState(): Pair<ConnectionStatus?, String?> {
        if (isAidlFresh()) {
            return if (lastAidlStateUpdateMs > 0L || lastAidlLevel != null) {
                lastAidlLevel to lastAidlState
            } else {
                ConnectionStateManager.engineLevel.value to ConnectionStateManager.engineDetail.value
            }
        }

        return when {
            lastVpnStatusStateUpdateMs > lastAidlStateUpdateMs -> lastVpnStatusLevel to lastVpnStatusState
            lastAidlStateUpdateMs > 0L -> lastAidlLevel to lastAidlState
            else -> ConnectionStateManager.engineLevel.value to ConnectionStateManager.engineDetail.value
        }
    }

    private fun isAidlFresh(): Boolean {
        val now = System.currentTimeMillis()
        return boundToStatus && lastLiveStatusMs > 0L && (now - lastLiveStatusMs) <= aidlFreshWindowMs
    }

    private fun shouldUseVpnStatus(): Boolean = !isAidlFresh()

    private fun shouldSupplementAidlWithVpnStatus(level: ConnectionStatus): Boolean {
        if (!isAidlFresh()) return false
        if (ConnectionStateManager.state.value != ConnectionState.CONNECTING) return false
        return level == ConnectionStatus.LEVEL_START ||
            level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
            level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED ||
            level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT
    }
    private fun bindStatusService() {
        try {
            val statusIntent = Intent().apply { setClassName(applicationContext, "de.blinkt.openvpn.core.OpenVPNStatusService") }
            try {
                startService(statusIntent)
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to start status service", e)
            }
            boundToStatus = bindService(statusIntent, statusConnection, Context.BIND_AUTO_CREATE)
            AppLog.d(TAG, "Binding status service: $boundToStatus")
            if (!boundToStatus) {
                scheduleStatusRebind()
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "Failed to bind status service", t)
            scheduleStatusRebind()
        }
    }

    private val statusDeathRecipient = IBinder.DeathRecipient {
        AppLog.w(TAG, "Status binder died; scheduling rebind")
        statusBinder = null
        boundToStatus = false
        updateStatusSource(StatusSource.VPN_STATUS, "status binder died")
        scheduleStatusRebind()
    }

    private fun scheduleStatusRebind() {
        statusHandler.removeCallbacks(statusRebindRunnable)
        statusHandler.postDelayed(statusRebindRunnable, statusRebindDelayMs)
        AppLog.d(TAG, "Scheduled status rebind in ${statusRebindDelayMs}ms")
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
            AppLog.w(TAG, "Failed to set default OpenVPN preferences (ovpn3=false, disableconfirmation=true)", t)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(VpnManager.actionKey(this))) {
            VpnManager.ACTION_START -> {
                AppLog.i(TAG, "ACTION_START")
                if (!enterControllerForeground()) return START_NOT_STICKY
                oneShotSyncRequested = false
                oneShotSyncReceivedInitialState = false
                statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
                statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
                val config = intent.getStringExtra(VpnManager.extraConfigKey(this))
                val title = intent.getStringExtra(VpnManager.extraTitleKey(this))
                userInitiatedStart = true
                userInitiatedStop = false
                ignoreConnectedUntilNotConnected = false
                val isReconnect = intent.getBooleanExtra(VpnManager.extraAutoSwitchKey(this), false)
                try {
                    ConnectionStateManager.setReconnectingHint(isReconnect)
                    AppLog.d(TAG, "reconnectHint=${isReconnect} (start)")
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to set reconnecting hint on start", e)
                }
                if (isReconnect) {
                    sessionAttempt = if (sessionAttempt <= 0) 1 else sessionAttempt + 1
                } else {
                    sessionTotalServers = try { SelectedCountryStore.getServers(applicationContext).size } catch (_: Exception) { -1 }
                    sessionAttempt = 1
                }
                if (config.isNullOrBlank()) { AppLog.e(TAG, "No config to start"); stopSelf(); return START_NOT_STICKY }
                val targetIp = runCatching { SelectedCountryStore.getIpForConfig(applicationContext, config) }.getOrNull()
                    ?: runCatching { SelectedCountryStore.currentServer(applicationContext)?.ip }.getOrNull()
                try {
                    SelectedCountryStore.ensureIndexForConfig(applicationContext, config, targetIp)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to align server index with config being started", e)
                }
                run {
                    val titleStr = title?.let { ": $it" } ?: ""
                    val position = runCatching { SelectedCountryStore.getCurrentPosition(applicationContext) }.getOrNull()
                    val positionStr = position?.let { "${it.first}/${it.second}" } ?: "unknown"
                    val ipStr = targetIp ?: runCatching { SelectedCountryStore.currentServer(applicationContext)?.ip }.getOrNull()
                    AppLog.i(TAG, "Session attempt ${sessionAttempt} (serversInCountry=${totalServersStr()}, server=${positionStr}, ip=${ipStr ?: "<none>"})${titleStr}")
                }
                try {
                    SelectedCountryStore.saveLastStartedConfig(applicationContext, title, config, targetIp)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to persist last started config", e)
                }
                ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                suppressEngineState = false
                startIcsOpenVpn(config, title)
            }
            VpnManager.ACTION_STOP -> {
                AppLog.i(TAG, "ACTION_STOP")
                exitControllerForeground()
                oneShotSyncRequested = false
                oneShotSyncReceivedInitialState = false
                statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
                statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
                val preserveReconnect = intent.getBooleanExtra(VpnManager.extraPreserveReconnectKey(this), false)
                if (preserveReconnect) {
                    AppLog.d(TAG, "Preserving reconnect hint/state for retry stop")
                    userInitiatedStop = false
                    userInitiatedStart = true
                    ignoreConnectedUntilNotConnected = false
                    requestStopIcsOpenVpn()
                } else {
                    userInitiatedStop = true
                    userInitiatedStart = false
                    ignoreConnectedUntilNotConnected = true
                    try { ConnectionStateManager.setReconnectingHint(false); AppLog.d(TAG, "reconnectHint=false (user stop)") } catch (e: Exception) { AppLog.w(TAG, "Failed to clear reconnecting hint on user stop", e) }
                    try { ConnectionStateManager.updateSpeedMbps(0.0) } catch (_: Exception) {}
                    ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)
                    requestStopIcsOpenVpn()
                }
            }
            VpnManager.ACTION_STOP_IF_IDLE -> {
                AppLog.d(TAG, "ACTION_STOP_IF_IDLE")
                if (ConnectionStateManager.state.value != ConnectionState.DISCONNECTED) {
                    AppLog.d(TAG, "Ignoring stop-if-idle while VPN is active")
                    return START_NOT_STICKY
                }
                exitControllerForeground()
                stopSelf()
            }
            VpnManager.ACTION_SYNC_STATUS -> {
                AppLog.d(TAG, "ACTION_SYNC_STATUS")
                oneShotSyncRequested = true
                oneShotSyncReceivedInitialState = false
                statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
                statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
                if (!boundToStatus) bindStatusService()
                val snapshotApplied = trySyncStatusSnapshot()
                if (!snapshotApplied) {
                    statusHandler.postDelayed(oneShotSyncTimeoutRunnable, ONE_SHOT_SYNC_TIMEOUT_MS)
                }
            }
            else -> {
                val action = intent?.getStringExtra(VpnManager.actionKey(this))
                when (action) {
                    VpnManager.ACTION_PAUSE -> {
                        AppLog.i(TAG, "ACTION_PAUSE")
                        pauseActionInFlight = true
                        pauseActionStartedMs = System.currentTimeMillis()
                        statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                        statusHandler.postDelayed(pauseActionTimeoutRunnable, PAUSE_CONFIRMATION_TIMEOUT_MS)
                        try {
                            startService(Intent(this, de.blinkt.openvpn.core.OpenVPNService::class.java).apply {
                                setAction(ENGINE_ACTION_PAUSE_VPN)
                            })
                            AppLog.d(TAG, "Forwarded PAUSE_VPN to engine, waiting for PAUSED confirmation (timeout=${PAUSE_CONFIRMATION_TIMEOUT_MS}ms)")
                        } catch (e: Exception) {
                            AppLog.w(TAG, "Failed to forward PAUSE_VPN to engine", e)
                            statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                            statusHandler.post(pauseActionTimeoutRunnable)
                        }
                    }
                    VpnManager.ACTION_RESUME -> {
                        AppLog.i(TAG, "ACTION_RESUME")
                        pauseActionInFlight = false
                        statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                        resumeActionInFlight = true
                        statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                        statusHandler.postDelayed(resumeActionTimeoutRunnable, RESUME_CONFIRMATION_TIMEOUT_MS)
                        try {
                            startService(Intent(this, de.blinkt.openvpn.core.OpenVPNService::class.java).apply {
                                setAction(ENGINE_ACTION_RESUME_VPN)
                            })
                            AppLog.d(TAG, "Forwarded RESUME_VPN to engine, waiting for CONNECTED confirmation (timeout=${RESUME_CONFIRMATION_TIMEOUT_MS}ms)")
                        } catch (e: Exception) {
                            AppLog.w(TAG, "Failed to forward RESUME_VPN to engine", e)
                            resumeActionInFlight = false
                            statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                            ConnectionStateManager.cancelResumeTransition()
                            ConnectionStateManager.updateState(ConnectionState.PAUSED)
                        }
                    }
                    else -> {
                        if (!action.isNullOrBlank()) {
                            AppLog.w(TAG, "Unknown action: $action")
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun scheduleOneShotStop(delayMs: Long = ONE_SHOT_STOP_DELAY_MS) {
        if (!oneShotSyncRequested) return
        statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
        statusHandler.postDelayed(stopAfterOneShotSyncRunnable, delayMs)
    }

    private fun onOneShotInitialStateSynced(reason: String) {
        if (!oneShotSyncRequested || oneShotSyncReceivedInitialState) return
        oneShotSyncReceivedInitialState = true
        statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
        AppLog.d(TAG, "One-shot initial state synced from $reason")
        scheduleOneShotStop()
    }

    private val pauseActionTimeoutRunnable = Runnable {
        if (!pauseActionInFlight) return@Runnable
        val elapsedMs = System.currentTimeMillis() - pauseActionStartedMs
        pauseActionInFlight = false
        val (level, detail) = getLatestObservedEngineState()
        AppLog.w(TAG, "Pause action timeout after ${elapsedMs}ms: engine did not report PAUSED (lastLevel=${level ?: "<null>"})")
        try {
            when (level) {
                ConnectionStatus.LEVEL_CONNECTED -> {
                    // Restore connected state through valid transition path from PAUSING.
                    ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                    ConnectionStateManager.updateState(ConnectionState.CONNECTED)
                }
                ConnectionStatus.LEVEL_START,
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
                ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                    ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                }
                ConnectionStatus.LEVEL_VPNPAUSED -> {
                    ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_VPNPAUSED, detail)
                }
                else -> Unit
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to reconcile app state after pause timeout", e)
        }
    }

    private val resumeActionTimeoutRunnable = Runnable {
        if (!resumeActionInFlight) return@Runnable
        resumeActionInFlight = false
        val (level, detail) = getLatestObservedEngineState()
        AppLog.w(TAG, "Resume action timeout: engine did not confirm CONNECTED (lastLevel=${level ?: "<null>"})")
        try {
            ConnectionStateManager.cancelResumeTransition()
            if (level != null) {
                ConnectionStateManager.updateFromEngine(level, detail)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to reconcile app state after resume timeout", e)
        }
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
            AppLog.i(TAG, "Requested engine start (profile=${profile.mName})")
        } catch (e: ConfigParseError) {
            AppLog.e(TAG, "OVPN parse error", e); stopSelf()
        } catch (e: Exception) {
            AppLog.e(TAG, "Start error", e); stopSelf()
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
            AppLog.w(TAG, "Failed to apply app filter", t)
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
            AppLog.i(TAG, "DNS apply: option=${option.name}, override=false (use server DNS)")
            return
        }
        profile.mOverrideDNS = true
        profile.mDNS1 = config.primary ?: ""
        profile.mDNS2 = config.secondary ?: ""
        AppLog.i(TAG, "DNS apply: option=${option.name}, dns1=${profile.mDNS1}, dns2=${profile.mDNS2}")
    }

    private fun requestStopIcsOpenVpn() {
        if (!boundToEngine) {
            val engineIntent = Intent(this, de.blinkt.openvpn.core.OpenVPNService::class.java).apply {
                action = de.blinkt.openvpn.core.OpenVPNService.START_SERVICE
            }
            val bound = bindService(engineIntent, engineConnection, Context.BIND_AUTO_CREATE)
            AppLog.d(TAG, "Binding engine to stop: $bound")
            if (!bound) {
                AppLog.w(TAG, "Bind failed; launching DisconnectVPN")
                try {
                    startActivity(Intent().apply {
                        setClassName(applicationContext, "de.blinkt.openvpn.activities.DisconnectVPN")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) { AppLog.w(TAG, "Failed to start DisconnectVPN activity", e) }
                stopSelfSafely()
            }
        } else tryStopVpn()
    }

    private fun tryStopVpn() {
        val userStop = userInitiatedStop
        try {
            val stopped = engineBinder?.stopVPN(false) ?: false
            AppLog.i(TAG, "stopVPN invoked, result=$stopped")
            if (!stopped && userStop) {
                AppLog.w(TAG, "stopVPN returned false on user stop; launching DisconnectVPN")
                try {
                    startActivity(Intent().apply {
                        setClassName(applicationContext, "de.blinkt.openvpn.activities.DisconnectVPN")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (e: Exception) { AppLog.w(TAG, "Failed to start DisconnectVPN activity", e) }
            }
        } catch (e: RemoteException) {
            AppLog.e(TAG, "Binder stop error", e)
        } finally {
            if (boundToEngine) { try { unbindService(engineConnection) } catch (e: Exception) { AppLog.w(TAG, "Failed to unbind engine after stop", e) }; boundToEngine = false }
            if (userInitiatedStop) {
                ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
                userInitiatedStop = false
                stopSelf()
            }
        }
    }

    private fun stopSelfSafely() { stopSelf() }

    override fun onDestroy() {
        exitControllerForeground()
        super.onDestroy()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeLogListener(this)
        try { VpnStatus.removeByteCountListener(this) } catch (_: Exception) {}
        statusHandler.removeCallbacks(statusRebindRunnable)
        statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
        statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
        statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
        statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
        trafficHandler.removeCallbacks(trafficPollRunnable)
        lastPolledDatapoint = null
        lastPolledState = null
        if (boundToStatus) {
            try { statusBinder?.unregisterStatusCallback(statusCallbacks) } catch (_: Exception) {}
            try { unbindService(statusConnection) } catch (_: Exception) {}
            boundToStatus = false
            statusBinder = null
        }
        if (boundToEngine) { try { unbindService(engineConnection) } catch (e: Exception) { AppLog.w(TAG, "Failed to unbind engine on destroy", e) }; boundToEngine = false }
        serviceScope.cancel()
        ServerAutoSwitcher.v2HydrationCallback = null
        AppLog.d(TAG, "Service destroyed and listener removed")
    }

    private fun enterControllerForeground(): Boolean {
        if (controllerForegroundActive) return true
        try {
            val iconRes = if (applicationInfo.icon != 0) applicationInfo.icon else android.R.drawable.stat_sys_warning
            val title = runCatching { getString(R.string.vpn_notification_title_connecting) }.getOrElse { "VPN connecting" }
            val text = runCatching { getString(R.string.vpn_notification_text_connecting) }.getOrElse { "Establishing secure connection..." }
            val notification = NotificationCompat.Builder(
                this,
                de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID
            )
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(CONTROLLER_NOTIFICATION_ID, notification)
            controllerForegroundActive = true
            return true
        } catch (t: Throwable) {
            AppLog.e(TAG, "Failed to enter controller foreground; stopping service", t)
            controllerForegroundActive = false
            stopSelf()
            return false
        }
    }

    private fun exitControllerForeground() {
        if (!controllerForegroundActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        controllerForegroundActive = false
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
            if (shouldSupplementAidlWithVpnStatus(level)) {
                syncEngineState(level, state, allowAutoSwitch = false)
            }
            return
        }
        updateStatusSource(StatusSource.VPN_STATUS, "VpnStatus update")
        logEngineStateChange("VPN_STATUS", level, state)
        val failureLevelsHandledByService = setOf(
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED
        )
        if (level !in failureLevelsHandledByService) {
            AppLog.d(TAG, "Auto-switch source=VPN_STATUS (updateState)")
            try { ServerAutoSwitcher.onEngineLevel(applicationContext, level, "VPN_STATUS") } catch (e: Exception) { AppLog.w(TAG, "Failed to notify auto-switcher from updateState", e) }
        }
        if (shouldIgnoreLevelAfterUserStop(level)) return
        ConnectionStateManager.updateFromEngine(level, state)
        if (suppressEngineState) return

        if (userInitiatedStart && level in AUTO_SWITCH_LEVELS && !ConnectionStateManager.reconnectingHint.value) {
            val autoSwitchEnabled = try { com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore.load(applicationContext).autoSwitchWithinCountry } catch (_: Exception) { true }
            if (!autoSwitchEnabled) {
                AppLog.d(TAG, "Auto-switch disabled; skipping engine auto-switch path")
            } else {
                val candidates = try { SelectedCountryStore.getServers(applicationContext).size } catch (_: Exception) { -1 }
                if (candidates >= 0) AppLog.d(TAG, "Auto-switch candidates in selected country: ${candidates}")
                val next = SelectedCountryStore.nextServer(applicationContext)
                val title = SelectedCountryStore.getSelectedCountry(applicationContext)
                if (next != null) {
                val position = runCatching { SelectedCountryStore.getCurrentPosition(applicationContext) }.getOrNull()
                val positionStr = position?.let { "${it.first}/${it.second}" } ?: "unknown"
                AppLog.i(TAG, "Auto-switching to next server in country list: ${title} -> ${next.city} (server=${positionStr}, ip=${next.ip ?: "<none>"})")
                try { ConnectionStateManager.setReconnectingHint(true); AppLog.d(TAG, "reconnectHint=true (engine auto-switch)") } catch (e: Exception) { AppLog.w(TAG, "Failed to set reconnecting hint for engine auto-switch", e) }
                try { ServerAutoSwitcher.beginChainedSwitch(applicationContext, next.config, title) } catch (e: Exception) { AppLog.e(TAG, "Failed to begin chained server switch", e) }
                return
              } else {
                  userInitiatedStart = false
                  try { ConnectionStateManager.setReconnectingHint(false); AppLog.d(TAG, "reconnectHint=false (no more servers)") } catch (e: Exception) { AppLog.w(TAG, "Failed to clear reconnecting hint when no more servers", e) }
                AppLog.i(TAG, "Exhausted server list without success after ${sessionAttempt} attempts (serversInCountry=${totalServersStr()})")
              }
            }
        }
        when (level) {
              ConnectionStatus.LEVEL_CONNECTED -> {
                  userInitiatedStart = false
                  userInitiatedStop = false
                  resumeActionInFlight = false
                  statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                AppLog.i(TAG, "Connected after attempt ${sessionAttempt} (serversInCountry=${totalServersStr()})")
            }
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                if (userInitiatedStop) { userInitiatedStop = false }
                resumeActionInFlight = false
                statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
            }
            ConnectionStatus.LEVEL_VPNPAUSED -> {
                if (userInitiatedStop) { userInitiatedStop = false }
                pauseActionInFlight = false
                statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                AppLog.d(TAG, "Engine reported PAUSED, pause action complete")
            }
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                AppLog.d(TAG, "Waiting for user input")
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
                onOneShotInitialStateSynced("AIDL callback")
                if (level == ConnectionStatus.LEVEL_CONNECTED) {
                    resumeActionInFlight = false
                    statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                    persistLastSuccessfulConfig()
                    tryRestoreTrafficSnapshot()
                } else if (level == ConnectionStatus.LEVEL_VPNPAUSED) {
                    pauseActionInFlight = false
                    statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "Failed to sync state from status service: level=$level state=$state", t)
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
            AppLog.w(TAG, "Failed to get traffic history from status service", e)
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
                AppLog.w(TAG, "Status service connected with null binder; scheduling rebind")
                statusBinder = null
                boundToStatus = false
                scheduleStatusRebind()
                return
            }
            statusBinder = IServiceStatus.Stub.asInterface(service)
            boundToStatus = true
            statusRebindDelayMs = 500L
            updateStatusSource(StatusSource.AIDL, "status service connected")
            AppLog.i(TAG, "Status service connected")
            try {
                service?.linkToDeath(statusDeathRecipient, 0)
            } catch (e: RemoteException) {
                AppLog.w(TAG, "Failed to link status binder death", e)
            }
            try {
                statusBinder?.registerStatusCallback(statusCallbacks)
            } catch (e: RemoteException) {
                AppLog.e(TAG, "Failed to register status callback", e)
            }
            trySyncStatusSnapshot()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            statusBinder = null
            boundToStatus = false
            updateStatusSource(StatusSource.VPN_STATUS, "status service disconnected")
            AppLog.w(TAG, "Status service disconnected")
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
                    AppLog.w(TAG, "Error in trafficPollRunnable", e)
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
            val sanitized = redactMessage(msg)
            when (logItem.logLevel) {
                VpnStatus.LogLevel.ERROR -> AppLog.e(TAG, sanitized)
                VpnStatus.LogLevel.WARNING -> AppLog.w(TAG, sanitized)
                VpnStatus.LogLevel.INFO -> AppLog.iThrottled(TAG, sanitized, key = buildLogThrottleKey("ovpn-info", sanitized))
                VpnStatus.LogLevel.VERBOSE -> AppLog.dThrottled(TAG, sanitized, key = buildLogThrottleKey("ovpn-verbose", sanitized))
                else -> AppLog.dThrottled(TAG, sanitized, key = buildLogThrottleKey("ovpn-default", sanitized))
            }
        } catch (e: Exception) { AppLog.w(TAG, "Failed to format OpenVPN log item", e) }
    }

    private fun redactMessage(message: String): String {
        return hexRegex.replace(
            ipv4Regex.replace(
                urlRegex.replace(message, "<url>"),
                "<ip>"
            ),
            "<hex>"
        )
    }

    private fun buildLogThrottleKey(prefix: String, message: String): String {
        val normalized = numberRegex.replace(
            hexRegex.replace(
                ipv4Regex.replace(message.lowercase(), "<ip>"),
                "<hex>"
            ),
            "#"
        )
            .replace(Regex("\\s+"), " ")
            .trim()
        val suffix = if (normalized.length > MAX_THROTTLE_KEY_LENGTH) {
            normalized.take(MAX_THROTTLE_KEY_LENGTH)
        } else {
            normalized
        }
        return "$prefix:$suffix"
    }

    private fun trySyncStatusSnapshot(): Boolean {
        val binder = statusBinder ?: return false
        val snapshot = try {
            binder.lastStatusSnapshot
        } catch (e: RemoteException) {
            AppLog.w(TAG, "Failed to read status snapshot", e)
            statusBinder = null
            boundToStatus = false
            scheduleStatusRebind()
            null
        } ?: return false
        updateStatusSource(StatusSource.AIDL, "AIDL snapshot")
        applyStatusSnapshot(snapshot)
        return true
    }

    private fun applyStatusSnapshot(snapshot: StatusSnapshot) {
        val level = snapshot.level ?: return
        val now = System.currentTimeMillis()
        val ts = snapshot.timestampMs
        if (ts > 0L && level in staleSnapshotTimeoutLevels) {
            val ageMs = now - ts
            if (ageMs > staleSnapshotMaxAgeMs) {
                if (now - lastLiveStatusMs <= liveStatusGraceMs) {
                    AppLog.w(TAG, "Skipping stale snapshot (live updates present) level=$level age=${ageMs}ms")
                    return
                }
                AppLog.w(TAG, "Skipping stale snapshot level=$level age=${ageMs}ms count=${staleSnapshotCount + 1}")
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
        onOneShotInitialStateSynced("AIDL snapshot")
        if (level == ConnectionStatus.LEVEL_CONNECTED) {
            if (snapshot.connectedSinceMs > 0L) {
                ConnectionStateManager.syncConnectionStartTime(snapshot.connectedSinceMs)
            }
            persistLastSuccessfulConfig()
            tryRestoreTrafficSnapshot()
        }
    }

    private fun forceRebindStatusService(reason: String) {
        AppLog.w(TAG, "Forcing status rebind: $reason")
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
            AppLog.i(TAG, "Engine level=${level} detail=${detail ?: "<none>"} source=${statusSource ?: StatusSource.VPN_STATUS}")
            lastEngineLevel = level
            lastEngineDetail = detail
            lastEngineLevelLogMs = now
        }
    }

    private fun syncEngineState(level: ConnectionStatus, detail: String?, allowAutoSwitch: Boolean) {
        logEngineLevel(level, detail)
        if (controllerForegroundActive && level != ConnectionStatus.LEVEL_START && level != ConnectionStatus.UNKNOWN_LEVEL) {
            exitControllerForeground()
        }
        if (shouldIgnoreLevelAfterUserStop(level)) return
        if (allowAutoSwitch) {
            try {
                ServerAutoSwitcher.onEngineLevel(applicationContext, level, "AIDL")
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to notify auto-switcher from AIDL", e)
            }
        }
        ConnectionStateManager.updateFromEngine(level, detail)
    }

    private fun shouldIgnoreLevelAfterUserStop(level: ConnectionStatus): Boolean {
        if (!ignoreConnectedUntilNotConnected) return false
        return when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                AppLog.d(TAG, "Ignoring stale LEVEL_CONNECTED after user stop")
                true
            }
            ConnectionStatus.LEVEL_VPNPAUSED -> {
                ignoreConnectedUntilNotConnected = false
                AppLog.d(TAG, "Cleared stale CONNECTED guard on level=$level and ignored stale paused callback")
                true
            }
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.UNKNOWN_LEVEL -> {
                ignoreConnectedUntilNotConnected = false
                AppLog.d(TAG, "Cleared stale CONNECTED guard on level=$level")
                false
            }
            else -> false
        }
    }

    private fun persistLastSuccessfulConfig() {
        try {
            val last = SelectedCountryStore.getLastStartedConfig(applicationContext)
            val cfg = last?.config
            val country = last?.country
            val ip = last?.ip
            if (!cfg.isNullOrBlank()) {
                SelectedCountryStore.saveLastSuccessfulConfig(
                    ctx = applicationContext,
                    country = country,
                    config = cfg,
                    ip = ip,
                    alignIndex = false
                )
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to save last successful config from status", e)
        }
    }
}
