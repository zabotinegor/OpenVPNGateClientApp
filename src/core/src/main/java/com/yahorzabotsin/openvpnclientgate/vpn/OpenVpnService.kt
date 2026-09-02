package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.Handler
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.yahorzabotsin.openvpnclientgate.core.ApiConstants
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
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
import com.yahorzabotsin.openvpnclientgate.core.servers.probe.ProbeRequestQueue
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class OpenVpnService : Service(), VpnStatus.StateListener, VpnStatus.LogListener, VpnStatus.ByteCountListener {

    internal companion object {
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
        private val STOP_TERMINAL_LEVELS = setOf(
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.UNKNOWN_LEVEL
        )
        private val numberRegex = Regex("\\d+")
        private val ipv4Regex = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b")
        private val urlRegex = Regex("\\bhttps?://\\S+\\b")
        private val hexRegex = Regex("\\b[0-9a-fA-F]{8,}\\b")
        private const val MAX_THROTTLE_KEY_LENGTH = 96
        private const val ONE_SHOT_STOP_DELAY_MS = 1_000L
        // Re-checks guards before firing stopSelf() after one-shot status sync.
        private const val ONE_SHOT_STOP_CONFIRM_DELAY_MS = 400L
        private const val ENGINE_RECONNECT_DISPATCH_BUFFER_MS = 500L
        private const val ONE_SHOT_SYNC_TIMEOUT_MS = 15_000L
        private const val CONTROLLER_NOTIFICATION_ID = 7014
        private const val PAUSE_CONFIRMATION_TIMEOUT_MS = 3_000L
        private const val RESUME_CONFIRMATION_TIMEOUT_MS = 5_000L
        private const val STOP_DISPATCH_MAX_ATTEMPTS = 3
        private const val STOP_DISPATCH_RETRY_DELAY_MS = 1_000L
        private const val STOP_CONFIRMATION_TIMEOUT_MS = 8_000L
        private const val STOP_BIND_TIMEOUT_MS = 2_000L
        private const val STOP_PREFS_NAME = "vpn_stop_teardown"
        private const val PREF_PENDING_STOP_INTENT = "pending_stop_intent"
        private const val PREF_STOP_FAILURE_COUNT = "stop_failure_count"
        private const val PREF_STOP_STALE_RECONCILE_COUNT = "stop_stale_reconcile_count"
        private const val WATCHDOG_POLL_INTERVAL_MS = 2_000L
        private const val WATCHDOG_MIN_TRAFFIC_DELTA_BYTES = 256L
        private const val WATCHDOG_PROBE_TIMEOUT_MS = 2_000
        private const val WATCHDOG_FAILURE_THRESHOLD = 3
        private const val WATCHDOG_RECOVERY_COOLDOWN_MS = 15_000L
        private const val WATCHDOG_CONNECTED_WARMUP_MS = 10_000L
        private const val WATCHDOG_MAX_RECOVERY_ATTEMPTS = 3
        private const val WATCHDOG_FALLBACK_HTTPS_PORT = 443
        private const val WATCHDOG_DEFAULT_OPENVPN_PORT = 1194

        @Volatile
        internal var isInstanceAlive: Boolean = false
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track engine binding for start/stop coordination
    private var engineBinder: IOpenVPNServiceInternal? = null
    private var boundToEngine = false

    // Remember whether start/stop were user-driven vs auto-switch.
    // @Volatile: written on the main thread, read on the AIDL binder thread (updateStateString ->
    // syncEngineState / shouldIgnoreLevelAfterUserStop / handleEngineLevelForStop).
    @Volatile private var userInitiatedStart = false
    @Volatile private var userInitiatedStop = false
    @Volatile private var serviceDestroyed = false
    @Volatile private var ignoreConnectedUntilNotConnected = false
    // Same cross-thread visibility requirement as above: stopRequestId/stopStartedAtMs are
    // written on the main thread (startUserStopTeardown) and read on the AIDL binder thread
    // (syncEngineState via maybeStartStaleStopReconciliation).
    @Volatile private var stopRequestId: String? = null
    @Volatile private var stopStartedAtMs: Long = 0L
    private var stopAttempt: Int = 0
    private var stopAwaitingConfirmation: Boolean = false
    private var stopBindPending: Boolean = false
    private var stopLastFailureReason: String? = null
    private val stopPrefs: SharedPreferences by lazy {
        getSharedPreferences(STOP_PREFS_NAME, MODE_PRIVATE)
    }

    // Suppress duplicate engine state callbacks while we manage retries
    private var suppressEngineState = true

    // Track per-session auto-switch attempts
    private var sessionTotalServers: Int = -1
    private var sessionAttempt: Int = 0
    private var currentAttemptStartMs: Long = 0L
    private var currentAttemptStartElapsedRealtimeMs: Long = 0L
    // See ReconnectDispatchGuard's class-level KDoc for the state machine this replaces (six
    // interacting fields layered on over a long series of fixes), the LEVEL_VPNPAUSED decoupling
    // exception, and why the enqueue-point window class is closed.
    private val reconnectDispatchGuard = ReconnectDispatchGuard()

    // Byte count tracking for local listener vs AIDL callbacks
    private var lastLocalByteUpdateTs: Long = 0L
    // Written and read on the AIDL binder thread only (updateByteCount(inBytes, outBytes)),
    // but Android's binder thread pool may service successive calls on different worker
    // threads, so @Volatile is required for cross-call memory visibility.
    @Volatile private var aidlLastInBytes: Long = 0L
    @Volatile private var aidlLastOutBytes: Long = 0L
    @Volatile private var lastAidlByteUpdateTs: Long = 0L
    @Volatile private var controllerForegroundActive = false

    // Binding to status service for engine logs/metrics
    @Volatile private var statusBinder: IServiceStatus? = null
    @Volatile private var boundToStatus = false
    private var statusRebindDelayMs = 500L
    @Volatile private var lastStatusSnapshotMs: Long = 0L
    @Volatile private var lastLiveStatusMs: Long = 0L
    @Volatile private var lastLiveStatusElapsedRealtimeMs: Long = 0L
    // Written to 0 on the AIDL binder thread (the statusCallbacks.updateStateString callback)
    // and read-increment-written on the main thread inside the snapshot-poll path below
    // (trySyncStatusSnapshot).
    // @Volatile alone only guarantees cross-thread VISIBILITY of the latest write -- it does not
    // make `staleSnapshotCount += 1` atomic. That is a genuine read-modify-write: the binder
    // thread's reset to 0 can land between the main thread's read of the old count and its write
    // of the incremented value, silently losing the reset. A retained pre-reset count can then
    // reach the 3-snapshot threshold before a live status callback should have re-armed it (the
    // grace window this counter exists to gate). AtomicInteger's set()/incrementAndGet() make both
    // operations atomic with respect to each other, closing that window. Copilot PR #140 review
    // (thread PRRT_kwDOONeEXM6ef1im).
    private val staleSnapshotCount = AtomicInteger(0)
    private enum class StatusSource { AIDL, VPN_STATUS }
    private var statusSource: StatusSource? = null
    private var lastStatusSourceSwitchMs: Long = 0L
    private val aidlFreshWindowMs = 3_000L
    private val staleSnapshotMaxAgeMs = 10_000L
    // Ceiling for the ACTION_SYNC_STATUS reattach path only (unknown attempt baseline + an active,
    // non-terminal level -- the snapshot that backfills currentAttemptStartMs in
    // applyStatusSnapshot()). Round 15 established that such a snapshot must be trusted even when
    // it is older than staleSnapshotMaxAgeMs, because a genuinely stuck current attempt is
    // ServerAutoSwitcher's only signal on this lifecycle path. That trust still has to be BOUNDED:
    // the backfill adopts the snapshot's own timestamp as the baseline, so the predates-check can
    // never reject it, and without a ceiling a leftover snapshot of ANY age -- hours or days old,
    // from a long-dead past session -- would be accepted and forwarded with auto-switch enabled,
    // able to start a chained VPN retry merely from opening the app.
    //
    // Sized well above any plausible "current attempt": ServerAutoSwitcher's own switch thresholds
    // are 5-8s, and an engine that is genuinely working a connection keeps pushing state changes
    // seconds apart, so a cached snapshot untouched for two minutes is not a live attempt.
    private val reattachSnapshotMaxAgeMs = 120_000L
    private val clockJumpMinDetectableMs = 1_000L
    private val clockJumpSlackMs = 2_000L
    private val liveStatusGraceMs = 5_000L
    private val statusHandler = Handler(Looper.getMainLooper())
    private val trafficHandler = Handler(Looper.getMainLooper())
    private var lastPolledDatapoint: TrafficHistory.TrafficDatapoint? = null
    private var lastPolledState: ConnectionState? = null
    private data class HealthWatchdogState(
        var connectedSinceMs: Long = 0L,
        var consecutiveFailures: Int = 0,
        var lastHealthyTimestamp: Long = 0L,
        var lastRecoveryTimestamp: Long = 0L,
        var recoveryAttempts: Int = 0,
        var degraded: Boolean = false
    )
    private data class WatchdogProbeTarget(
        val host: String,
        val port: Int
    )
    private data class WatchdogRecoveryTarget(
        val config: String,
        val title: String?
    )
    private var watchdogState = HealthWatchdogState()

    /**
     * True from the moment the watchdog dispatches a recovery until traffic actually flows again,
     * the fail-safe fires, or the user starts a connection themselves.
     *
     * Recovery reconnects, so it causes the very connection-state transition that
     * [resetHealthWatchdog] zeroes. Without this flag [HealthWatchdogState.recoveryAttempts] would
     * restart at 0 after every attempt, WATCHDOG_MAX_RECOVERY_ATTEMPTS would never be reached, and
     * a server that connects cleanly but carries no traffic would be retried forever.
     */
    private var watchdogRecoveryInFlight = false
    internal var watchdogNowMs: () -> Long = { System.currentTimeMillis() }
    // Monotonic counterpart to watchdogNowMs, used only to pair with
    // currentAttemptStartElapsedRealtimeMs for the backward-wall-clock-jump safety net in
    // applyStatusSnapshot(). Injectable for the same reason watchdogNowMs is: deterministic unit
    // tests can simulate a clock jump without depending on real device uptime.
    internal var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }
    internal var watchdogProbeDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var watchdogProbe: (String, Int, Int) -> Boolean = { host, port, timeoutMs ->
        performReachabilityProbe(host, port, timeoutMs)
    }
    /**
     * Dispatches a recovery. Returns false when nothing was actually dispatched, so the caller can
     * fail safe instead of consuming budget on an attempt that never happened.
     * [ServerAutoSwitcher.beginChainedSwitch] reports false for every such case: auto-switch off,
     * a rejected stop command, or an exception while requesting the stop.
     */
    internal var watchdogRecoveryStarter: (Context, String, String?) -> Boolean = { ctx, config, title ->
        ServerAutoSwitcher.beginChainedSwitch(ctx, config, title)
    }
    private var watchdogProbeJob: Job? = null
    
    @Volatile private var probeQueue: ProbeRequestQueue? = null

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

    // Gates one-shot-sync stopSelf() on UI not being visible (prevents race with Connect tap).
    internal var appForegroundVisibleProvider: () -> Boolean = {
        try {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        } catch (t: Throwable) {
            // Fail closed: assume an activity could be visible and suppress the stop rather than
            // risk racing a Connect tap.
            AppLog.w(TAG, "Unable to read process lifecycle state; treating app as foreground", t)
            true
        }
    }

    // Assumes this Service runs in the app's main process (no android:process, unlike the
    // engine's :openvpn). If that ever changes, ProcessLifecycleOwner would never observe an
    // activity here and this would silently return false forever, making the suppression above a
    // permanent no-op.
    private fun isAppForegroundVisible(): Boolean = appForegroundVisibleProvider()

    // Reaps idle controller once UI leaves foreground via ACTION_STOP_IF_IDLE.
    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            AppLog.i(TAG, "App UI left foreground; reaping idle controller via ACTION_STOP_IF_IDLE")
            VpnManager.stopControllerIfIdle(this@OpenVpnService)
        }
    }

    private fun registerAppLifecycleObserver() {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        }.onFailure { AppLog.w(TAG, "Failed to register app lifecycle observer", it) }
    }

    private fun unregisterAppLifecycleObserver() {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
        }.onFailure { AppLog.w(TAG, "Failed to unregister app lifecycle observer", it) }
    }

    private val stopAfterOneShotSyncRunnable = Runnable {
        if (!oneShotSyncRequested) return@Runnable
        if (!oneShotSyncReceivedInitialState) {
            AppLog.d(TAG, "One-shot status sync pending; keep controller alive")
            return@Runnable
        }
        if (userInitiatedStart || userInitiatedStop) return@Runnable
        if (ConnectionStateManager.state.value != ConnectionState.DISCONNECTED) {
            AppLog.d(TAG, "One-shot sync keeping controller alive while VPN is not idle")
            return@Runnable
        }
        // Do not call stopSelf() here. Defer it by ONE_SHOT_STOP_CONFIRM_DELAY_MS and re-run the
        // same guard checks in stopAfterOneShotSyncConfirmedRunnable immediately before the
        // deferred stopSelf() actually fires -- see that constant's declaration comment.
        AppLog.d(TAG, "One-shot status sync decided to stop; confirming after ${ONE_SHOT_STOP_CONFIRM_DELAY_MS}ms buffer")
        statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
        statusHandler.postDelayed(stopAfterOneShotSyncConfirmedRunnable, ONE_SHOT_STOP_CONFIRM_DELAY_MS)
    }

    // Second stage of one-shot-sync stop decision -- re-runs guards before actual stopSelf().
    private val stopAfterOneShotSyncConfirmedRunnable = Runnable {
        if (!oneShotSyncRequested) return@Runnable
        if (!oneShotSyncReceivedInitialState) return@Runnable
        if (userInitiatedStart || userInitiatedStop) {
            AppLog.i(TAG, "One-shot sync stop aborted by buffered re-check (userInitiatedStart/Stop set)")
            return@Runnable
        }
        // userInitiatedStart may not yet reflect a dispatch still in AMS/Binder transit.
        if (VpnManager.hasRecentActionStartDispatch()) {
            AppLog.i(TAG, "One-shot sync stop aborted: recent ACTION_START dispatch still in flight")
            return@Runnable
        }
        if (ConnectionStateManager.state.value != ConnectionState.DISCONNECTED) {
            // Excludes ServerAutoSwitcher's background retry-timer dispatcher: reconnectingHint
            // holds state at CONNECTING for the whole auto-switch stop-to-start gap.
            AppLog.d(TAG, "One-shot sync keeping controller alive while VPN is not idle (buffered re-check)")
            return@Runnable
        }
        if (isAppForegroundVisible()) {
            AppLog.i(TAG, "One-shot sync stop suppressed while app UI is foreground; deferring to UI-gone-away teardown")
            return@Runnable
        }
        oneShotSyncRequested = false
        oneShotSyncReceivedInitialState = false
        AppLog.i(TAG, "One-shot status sync complete; stopping controller service")
        stopSelf()
    }
    private val oneShotSyncTimeoutRunnable = Runnable {
        if (!oneShotSyncRequested || oneShotSyncReceivedInitialState) return@Runnable
        AppLog.w(TAG, "One-shot sync timeout; stopping controller with current state")
        oneShotSyncReceivedInitialState = true
        scheduleOneShotStop(0L)
    }

    private val stopRetryRunnable = Runnable {
        if (!userInitiatedStop) return@Runnable
        AppLog.w(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} retry=true reason=${stopLastFailureReason ?: "unknown"} next_attempt=${stopAttempt + 1}")
        stopAwaitingConfirmation = false
        stopBindPending = false
        requestStopIcsOpenVpn()
    }

    private val stopConfirmationTimeoutRunnable = Runnable {
        if (!userInitiatedStop || !stopAwaitingConfirmation) return@Runnable
        stopAwaitingConfirmation = false
        stopLastFailureReason = "confirmation_timeout"
        AppLog.w(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} attempt=$stopAttempt dispatch=sent confirm=false reason=confirmation_timeout")
        scheduleStopRetryOrFail("confirmation_timeout")
    }

    private val stopBindTimeoutRunnable = Runnable {
        if (!userInitiatedStop || !stopBindPending) return@Runnable
        stopBindPending = false
        stopLastFailureReason = "bind_timeout"
        stopAttempt += 1
        AppLog.w(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} attempt=$stopAttempt dispatch=not_sent reason=bind_timeout")
        scheduleStopRetryOrFail("bind_timeout")
    }

    private fun newStopRequestId(): String = UUID.randomUUID().toString().substring(0, 8)

    private fun hasPendingStopIntent(): Boolean = stopPrefs.getBoolean(PREF_PENDING_STOP_INTENT, false)

    private fun persistPendingStopIntent(pending: Boolean) {
        stopPrefs.edit().putBoolean(PREF_PENDING_STOP_INTENT, pending).commit()
    }

    private fun incrementStopFailureCounter(): Int {
        val next = stopPrefs.getInt(PREF_STOP_FAILURE_COUNT, 0) + 1
        stopPrefs.edit().putInt(PREF_STOP_FAILURE_COUNT, next).apply()
        return next
    }

    private fun incrementStaleReconcileCounter(): Int {
        val next = stopPrefs.getInt(PREF_STOP_STALE_RECONCILE_COUNT, 0) + 1
        stopPrefs.edit().putInt(PREF_STOP_STALE_RECONCILE_COUNT, next).apply()
        return next
    }

    private fun startUserStopTeardown(reason: String, forceReset: Boolean = false) {
        if (!userInitiatedStop || forceReset) {
            userInitiatedStop = true
            userInitiatedStart = false
            ignoreConnectedUntilNotConnected = true
            stopRequestId = newStopRequestId()
            stopStartedAtMs = System.currentTimeMillis()
            stopAttempt = 0
            stopAwaitingConfirmation = false
            stopBindPending = false
            stopLastFailureReason = null
            persistPendingStopIntent(true)
            ConnectionStateManager.clearStopFailure()
            AppLog.i(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} session=${sessionAttempt} source=$reason started=true")
        }
        try {
            ConnectionStateManager.setReconnectingHint(false)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to clear reconnecting hint on user stop", e)
        }
        try {
            ConnectionStateManager.updateSpeedMbps(0.0)
        } catch (_: Exception) {
        }
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)
        pauseActionInFlight = false
        resumeActionInFlight = false
        statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
        statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
        // Cancels only queued-but-not-yet-run dispatches to ServerAutoSwitcher (see
        // autoSwitchDispatchToken). Does nothing to a switch timer already running before this
        // teardown began -- that lives on ServerAutoSwitcher's own Handler and is cancelled
        // explicitly below instead.
        statusHandler.removeCallbacksAndMessages(autoSwitchDispatchToken)
        // Sweeps any reconnect engine-dispatch still queued from a prior auto-switch retry --
        // see reconnectEngineDispatchToken's declaration comment.
        statusHandler.removeCallbacksAndMessages(reconnectEngineDispatchToken)
        // This function can be reached synchronously from the AIDL binder thread (via
        // maybeStartStaleStopReconciliation), so the cancellation must be dispatched onto the main
        // thread -- ServerAutoSwitcher's internal timer state assumes a single main-looper caller.
        // Posted untagged, not with autoSwitchDispatchToken: that token is for forward-looking
        // auto-switch reaction dispatches, and tagging this cancellation the same way would risk a
        // later sweep (e.g. from onDestroy()) wiping it out before it runs.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ServerAutoSwitcher.cancelForUserStop()
        } else {
            statusHandler.post { ServerAutoSwitcher.cancelForUserStop() }
        }
        requestStopIcsOpenVpn()
    }

    private fun scheduleStopRetryOrFail(reason: String) {
        if (!userInitiatedStop) return
        if (stopAttempt >= STOP_DISPATCH_MAX_ATTEMPTS) {
            markStopFailure(reason)
            return
        }
        statusHandler.removeCallbacks(stopRetryRunnable)
        statusHandler.postDelayed(stopRetryRunnable, STOP_DISPATCH_RETRY_DELAY_MS)
    }

    private fun markStopFailure(reason: String) {
        stopAwaitingConfirmation = false
        stopBindPending = false
        stopLastFailureReason = reason
        statusHandler.removeCallbacks(stopRetryRunnable)
        statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
        statusHandler.removeCallbacks(stopBindTimeoutRunnable)
        ConnectionStateManager.setStopFailure()
        val elapsedMs = if (stopStartedAtMs > 0L) System.currentTimeMillis() - stopStartedAtMs else -1L
        val failureCount = incrementStopFailureCounter()
        AppLog.e(
            TAG,
            "stop_flow requestId=${stopRequestId ?: "<none>"} attempts=$stopAttempt dispatch=failed confirm=false elapsed_ms=$elapsedMs reason=$reason failure_count=$failureCount"
        )
    }

    private fun finishStopFlowConfirmed(level: ConnectionStatus, source: String) {
        if (!userInitiatedStop) return
        stopAwaitingConfirmation = false
        stopBindPending = false
        statusHandler.removeCallbacks(stopRetryRunnable)
        statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
        statusHandler.removeCallbacks(stopBindTimeoutRunnable)
        ignoreConnectedUntilNotConnected = false
        userInitiatedStop = false
        reconnectDispatchGuard.beginNewAttempt()
        ConnectionStateManager.clearStopFailure()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        val serverId = if (level != ConnectionStatus.LEVEL_NONETWORK) {
            SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)
        } else 0
        if (serverId != 0) {
            try { probeQueue?.enqueue(serverId) } catch (e: Exception) {
                AppLog.w(TAG, "Failed to enqueue hardprobe on user disconnect", e)
            }
        }
        persistPendingStopIntent(false)
        val elapsedMs = if (stopStartedAtMs > 0L) System.currentTimeMillis() - stopStartedAtMs else -1L
        AppLog.i(
            TAG,
            "stop_flow requestId=${stopRequestId ?: "<none>"} attempts=$stopAttempt dispatch=sent confirm=true level=$level source=$source elapsed_ms=$elapsedMs"
        )
        stopRequestId = null
        stopStartedAtMs = 0L
        stopAttempt = 0
        stopLastFailureReason = null
        if (boundToEngine) {
            try {
                unbindService(engineConnection)
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to unbind engine after confirmed stop", e)
            }
            boundToEngine = false
        }
        if (VpnManager.hasRecentActionStartDispatch()) {
            AppLog.i(TAG, "Confirmed-stop stopSelf() aborted: recent ACTION_START dispatch still in flight")
            return
        }
        stopSelf()
    }

    private fun maybeStartStaleStopReconciliation(level: ConnectionStatus, source: String): Boolean {
        if (level != ConnectionStatus.LEVEL_CONNECTED) return false
        if (!hasPendingStopIntent()) return false
        if (userInitiatedStart) return false
        if (userInitiatedStop) return false

        val reconcileCount = incrementStaleReconcileCounter()
        AppLog.w(
            TAG,
            "stale_stop_guard source=$source pending_stop_intent=true observed_level=$level reconcile_count=$reconcileCount"
        )
        startUserStopTeardown("stale_relaunch")
        return true
    }

    private fun maybeClearStaleStopIntentOnIdleLevel(level: ConnectionStatus, source: String) {
        if (level !in STOP_TERMINAL_LEVELS) return
        if (!hasPendingStopIntent()) return
        if (userInitiatedStop || userInitiatedStart) return

        persistPendingStopIntent(false)
        ConnectionStateManager.clearStopFailure()
        AppLog.i(
            TAG,
            "stop_flow pending intent cleared on idle engine level=$level source=$source pending_stop_intent=false"
        )
    }

    private fun totalServersStr(): String =
        if (sessionTotalServers >= 0) sessionTotalServers.toString() else "unknown"
    

    private val engineConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            engineBinder = IOpenVPNServiceInternal.Stub.asInterface(service)
            boundToEngine = true
            stopBindPending = false
            statusHandler.removeCallbacks(stopBindTimeoutRunnable)
            tryStopVpn()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            engineBinder = null
            boundToEngine = false
        }
    }

    override fun onCreate() {
        isInstanceAlive = true
        super.onCreate()
        AppLog.i(TAG, "Service created")
        ensureEngineNotificationChannels()
        ensureEnginePreferences()
        enterControllerForeground()
        registerAppLifecycleObserver()
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

        runCatching {
            val queue = GlobalContext.get().get<ProbeRequestQueue>()
            probeQueue = queue
            ServerAutoSwitcher.probeRequestQueue = queue
        }.onFailure { e ->
            AppLog.w(TAG, "Failed to wire ProbeRequestQueue", e)
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
        return boundToStatus && lastLiveStatusMs > 0L && lastLiveStatusElapsedRealtimeMs > 0L &&
            (elapsedRealtimeMs() - lastLiveStatusElapsedRealtimeMs) <= aidlFreshWindowMs
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
                stopAwaitingConfirmation = false
                stopBindPending = false
                stopLastFailureReason = null
                stopRequestId = null
                userInitiatedStop = false
                ignoreConnectedUntilNotConnected = false
                statusHandler.removeCallbacks(stopRetryRunnable)
                statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
                statusHandler.removeCallbacks(stopBindTimeoutRunnable)
                ConnectionStateManager.clearStopFailure()
                if (hasPendingStopIntent()) {
                    persistPendingStopIntent(false)
                    AppLog.i(TAG, "stop_flow pending intent cleared on fresh ACTION_START pending_stop_intent=false")
                }
                if (!enterControllerForeground()) return START_NOT_STICKY
                oneShotSyncRequested = false
                oneShotSyncReceivedInitialState = false
                statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
                statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
                statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
                pauseActionInFlight = false
                resumeActionInFlight = false
                statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                val config = intent.getStringExtra(VpnManager.extraConfigKey(this))
                val title = intent.getStringExtra(VpnManager.extraTitleKey(this))
                userInitiatedStart = true
                VpnManager.clearRecentActionStartDispatch()
                if (ConnectionStateManager.state.value == ConnectionState.DISCONNECTING) {
                    ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
                }
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
                    watchdogRecoveryInFlight = false
                    watchdogState.recoveryAttempts = 0
                }
                currentAttemptStartMs = watchdogNowMs()
                currentAttemptStartElapsedRealtimeMs = elapsedRealtimeMs()
                val attemptGeneration = reconnectDispatchGuard.beginNewAttempt()
                if (config.isNullOrBlank()) { AppLog.e(TAG, "No config to start"); stopSelf(); return START_NOT_STICKY }
                if (isReconnect) {
                    reconnectDispatchGuard.armPending(attemptGeneration)
                }
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
                if (isReconnect) {
                    val dispatchGeneration = attemptGeneration
                    // Accepted residual risk, pre-existing and not widened by the extraction of
                    // ReconnectDispatchGuard: this check-then-act pair (userInitiatedStop read here, then
                    // startIcsOpenVpn() below) races startUserStopTeardown() when the latter is
                    // reached synchronously from the AIDL binder thread via
                    // maybeStartStaleStopReconciliation()'s stale_relaunch path. If this Runnable
                    // has already passed this check on the main thread when the binder-thread
                    // teardown begins, statusHandler.removeCallbacksAndMessages(reconnectEngineDispatchToken)
                    // in startUserStopTeardown() cannot recall a Runnable already executing, so
                    // startIcsOpenVpn() below can still run. The generation guard does not help
                    // here either, because startUserStopTeardown() deliberately does not call
                    // reconnectDispatchGuard.beginNewAttempt() (a user-initiated stop must not
                    // itself count as a superseding attempt). Not fixed here: the window is
                    // sub-millisecond, requires the specific stale_relaunch reconciliation path (not
                    // a plain user Disconnect, which arrives on main), and is bounded/self-healing --
                    // startUserStopTeardown() unconditionally calls requestStopIcsOpenVpn()
                    // afterwards, so an engine started in the window is stopped by the same
                    // teardown, and finishStopFlowConfirmed()'s stopSelf() is independently gated by
                    // VpnManager.hasRecentActionStartDispatch(). A real fix would need to make
                    // startUserStopTeardown() and this Runnable agree through a single synchronized
                    // check-and-clear rather than two independent reads, which risks destabilizing a
                    // subsystem that has already taken 22 fix cycles to reach its current verified
                    // state; left as a documented risk instead. See docs/guides/troubleshooting.md
                    // for this subsystem's full defect history.
                    statusHandler.postAtTime(Runnable {
                        fun clearMarkerIfOwn() {
                            reconnectDispatchGuard.clearPendingIfOwnedBy(dispatchGeneration)
                        }
                        if (userInitiatedStop || serviceDestroyed) {
                            clearMarkerIfOwn()
                            AppLog.i(TAG, "Reconnect engine-dispatch buffer elapsed but stop/destroy landed first; skipping start")
                            return@Runnable
                        }
                        if (reconnectDispatchGuard.currentGeneration != dispatchGeneration) {
                            clearMarkerIfOwn()
                            AppLog.i(TAG, "Reconnect engine-dispatch buffer elapsed but a newer attempt has begun; skipping start")
                            return@Runnable
                        }
                        startIcsOpenVpn(config, title)
                        clearMarkerIfOwn()
                    }, reconnectEngineDispatchToken, SystemClock.uptimeMillis() + ENGINE_RECONNECT_DISPATCH_BUFFER_MS)
                } else {
                    startIcsOpenVpn(config, title)
                }
            }
            VpnManager.ACTION_STOP -> {
                AppLog.i(TAG, "ACTION_STOP")
                exitControllerForeground()
                oneShotSyncRequested = false
                oneShotSyncReceivedInitialState = false
                statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
                statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
                statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
                pauseActionInFlight = false
                resumeActionInFlight = false
                statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                val preserveReconnect = intent.getBooleanExtra(VpnManager.extraPreserveReconnectKey(this), false)
                if (preserveReconnect) {
                    AppLog.d(TAG, "Preserving reconnect hint/state for retry stop")
                    userInitiatedStop = false
                    userInitiatedStart = true
                    ignoreConnectedUntilNotConnected = false
                    reconnectDispatchGuard.beginNewAttempt()
                    // Sweep the reconnect engine-dispatch token too (defence in depth alongside the
                    // generation bump above) -- see reconnectEngineDispatchToken's declaration comment.
                    statusHandler.removeCallbacksAndMessages(reconnectEngineDispatchToken)
                    statusHandler.removeCallbacks(stopRetryRunnable)
                    statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
                    statusHandler.removeCallbacks(stopBindTimeoutRunnable)
                    requestStopIcsOpenVpn()
                } else {
                    startUserStopTeardown("user_action", forceReset = true)
                }
            }
            VpnManager.ACTION_STOP_IF_IDLE -> {
                AppLog.d(TAG, "ACTION_STOP_IF_IDLE")
                if (ConnectionStateManager.state.value != ConnectionState.DISCONNECTED) {
                    AppLog.d(TAG, "Ignoring stop-if-idle while VPN is active")
                    return START_NOT_STICKY
                }
                if (VpnManager.hasRecentActionStartDispatch()) {
                    AppLog.i(TAG, "Stop-if-idle stopSelf() aborted: recent ACTION_START dispatch still in flight")
                    return START_NOT_STICKY
                }
                exitControllerForeground()
                stopSelf()
            }
            VpnManager.ACTION_SYNC_STATUS -> {
                AppLog.d(TAG, "ACTION_SYNC_STATUS")
                if (ConnectionStateManager.state.value == ConnectionState.DISCONNECTED) {
                    exitControllerForeground()
                }
                oneShotSyncRequested = true
                oneShotSyncReceivedInitialState = false
                statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
                statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
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
        statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
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
        if (userInitiatedStop) return@Runnable
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
        if (userInitiatedStop) return@Runnable
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
        // Establish the safe state (nothing excluded, list interpreted as a disallow list) BEFORE
        // the fallible read. loadExcludedPackages() can throw -- getStringSet raises
        // ClassCastException on a corrupted or wrong-typed preference -- and if it did so while
        // these two assignments came after it, the profile would keep whatever it already carried.
        // Do not reorder: this method must never leave app-routing directives half-applied.
        profile.mAllowedAppsVpn.clear()
        profile.mAllowedAppsVpnAreDisallowed = true
        try {
            val excluded = AppFilterStore.loadExcludedPackages(applicationContext)
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
                stopLastFailureReason = "bind_failed"
                stopAttempt += 1
                AppLog.w(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} attempt=$stopAttempt dispatch=not_sent reason=bind_failed")
                scheduleStopRetryOrFail("bind_failed")
                return
            }
            stopBindPending = true
            statusHandler.removeCallbacks(stopBindTimeoutRunnable)
            statusHandler.postDelayed(stopBindTimeoutRunnable, STOP_BIND_TIMEOUT_MS)
        } else tryStopVpn()
    }

    private fun tryStopVpn() {
        if (!userInitiatedStop) {
            val stopped = try {
                engineBinder?.stopVPN(false) ?: false
            } catch (e: RemoteException) {
                AppLog.e(TAG, "Binder stop error", e)
                false
            }
            AppLog.i(TAG, "stopVPN invoked, result=$stopped")
            if (boundToEngine) {
                try {
                    unbindService(engineConnection)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to unbind engine after stop", e)
                }
                boundToEngine = false
            }
            return
        }

        if (stopAttempt >= STOP_DISPATCH_MAX_ATTEMPTS) {
            markStopFailure("dispatch_attempt_limit")
            return
        }

        stopAttempt += 1
        stopBindPending = false
        statusHandler.removeCallbacks(stopBindTimeoutRunnable)

        try {
            val stopped = engineBinder?.stopVPN(false) ?: false
            AppLog.i(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} attempt=$stopAttempt dispatch_result=$stopped")
            if (stopped) {
                stopAwaitingConfirmation = true
                stopLastFailureReason = null
                statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
                statusHandler.postDelayed(stopConfirmationTimeoutRunnable, STOP_CONFIRMATION_TIMEOUT_MS)
            } else {
                stopLastFailureReason = "dispatch_false"
                AppLog.w(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} attempt=$stopAttempt dispatch=failed reason=dispatch_false")
                scheduleStopRetryOrFail("dispatch_false")
            }
        } catch (e: RemoteException) {
            AppLog.e(TAG, "Binder stop error", e)
            stopLastFailureReason = "binder_exception"
            scheduleStopRetryOrFail("binder_exception")
        }
    }

    private fun handleEngineLevelForStop(level: ConnectionStatus, source: String) {
        if (!userInitiatedStop) return
        when (level) {
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.UNKNOWN_LEVEL -> {
                finishStopFlowConfirmed(level, source)
            }
            else -> Unit
        }
    }

    private fun stopSelfSafely() { stopSelf() }

    override fun onDestroy() {
        // Set before anything else -- including the autoSwitchDispatchToken sweep a few lines
        // below and unregisterStatusCallback() further down -- so there is no window where a
        // binder thread reading this flag observes a stale false. See the field's doc comment
        // for why this is distinct from userInitiatedStop.
        serviceDestroyed = true
        isInstanceAlive = false
        exitControllerForeground()
        unregisterAppLifecycleObserver()
        super.onDestroy()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeLogListener(this)
        try { VpnStatus.removeByteCountListener(this) } catch (_: Exception) {}
        statusHandler.removeCallbacks(statusRebindRunnable)
        statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
        statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
        statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
        statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
        statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
        statusHandler.removeCallbacks(stopRetryRunnable)
        statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
        statusHandler.removeCallbacks(stopBindTimeoutRunnable)
        statusHandler.removeCallbacksAndMessages(autoSwitchDispatchToken)
        // Sweep the reconnect engine-dispatch token too -- without this, a deferred dispatch
        // queued before onDestroy() would retain a strong reference to this Service for up to
        // ENGINE_RECONNECT_DISPATCH_BUFFER_MS after destroy.
        statusHandler.removeCallbacksAndMessages(reconnectEngineDispatchToken)
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
        ServerAutoSwitcher.probeRequestQueue = null
        probeQueue = null
        AppLog.d(TAG, "Service destroyed and listener removed")
    }

    private fun enterControllerForeground(): Boolean {
        // Always (re)issue Service.startForeground() below, even if controllerForegroundActive is
        // already true: a genuine ACTION_START must get a fresh call to satisfy Android's
        // foreground-service-start timing requirement. Repeated calls are idempotent.
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
            // Do NOT call stopSelf() here. ACTION_START calls this method after its own
            // startForegroundService() has already been dispatched, so the FGS-start obligation is
            // still undischarged -- stopSelf() here would turn a recoverable "could not show the
            // notification" failure into a ForegroundServiceDidNotStartInTimeException. Both
            // callers already treat a `false` return as sufficient signal to bail out.
            AppLog.e(TAG, "Failed to enter controller foreground", t)
            controllerForegroundActive = false
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
        if (maybeStartStaleStopReconciliation(level, "VPN_STATUS")) return
        maybeClearStaleStopIntentOnIdleLevel(level, "VPN_STATUS")
        if (shouldIgnoreLevelAfterUserStop(level)) return
        ConnectionStateManager.updateFromEngine(level, state)
        handleEngineLevelForStop(level, "VPN_STATUS")
        if (suppressEngineState) return

        if (userInitiatedStart && level in AUTO_SWITCH_LEVELS && !ConnectionStateManager.reconnectingHint.value) {
            val autoSwitchEnabled = try { com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore.load(applicationContext).autoSwitchWithinCountry } catch (_: Exception) { true }
            if (!autoSwitchEnabled) {
                AppLog.d(TAG, "Auto-switch disabled; skipping engine auto-switch path")
            } else {
                val candidates = try { SelectedCountryStore.getServers(applicationContext).size } catch (_: Exception) { -1 }
                if (candidates >= 0) AppLog.d(TAG, "Auto-switch candidates in selected country: ${candidates}")
                val vpnStatusFailingServerId = if (level != ConnectionStatus.LEVEL_NONETWORK) {
                    SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)
                } else 0
                val next = SelectedCountryStore.nextServer(applicationContext)
                if (vpnStatusFailingServerId != 0) {
                    try { probeQueue?.enqueue(vpnStatusFailingServerId) } catch (e: Exception) { AppLog.w(TAG, "VPN_STATUS fallback: failed to enqueue hardprobe for serverId=$vpnStatusFailingServerId", e) }
                }
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
                // Reached when auto-switch is disabled (or the level isn't handled by the
                // auto-switch block above): a failed user-initiated start must still clear
                // userInitiatedStart here, otherwise syncEngineState's reconnectPending guard
                // keeps suppressing exitControllerForeground() forever, leaving the "VPN
                // connecting" foreground notification stuck after the failed attempt.
                userInitiatedStart = false
                resumeActionInFlight = false
                statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
            }
            ConnectionStatus.LEVEL_VPNPAUSED -> {
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
            lastStatusSnapshotMs = watchdogNowMs()
            lastLiveStatusMs = lastStatusSnapshotMs
            lastLiveStatusElapsedRealtimeMs = elapsedRealtimeMs()
            staleSnapshotCount.set(0)
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
                    val now = watchdogNowMs()
                    if (lastStatusSnapshotMs == 0L || now - lastStatusSnapshotMs > 5_000L) {
                        trySyncStatusSnapshot()
                    }
                }

                val currentState = ConnectionStateManager.state.value
                if (currentState != lastPolledState) {
                    // A watchdog-driven recovery reconnects, so it lands here itself. Carry the
                    // attempt count across that transition -- otherwise the watchdog resets its own
                    // budget every time it spends some of it. Timing fields are deliberately NOT
                    // carried: the new tunnel gets a fresh warmup grace period.
                    val carriedRecoveryAttempts =
                        if (watchdogRecoveryInFlight) watchdogState.recoveryAttempts else 0
                    if (currentState == ConnectionState.CONNECTED) {
                        resetHealthWatchdog(nowMs = watchdogNowMs())
                    } else {
                        lastPolledDatapoint = null
                        resetHealthWatchdog()
                    }
                    watchdogState.recoveryAttempts = carriedRecoveryAttempts
                    lastPolledState = currentState
                }

                // Harden baseline CONNECTED state establishment
                val trafficBinder = statusBinder
                var sampleAdvanced = false
                var trafficDelta = 0L
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
                                sampleAdvanced = true
                                val diffIn = (latest.`in` - previous.`in`).coerceAtLeast(0L)
                                val diffOut = (latest.out - previous.out).coerceAtLeast(0L)
                                trafficDelta = diffIn + diffOut
                                val deltaMs = (latest.timestamp - previous.timestamp).coerceAtLeast(1L)
                                val bitsPerSec = (trafficDelta * 8.0) * (1000.0 / deltaMs.toDouble())
                                val mbps = bitsPerSec / 1_000_000.0
                                if (shouldPublishTrafficMetrics(currentState)) {
                                    ConnectionStateManager.updateSpeedMbps(mbps)
                                }
                            }

                            if (shouldPublishTrafficMetrics(currentState)) {
                                ConnectionStateManager.updateTraffic(latest.`in`, latest.out)
                            }
                            lastPolledDatapoint = latest
                        }
                    }
                }

                // Only force CONNECTED when we have verified health evidence from traffic samples.
                val engineLevel = ConnectionStateManager.engineLevel.value
                if (shouldForceConnectedState(engineLevel, sampleAdvanced, trafficDelta) &&
                    ConnectionStateManager.state.value != ConnectionState.CONNECTED &&
                    !pauseActionInFlight && !resumeActionInFlight && !userInitiatedStop) {
                    AppLog.i(TAG, "Hardened: Forcing CONNECTED state after engine connected and healthy traffic")
                    ConnectionStateManager.updateState(ConnectionState.CONNECTED)
                }

                if (ConnectionStateManager.state.value == ConnectionState.CONNECTED) {
                    evaluateConnectedHealth(sampleAdvanced = sampleAdvanced, trafficDeltaBytes = trafficDelta)
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    AppLog.w(TAG, "Error in trafficPollRunnable", e)
                }
            }

            trafficHandler.postDelayed(this, WATCHDOG_POLL_INTERVAL_MS)
        }
    }

    private fun shouldForceConnectedState(
        engineLevel: ConnectionStatus?,
        sampleAdvanced: Boolean,
        trafficDeltaBytes: Long
    ): Boolean {
        return engineLevel == ConnectionStatus.LEVEL_CONNECTED &&
            sampleAdvanced &&
            trafficDeltaBytes >= WATCHDOG_MIN_TRAFFIC_DELTA_BYTES
    }

    private fun shouldPublishTrafficMetrics(currentState: ConnectionState): Boolean {
        return currentState == ConnectionState.CONNECTED
    }

    private fun evaluateConnectedHealth(sampleAdvanced: Boolean, trafficDeltaBytes: Long) {
        val now = watchdogNowMs()
        if (watchdogState.connectedSinceMs == 0L) {
            watchdogState.connectedSinceMs = now
            watchdogState.lastHealthyTimestamp = now
        }

        if (sampleAdvanced && trafficDeltaBytes >= WATCHDOG_MIN_TRAFFIC_DELTA_BYTES) {
            markWatchdogHealthy(now, "traffic", trafficDeltaBytes, trafficVerified = true)
            return
        }

        if (now - watchdogState.connectedSinceMs < WATCHDOG_CONNECTED_WARMUP_MS) {
            AppLog.dThrottled(TAG, "Watchdog: warm-up active", key = "watchdog-warmup")
            return
        }

        if (watchdogState.lastRecoveryTimestamp > 0L && now - watchdogState.lastRecoveryTimestamp < WATCHDOG_RECOVERY_COOLDOWN_MS) {
            AppLog.dThrottled(TAG, "Watchdog: cooldown active", key = "watchdog-cooldown")
            return
        }

        if (watchdogProbeJob?.isActive == true) {
            AppLog.dThrottled(TAG, "Watchdog: probe already in flight", key = "watchdog-probe-in-flight")
            return
        }

        val probeTargets = resolveWatchdogProbeTargets()
        if (probeTargets.isEmpty()) {
            AppLog.w(TAG, "Watchdog: trusted probe target unavailable; treating as failed probe")
            handleConnectedProbeResult(probeSucceeded = false, trafficDeltaBytes = trafficDeltaBytes)
            return
        }

        watchdogProbeJob = serviceScope.launch(watchdogProbeDispatcher) {
            val probeSucceeded = probeTargets.any { target ->
                executeWatchdogProbe(target.host, target.port, WATCHDOG_PROBE_TIMEOUT_MS)
            }
            statusHandler.post {
                if (ConnectionStateManager.state.value != ConnectionState.CONNECTED) return@post
                handleConnectedProbeResult(probeSucceeded, trafficDeltaBytes)
            }
        }
    }

    private fun executeWatchdogProbe(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            watchdogProbe(host, port, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Watchdog: probe failed with exception", e)
            false
        }
    }

    private fun handleConnectedProbeResult(probeSucceeded: Boolean, trafficDeltaBytes: Long) {
        val now = watchdogNowMs()
        if (probeSucceeded) {
            // Reachable, but no traffic evidence: clears the failure streak, keeps the budget.
            markWatchdogHealthy(
                now,
                "probe",
                trafficDeltaBytes,
                trafficVerified = trafficDeltaBytes >= WATCHDOG_MIN_TRAFFIC_DELTA_BYTES
            )
            return
        }

        watchdogState.consecutiveFailures += 1
        AppLog.w(
            TAG,
            "Watchdog: unhealthy trafficDelta=${trafficDeltaBytes} probe=false thresholdCount=${watchdogState.consecutiveFailures}/${WATCHDOG_FAILURE_THRESHOLD}"
        )

        if (watchdogState.consecutiveFailures < WATCHDOG_FAILURE_THRESHOLD) return

        if (watchdogState.recoveryAttempts >= WATCHDOG_MAX_RECOVERY_ATTEMPTS) {
            AppLog.e(
                TAG,
                "Watchdog: bounded recovery exhausted; entering fail-safe disconnect"
            )
            triggerWatchdogFailSafeDisconnect("attempt_limit_reached")
            return
        }

        watchdogState.degraded = true
        watchdogState.recoveryAttempts += 1
        watchdogState.lastRecoveryTimestamp = now
        AppLog.i(
            TAG,
            "Watchdog: threshold reached trafficDelta=${trafficDeltaBytes} probe=false thresholdCount=${watchdogState.consecutiveFailures}/${WATCHDOG_FAILURE_THRESHOLD} recoveryAttempt=${watchdogState.recoveryAttempts}/${WATCHDOG_MAX_RECOVERY_ATTEMPTS}"
        )

        val recoveryTarget = resolveWatchdogRecoveryTarget()
        if (recoveryTarget == null) {
            AppLog.e(TAG, "Watchdog: no recovery target available; entering fail-safe disconnect")
            triggerWatchdogFailSafeDisconnect("missing_recovery_target")
            return
        }
        val watchdogServerId = SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)
        if (watchdogServerId != 0) {
            try { probeQueue?.enqueue(watchdogServerId) } catch (e: Exception) { AppLog.w(TAG, "Watchdog: failed to enqueue hardprobe for serverId=$watchdogServerId", e) }
        }
        try {
            // Set before dispatch: beginChainedSwitch can drive the state change synchronously.
            watchdogRecoveryInFlight = true
            val dispatched =
                watchdogRecoveryStarter(applicationContext, recoveryTarget.config, recoveryTarget.title)
            if (!dispatched) {
                // Nothing was dispatched -- auto-switch is off, or the stop command was rejected.
                // Do not burn the budget on attempts that never happen: that ends in a fail-safe
                // disconnect three cycles later with logs claiming recoveries that did not occur.
                // Fail safe now, for the same reason a missing recovery target does: there is no
                // mechanism to recover with.
                AppLog.e(TAG, "Watchdog: recovery not dispatched; entering fail-safe disconnect")
                triggerWatchdogFailSafeDisconnect("recovery_unavailable")
                return
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Watchdog: failed to dispatch recovery", e)
            triggerWatchdogFailSafeDisconnect("recovery_dispatch_failed")
        }
    }

    /**
     * @param trafficVerified true only when real traffic was observed. A successful TCP probe means
     *   the peer is reachable, which clears the failure streak -- but it is NOT evidence that the
     *   tunnel carries data, so it must not refill the recovery budget. Otherwise a tunnel that
     *   answers probes while passing nothing would reset the bound on every cycle and recover
     *   forever, which is the exact case the budget exists to stop.
     */
    private fun markWatchdogHealthy(
        nowMs: Long,
        source: String,
        trafficDeltaBytes: Long,
        trafficVerified: Boolean
    ) {
        val hadRecoveryState = watchdogState.degraded || watchdogState.recoveryAttempts > 0 || watchdogState.consecutiveFailures > 0
        watchdogState.consecutiveFailures = 0
        watchdogState.degraded = false
        if (trafficVerified) {
            // The recovery chain genuinely succeeded: the budget is spent and refilled.
            watchdogRecoveryInFlight = false
            watchdogState.recoveryAttempts = 0
        }
        watchdogState.lastHealthyTimestamp = nowMs
        watchdogState.lastRecoveryTimestamp = 0L
        AppLog.iThrottled(
            TAG,
            "Watchdog: healthy source=${source} trafficDelta=${trafficDeltaBytes} recovered=${hadRecoveryState} reconnectAfterRestore=${hadRecoveryState}",
            key = "watchdog-healthy-${source}-${hadRecoveryState}"
        )
    }

    private fun resetHealthWatchdog(nowMs: Long = 0L) {
        watchdogProbeJob?.cancel()
        watchdogProbeJob = null
        watchdogState = if (nowMs > 0L) {
            HealthWatchdogState(
                connectedSinceMs = nowMs,
                lastHealthyTimestamp = nowMs
            )
        } else {
            HealthWatchdogState()
        }
    }

    private fun resolveWatchdogProbeTargets(): List<WatchdogProbeTarget> {
        val targets = mutableListOf<WatchdogProbeTarget>()
        resolveActiveTunnelProbeTarget()?.let { targets += it }

        val candidates = listOfNotNull(
            runCatching { ApiConstants.primaryRetrofitBaseUrl() }.getOrNull(),
            ApiConstants.FALLBACK_SERVERS_URL
        )
        targets += candidates
            .mapNotNull { resolveWatchdogProbeTarget(it) }
        return targets.distinctBy { "${it.host}:${it.port}" }
    }

    private fun resolveActiveTunnelProbeTarget(): WatchdogProbeTarget? {
        val lastStarted = runCatching { SelectedCountryStore.getLastStartedConfig(applicationContext) }.getOrNull()
        parseRemoteEndpointFromConfig(lastStarted?.config)?.let { return it }

        val tunnelIp = lastStarted?.ip
            ?: runCatching { SelectedCountryStore.currentServer(applicationContext)?.ip }.getOrNull()
        val host = tunnelIp?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return WatchdogProbeTarget(host = host, port = WATCHDOG_FALLBACK_HTTPS_PORT)
    }

    private fun parseRemoteEndpointFromConfig(config: String?): WatchdogProbeTarget? {
        if (config.isNullOrBlank()) return null
        val remoteLine = config
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    !line.startsWith(";") &&
                    line.startsWith("remote") &&
                    line.getOrNull("remote".length)?.isWhitespace() == true
            }
            ?: return null

        val parts = remoteLine.split(Regex("\\s+"))
        if (parts.size < 2) return null

        val host = parts[1].trim().removePrefix("[").removeSuffix("]")
        if (host.isBlank()) return null

        val port = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: WATCHDOG_DEFAULT_OPENVPN_PORT
        return WatchdogProbeTarget(host = host, port = port)
    }

    private fun resolveWatchdogProbeTarget(rawUrl: String): WatchdogProbeTarget? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port > 0) uri.port else WATCHDOG_FALLBACK_HTTPS_PORT
        return WatchdogProbeTarget(host = host, port = port)
    }

    private fun resolveWatchdogRecoveryTarget(): WatchdogRecoveryTarget? {
        val selectedCountry = runCatching { SelectedCountryStore.getSelectedCountry(applicationContext) }.getOrNull()
        val lastStarted = runCatching { SelectedCountryStore.getLastStartedConfig(applicationContext) }.getOrNull()
        if (!lastStarted?.config.isNullOrBlank()) {
            return WatchdogRecoveryTarget(
                config = lastStarted!!.config!!,
                title = lastStarted.country ?: selectedCountry
            )
        }

        val lastSuccessfulConfig = runCatching {
            SelectedCountryStore.getLastSuccessfulConfigForSelected(applicationContext)
        }.getOrNull()
        return if (!lastSuccessfulConfig.isNullOrBlank()) {
            WatchdogRecoveryTarget(lastSuccessfulConfig, selectedCountry)
        } else {
            null
        }
    }

    private fun triggerWatchdogFailSafeDisconnect(reason: String) {
        AppLog.e(TAG, "Watchdog: fail-safe disconnect reason=${reason}")
        // The recovery chain is over either way; do not carry the count into whatever comes next.
        watchdogRecoveryInFlight = false
        watchdogState.recoveryAttempts = 0
        startUserStopTeardown("watchdog_fail_safe", forceReset = true)
    }

    private fun performReachabilityProbe(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
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
        val now = watchdogNowMs()
        val nowElapsedRealtimeMs = elapsedRealtimeMs()
        val ts = snapshot.timestampMs
        // Applied unconditionally to every level -- see staleSnapshotMaxAgeMs's declaration comment.
        if (ts > 0L) {
            // ACTION_SYNC_STATUS reattachment leaves currentAttemptStartMs at 0L; backfill it from
            // the first active snapshot so later ones have a baseline. Classified on the NORMALIZED
            // level -- a raw lagging LEVEL_NONETWORK on a "CONNECTED" state string would otherwise
            // skip the backfill.
            val normalizedLevelForBackfill = ConnectionStateManager.normalizeEngineLevel(level, snapshot.state)
            val willBackfillAttemptBaseline = currentAttemptStartMs == 0L &&
                normalizedLevelForBackfill !in STOP_TERMINAL_LEVELS
            val ageMs = now - ts
            // Age alone cannot separate a leftover snapshot from a past attempt (reject) from an
            // old-but-current one whose attempt is simply stuck (keep -- it is ServerAutoSwitcher's
            // only signal). When currentAttemptStartMs is known, the predates-check is the ONLY
            // test applied, independent of age; only when it is unknown does the age gate apply.
            val currentAttemptStartKnown = currentAttemptStartMs > 0L
            val knownToPredateCurrentAttempt = currentAttemptStartKnown && ts < currentAttemptStartMs
            // A backward wall-clock correction (e.g. NTP) makes every post-correction snapshot look
            // like it predates the attempt, silently defeating the stale-push auto-switch. Detect
            // it by pairing the wall clock with monotonic elapsedRealtime(): less wall-clock time
            // elapsed than real time means the clock moved back by roughly that difference, and a
            // predates-gap covered by that estimate is trusted. The ts <= now + slack term keeps a
            // genuinely stale pre-jump snapshot (its ts lands ahead of a corrected `now`) from
            // being waived by an unrelated jump elsewhere in the attempt's lifetime.
            val predatesExplainedByBackwardClockJump = knownToPredateCurrentAttempt &&
                currentAttemptStartElapsedRealtimeMs > 0L &&
                ts <= now + clockJumpSlackMs &&
                run {
                    val wallClockDeltaSinceStartMs = now - currentAttemptStartMs
                    val realElapsedSinceStartMs =
                        nowElapsedRealtimeMs - currentAttemptStartElapsedRealtimeMs
                    val estimatedJumpMs = realElapsedSinceStartMs - wallClockDeltaSinceStartMs
                    val predatesGapMs = currentAttemptStartMs - ts
                    estimatedJumpMs > clockJumpMinDetectableMs &&
                        predatesGapMs <= estimatedJumpMs + clockJumpSlackMs
                }
            val shouldRejectAsStale = if (currentAttemptStartKnown) {
                knownToPredateCurrentAttempt && !predatesExplainedByBackwardClockJump
            } else if (willBackfillAttemptBaseline) {
                // Reattach path: bounded trust rather than the unbounded acceptance that adopting
                // this snapshot's own timestamp as the baseline before the check would produce.
                // See reattachSnapshotMaxAgeMs's declaration comment.
                ageMs > reattachSnapshotMaxAgeMs
            } else {
                ageMs > staleSnapshotMaxAgeMs
            }
            if (shouldRejectAsStale) {
                if (now - lastLiveStatusMs <= liveStatusGraceMs) {
                    AppLog.w(TAG, "Skipping stale snapshot (live updates present) level=$level age=${ageMs}ms")
                    return
                }
                val staleCount = staleSnapshotCount.incrementAndGet()
                AppLog.w(TAG, "Skipping stale snapshot level=$level age=${ageMs}ms count=$staleCount")
                if (staleCount >= 3 && now - lastLiveStatusMs > staleSnapshotMaxAgeMs) {
                    forceRebindStatusService("stale snapshots age=${ageMs}ms")
                }
                return
            }
            // Backfilled only AFTER the staleness verdict above, and only from a snapshot that
            // passed it. Doing this before the check (the pre-fix ordering) set
            // currentAttemptStartMs = ts, which made currentAttemptStartKnown true and
            // `ts < currentAttemptStartMs` trivially false, so the age fallback -- the only
            // staleness test available when no baseline is known -- could never fire for the very
            // snapshot that supplied the baseline. Keeping the assignment here also stops a
            // rejected snapshot from installing an ancient baseline that every later comparison
            // would then measure against.
            if (willBackfillAttemptBaseline) {
                currentAttemptStartMs = ts
                // Estimate what elapsedRealtimeMs() was when this snapshot's own timestamp (ts)
                // was captured, keeping the pairing with currentAttemptStartMs consistent with the
                // ACTION_START path, where both fields are captured together at the same instant.
                currentAttemptStartElapsedRealtimeMs = nowElapsedRealtimeMs - ageMs
            }
        }
        staleSnapshotCount.set(0)
        lastStatusSnapshotMs = if (ts > 0L) ts else now
        logEngineStateChange("AIDL", level, snapshot.state)
        // isAidlFresh() also covers boundToStatus and lastLiveStatusMs > 0 (a live push has ever
        // arrived), not just the freshness-window comparison -- both can independently make it
        // false, e.g. the status binder dying on another thread mid-read of this snapshot.
        val livePushStale = !isAidlFresh()
        syncEngineState(level, snapshot.state, allowAutoSwitch = livePushStale)
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
        // Normalize ONCE, up front, and forward the SAME result to every consumer that derives an
        // "effective" engine level from (level, detail). Normalizing separately per consumer let
        // ServerAutoSwitcher and ConnectionStateManager observe two DIFFERENT effective levels for
        // the exact same snapshot -- a raw connecting-family level could start a needless switch
        // timer on an already-healthy connection, and a raw LEVEL_NONETWORK could trigger an
        // immediate switch away from a connection the app itself already recognizes as connected.
        // This "normalize once" guarantee covers exactly those two consumers
        // (dispatchAutoSwitcherOnEngineLevel / ConnectionStateManager.updateFromEngine) below --
        // it is not a whole-function invariant. The stop-flow reconciliation further down
        // (maybeStartStaleStopReconciliation, maybeClearStaleStopIntentOnIdleLevel,
        // shouldIgnoreLevelAfterUserStop, handleEngineLevelForStop) is intentionally fed the raw
        // `level`, not `normalizedLevel`: it reasons about what the engine literally reported, not
        // about the app's effective interpretation of it.
        val normalizedLevel = ConnectionStateManager.normalizeEngineLevel(level, detail)
        // LEVEL_NOTCONNECTED / LEVEL_NONETWORK: the engine is idle. Must NOT exit the FGS
        // notification in two situations: (1) chained auto-switch (reconnectingHint=true), where
        // the engine is intentionally stopped before the next server start -- dropping the
        // notification reopens the AMS FGS-obligation race; and (2) a user-initiated rapid
        // reconnect (userInitiatedStart=true), where a stale LEVEL_NOTCONNECTED from the previous
        // session may still be in-flight while a fresh ACTION_START's notification is the safety
        // net. ACTION_STOP and ACTION_SYNC_STATUS both call exitControllerForeground() explicitly,
        // so those paths are unaffected by this guard.
        val idleLevel = level == ConnectionStatus.LEVEL_NOTCONNECTED || level == ConnectionStatus.LEVEL_NONETWORK
        val reconnectPending = idleLevel && (ConnectionStateManager.reconnectingHint.value || userInitiatedStart)
        if (controllerForegroundActive
            && level != ConnectionStatus.LEVEL_START
            && level != ConnectionStatus.UNKNOWN_LEVEL
            && !reconnectPending) {
            exitControllerForeground()
        }
        // The only place userInitiatedStart gets cleared while the status service is fresh (the
        // VPN_STATUS path returns early then), otherwise reconnectPending stays stuck and the
        // "VPN connecting" notification is undismissable. Intentionally NOT followed by
        // exitControllerForeground(): a stale LEVEL_NOTCONNECTED arriving during a new start is
        // indistinguishable from a genuine failure here, and exiting would reopen the FGS window.
        if (level == ConnectionStatus.LEVEL_CONNECTED || level in AUTO_SWITCH_LEVELS) {
            userInitiatedStart = false
        }
        if (maybeStartStaleStopReconciliation(level, "AIDL")) return
        maybeClearStaleStopIntentOnIdleLevel(level, "AIDL")
        if (shouldIgnoreLevelAfterUserStop(level)) return
        if (allowAutoSwitch) {
            dispatchAutoSwitcherOnEngineLevel(normalizedLevel)
        }
        ConnectionStateManager.updateFromEngine(normalizedLevel, detail)
        handleEngineLevelForStop(level, "AIDL")
    }

    // Shared Handler token tagging every deferred dispatch posted by
    // dispatchAutoSwitcherOnEngineLevel() below, so teardown paths (startUserStopTeardown(),
    // onDestroy()) can cancel ALL of them in one removeCallbacksAndMessages(token) call. A single
    // mutable `Runnable?` field would only remember the most recently posted runnable: if the AIDL
    // binder thread posts more than one deferred dispatch before the main looper drains its queue,
    // each new post would orphan the previous one, leaving it uncancellable.
    private val autoSwitchDispatchToken = Any()

    // Dedicated token for the ENGINE_RECONNECT_DISPATCH_BUFFER_MS deferred dispatch (the
    // ACTION_START isReconnect==true branch below), swept at the same sites as
    // autoSwitchDispatchToken (onDestroy(), the preserveReconnect stop branch,
    // startUserStopTeardown()) so this dispatch is cancellable like every other deferred action in
    // this class.
    private val reconnectEngineDispatchToken = Any()

    // syncEngineState() is reachable both from the AIDL binder thread (updateStateString) and from
    // the main thread (applyStatusSnapshot). ServerAutoSwitcher's internal timer state is guarded
    // only by non-atomic check-then-act sequences that assume a single (main-looper) caller, so
    // every invocation is routed through the main-looper statusHandler when not already on the
    // main thread, serializing binder-thread and main-thread callers onto the same queue. The fast
    // path preserves synchronous behavior for the applyStatusSnapshot main-thread caller.
    private fun dispatchAutoSwitcherOnEngineLevel(level: ConnectionStatus) {
        // Checked at the enqueue point rather than relying solely on onDestroy()'s
        // removeCallbacksAndMessages(autoSwitchDispatchToken) sweep, which only clears dispatches
        // queued BEFORE the sweep runs: an in-flight binder callback that had already started
        // executing before teardown began, but had not yet reached this function, could still
        // enqueue a dispatch afterward. userInitiatedStop is not a substitute here during a
        // system-driven onDestroy() (e.g. task removal), where it stays false.
        if (serviceDestroyed) return
        // While a reconnect engine-dispatch buffer is pending for the current generation, no new
        // engine process has been asked to start yet, so any level received right now is
        // necessarily a stray delivery from the just-stopped previous engine -- forwarding it would
        // let it skip the selected server without ever trying it. See ReconnectDispatchGuard's
        // class-level KDoc.
        if (reconnectDispatchGuard.isBufferPendingForCurrentGeneration()) {
            AppLog.i(TAG, "Ignoring AIDL level=$level while reconnect engine-dispatch buffer is pending (stale from just-stopped engine)")
            return
        }
        // Capture whether ConnectionStateManager.state was CONNECTING synchronously, right now --
        // before syncEngineState() calls updateFromEngine() on the calling thread, which can flip
        // CONNECTING -> DISCONNECTED for a terminal level before a deferred dispatch below actually
        // runs. Passing this pre-mutation snapshot through preserves the ordering guarantee that an
        // immediate switch is not silently skipped just because the dispatch had to be deferred.
        val wasConnectingAtDispatch = try {
            ConnectionStateManager.state.value == ConnectionState.CONNECTING
        } catch (_: Exception) {
            false
        }
        // Snapshot the attempt generation valid right now, at the moment this level was received --
        // see ReconnectDispatchGuard's class-level KDoc. If a fresh ACTION_START bumps the live
        // counter before the runnable below actually executes, the mismatch proves a newer attempt
        // has begun since this dispatch was queued.
        val dispatchedForGeneration = reconnectDispatchGuard.currentGeneration
        val invoke = Runnable {
            // Defensive re-check: even if teardown's removeCallbacksAndMessages() raced with this
            // runnable already being pulled off the main-looper queue, don't act on it once the
            // user has stopped the VPN in the meantime, or once the service itself has been
            // destroyed (system-driven onDestroy(), where userInitiatedStop stays false).
            if (userInitiatedStop || serviceDestroyed) return@Runnable
            // Re-validated at the last possible moment, right before touching ServerAutoSwitcher:
            // userInitiatedStop is NOT a reliable signal for the stop-then-restart race a fresh
            // ACTION_START clears it back to false as part of starting the new attempt, even
            // though this dispatch was queued for a now-superseded attempt. A generation mismatch
            // means exactly that happened, so skip unconditionally regardless of the flag above.
            if (reconnectDispatchGuard.currentGeneration != dispatchedForGeneration) return@Runnable
            // Re-evaluate the same suppression predicate checked above, but here at execution time
            // rather than trusting the capture-time read: a level captured strictly between
            // ReconnectDispatchGuard's generation bump and its marker arm can observe a torn
            // (marker=stale, generation=G) snapshot and evade suppression at the earlier check.
            // This Runnable always executes on statusHandler's main looper -- the same thread that
            // owns the marker's only writers (ACTION_START and clearMarkerIfOwn()) -- so if
            // onStartCommand() has since armed the marker to match the live generation, this
            // catches it even though the capture-time check could not.
            if (reconnectDispatchGuard.isBufferPendingForCurrentGeneration()) {
                AppLog.i(TAG, "Ignoring AIDL level=$level at dispatch time; reconnect engine-dispatch buffer armed for the current generation after capture (stray from just-stopped engine)")
                return@Runnable
            }
            try {
                ServerAutoSwitcher.onEngineLevel(applicationContext, level, "AIDL", wasConnectingAtDispatch)
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to notify auto-switcher from AIDL", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invoke.run()
        } else {
            // Tag with the shared token (instead of a plain post()) so teardown can cancel this
            // dispatch -- and any other deferred dispatch still queued alongside it -- in one
            // removeCallbacksAndMessages(autoSwitchDispatchToken) call.
            statusHandler.postAtTime(invoke, autoSwitchDispatchToken, SystemClock.uptimeMillis())
        }
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
