package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.yahorzabotsin.openvpnclientgate.vpn.ConnectionStateManager

object ServerAutoSwitcher {
    private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ServerAutoSwitcher"
    private const val NO_REPLY_SWITCH_THRESHOLD_SECONDS = 5
    private const val REPLIED_SWITCH_THRESHOLD_SECONDS = 8
    private const val REPLIED_TIMEOUT_EXTRA_SECONDS = 3
    private const val START_AFTER_STOP_DELAY_MS = 350
    private const val STOP_RETRY_TIMEOUT_MS = 5_000L
    private const val UNKNOWN_PAUSED_GRACE_MS = 3_000L
    @Volatile private var noReplyThresholdSeconds: Int = NO_REPLY_SWITCH_THRESHOLD_SECONDS
    @Volatile private var repliedThresholdSeconds: Int = REPLIED_SWITCH_THRESHOLD_SECONDS
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var seconds: Int = 0
    @Volatile private var timerActive: Boolean = false
    @Volatile private var timerLevel: ConnectionStatus? = null
    internal var starter: (Context, String, String?, Boolean) -> Unit = { ctx, config, title, isReconnect -> VpnManager.startVpn(ctx, config, title, isReconnect) }
    internal var stopper: (Context) -> Unit = { ctx -> VpnManager.stopVpn(ctx) }
    // AC-3.3: Optional callback invoked when DEFAULT_V2 auto-switch is triggered but the
    // selected-country server list is empty. The callback must hydrate the list and then
    // invoke the provided completion action on the main thread.
    @Volatile internal var v2HydrationCallback: ((Context, () -> Unit) -> Unit)? = null
    @Volatile private var v2HydrationPending: Boolean = false
    @Volatile private var waitingStopForRetry: Boolean = false
    @Volatile private var pendingConfig: String? = null
    @Volatile private var pendingTitle: String? = null
    @Volatile private var cycleStartIndex: Int? = null
    private var stopRetryTimeoutRunnable: Runnable? = null
    private var idleToleranceRunnable: Runnable? = null
    private var idleToleranceLevel: ConnectionStatus? = null
    private var lastEngineLevel: ConnectionStatus? = null
    private var lastEngineSource: String? = null
    private val _remainingSeconds = MutableStateFlow<Int?>(null)
    val remainingSeconds = _remainingSeconds.asStateFlow()

    private fun thresholdFor(level: ConnectionStatus): Int = when (level) {
        ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> repliedThresholdSeconds
        ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET -> noReplyThresholdSeconds
        else -> noReplyThresholdSeconds
    }

    private fun isEnabled(ctx: Context): Boolean =
        try { UserSettingsStore.load(ctx).autoSwitchWithinCountry } catch (_: Exception) { true }

    private fun applyTimeoutFromSettings(ctx: Context) {
        val seconds = try { UserSettingsStore.load(ctx).statusStallTimeoutSeconds } catch (_: Exception) { null }
        if (seconds != null) {
            noReplyThresholdSeconds = seconds
            repliedThresholdSeconds = (seconds + REPLIED_TIMEOUT_EXTRA_SECONDS).coerceAtLeast(seconds)
        }
    }

    fun onEngineLevel(appContext: Context, level: ConnectionStatus, source: String) {
        logEngineLevel(level, source)
        if (level == ConnectionStatus.UNKNOWN_LEVEL) {
            scheduleIdleTolerance(appContext, level)
            return
        } else {
            cancelIdleTolerance()
        }

        if (waitingStopForRetry && level == ConnectionStatus.LEVEL_NOTCONNECTED) {
            val cfg = pendingConfig
            val title = pendingTitle
            pendingConfig = null
            pendingTitle = null
            waitingStopForRetry = false
            stopRetryTimeoutRunnable?.let { handler.removeCallbacks(it) }
            stopRetryTimeoutRunnable = null
            if (cfg != null) {
                AppLog.d(TAG, "Observed NOTCONNECTED after stop; starting next server")
                handler.postDelayed({ starter(appContext, cfg, title, true) }, START_AFTER_STOP_DELAY_MS.toLong())
                return
            }
        }

        val shouldSwitchImmediately =
            level == ConnectionStatus.LEVEL_AUTH_FAILED ||
                (source == "AIDL" && level == ConnectionStatus.LEVEL_NONETWORK)
        if (shouldSwitchImmediately && !waitingStopForRetry) {
            val isConnecting = try {
                ConnectionStateManager.state.value == ConnectionState.CONNECTING
            } catch (_: Exception) {
                false
            }
            if (timerActive || isConnecting) {
                requestSwitchNow(appContext, level = level, fromTimer = false, waitedSeconds = null)
            }
            return
        }

        val timeoutLevels = setOf(
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_AUTH_FAILED
        )
        if (level in timeoutLevels) {
            if (!timerActive) {
                start(appContext, level)
            } else if (timerLevel != level) {
                // Restart timer when CONNECTING sub-level changes, giving full timeout per level
                AppLog.d(TAG, "Auto-switch timer level change: ${timerLevel} -> ${level}")
                cancel(resetCycle = false)
                start(appContext, level)
            }
        } else {
            val shouldKeepCycle = try { ConnectionStateManager.reconnectingHint.value } catch (_: Exception) { false }
            val resetCycle = !shouldKeepCycle || level == ConnectionStatus.LEVEL_CONNECTED
            cancel(resetCycle = resetCycle)
        }
    }

    fun beginChainedSwitch(appContext: Context, config: String, title: String?) {
        if (!isEnabled(appContext)) {
            AppLog.d(TAG, "Auto-switch disabled; skipping chained switch")
            return
        }
        applyTimeoutFromSettings(appContext)
        if (cycleStartIndex == null) {
            cycleStartIndex = runCatching { SelectedCountryStore.getCurrentIndex(appContext) }.getOrNull()
        }
        try { ConnectionStateManager.setReconnectingHint(true); AppLog.d(TAG, "reconnectHint=true (begin chained switch)") } catch (e: Exception) { AppLog.w(TAG, "Failed to set reconnecting hint for chained switch", e) }
        AppLog.i(TAG, "Begin chained switch (title=${title ?: "<none>"}, cfgLen=${config.length})")
        pendingConfig = config
        pendingTitle = title
        waitingStopForRetry = true
        scheduleStopRetryTimeout(appContext)
        try {
            val dispatched = VpnManager.stopVpn(appContext, preserveReconnectHint = true)
            if (!dispatched) {
                AppLog.w(TAG, "Controller stop dispatch rejected for chained switch; aborting auto-switch")
                cancel(resetCycle = true)
                try { ConnectionStateManager.setReconnectingHint(false) } catch (e: Exception) { AppLog.w(TAG, "Failed to clear reconnecting hint after dispatch rejection", e) }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to request engine stop for chained switch", e)
            cancel(resetCycle = true)
            try { ConnectionStateManager.setReconnectingHint(false) } catch (ex: Exception) { AppLog.w(TAG, "Failed to clear reconnecting hint after stop exception", ex) }
        }
    }

    private fun start(appContext: Context, level: ConnectionStatus) {
        if (runnable != null) return
        applyTimeoutFromSettings(appContext)
        seconds = 0
        timerActive = true
        timerLevel = level
        val th = thresholdFor(level)
        _remainingSeconds.value = th
        AppLog.d(TAG, "Timeout timer started for level=${level}")
        try {
            val country = SelectedCountryStore.getSelectedCountry(appContext)
            val total = SelectedCountryStore.getServers(appContext).size
            val current = SelectedCountryStore.currentServer(appContext)?.city
            AppLog.d(TAG, "Selected country=${country ?: "<none>"} servers=$total current=${current ?: "<none>"}")
        } catch (e: Exception) { AppLog.w(TAG, "Failed to log selected country info", e) }
        val r = object : Runnable {
            override fun run() {
                if (!timerActive) { AppLog.d(TAG, "Switch timer canceled (state changed)"); return }
                seconds += 1
                val threshold = thresholdFor(timerLevel ?: level)
                _remainingSeconds.value = (threshold - seconds).coerceAtLeast(0)
                if (seconds % 30 == 0 || seconds >= threshold) {
                    AppLog.dThrottled(
                        TAG,
                        "Switch wait: ${seconds}s (level=${timerLevel})",
                        key = "switch-wait-${timerLevel}"
                    )
                }
                if (seconds >= threshold) {
                    requestSwitchNow(appContext, timerLevel ?: level, fromTimer = true, waitedSeconds = threshold)
                    return
                }
                handler.postDelayed(this, 1_000)
            }
        }
        runnable = r
        handler.postDelayed(r, 1_000)
    }

    private fun cancel(resetCycle: Boolean) {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = null
        if (timerActive || seconds > 0) {
            AppLog.d(TAG, "Switch timer stopped at ${seconds}s (level=${timerLevel})")
        }
        timerActive = false
        timerLevel = null
        seconds = 0
        waitingStopForRetry = false
        pendingConfig = null
        pendingTitle = null
        stopRetryTimeoutRunnable?.let { handler.removeCallbacks(it) }
        stopRetryTimeoutRunnable = null
        cancelIdleTolerance()
        _remainingSeconds.value = null
        v2HydrationPending = false
        if (resetCycle) {
            cycleStartIndex = null
        }
    }

    private fun requestSwitchNow(
        appContext: Context,
        level: ConnectionStatus,
        fromTimer: Boolean,
        waitedSeconds: Int?
    ) {
        if (!isEnabled(appContext)) {
            AppLog.d(TAG, "Auto-switch disabled; stopping timer and engine to avoid hang")
            cancel(resetCycle = true)
            try {
                ConnectionStateManager.setReconnectingHint(false)
                ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
            } catch (e: Exception) { AppLog.w(TAG, "Failed to reset state when auto-switch disabled", e) }
            try { stopper(appContext) } catch (e: Exception) { AppLog.w(TAG, "Failed to stop engine when auto-switch disabled", e) }
            return
        }

        val title = SelectedCountryStore.getSelectedCountry(appContext)
        val total = try { SelectedCountryStore.getServers(appContext).size } catch (e: Exception) { AppLog.w(TAG, "Failed to get server count", e); -1 }
        val next = try {
            if (cycleStartIndex == null) {
                cycleStartIndex = SelectedCountryStore.getCurrentIndex(appContext)
            }
            SelectedCountryStore.nextServerCircular(appContext, cycleStartIndex)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to resolve next server circularly", e)
            null
        }

        // AC-3.3: For DEFAULT_V2 with an empty store, request on-demand hydration before
        // concluding that no next server is available.
        if (next == null && total == 0 && !v2HydrationPending) {
            val serverSource = try { UserSettingsStore.load(appContext).serverSource } catch (_: Exception) { null }
            if (serverSource == ServerSource.DEFAULT_V2) {
                val callback = v2HydrationCallback
                if (callback != null) {
                    AppLog.i(TAG, "DEFAULT_V2: store empty at switch time, requesting on-demand hydration (level=${level})")
                    v2HydrationPending = true
                    val capturedLevel = level
                    val capturedFromTimer = fromTimer
                    val capturedWaited = waitedSeconds
                    callback(appContext) {
                        handler.post {
                            if (!v2HydrationPending) {
                                AppLog.d(TAG, "DEFAULT_V2: hydration callback received but state was reset, skipping retry")
                                return@post
                            }
                            v2HydrationPending = false
                            val hydratedTotal = try { SelectedCountryStore.getServers(appContext).size } catch (_: Exception) { 0 }
                            if (hydratedTotal > 0) {
                                AppLog.i(TAG, "DEFAULT_V2: hydration complete ($hydratedTotal servers), retrying switch")
                                SelectedCountryStore.resetIndex(appContext)
                                cycleStartIndex = null
                                requestSwitchNow(appContext, capturedLevel, capturedFromTimer, capturedWaited)
                            } else {
                                AppLog.w(TAG, "DEFAULT_V2: hydration yielded no servers, stopping engine")
                                cancel(resetCycle = true)
                                try { ConnectionStateManager.setReconnectingHint(false) } catch (e: Exception) { AppLog.w(TAG, "Failed to reset reconnecting hint after hydration failure", e) }
                                try { ConnectionStateManager.updateState(ConnectionState.DISCONNECTED) } catch (e: Exception) { AppLog.w(TAG, "Failed to reset state after hydration failure", e) }
                                try { stopper(appContext) } catch (e: Exception) { AppLog.w(TAG, "Failed to stop engine after hydration failure", e) }
                            }
                        }
                    }
                    return
                }
            }
        }

        if (next != null) {
            val position = runCatching { SelectedCountryStore.getCurrentPosition(appContext) }.getOrNull()
            val positionStr = position?.let { "${it.first}/${it.second}" } ?: "unknown"
            if (fromTimer && waitedSeconds != null) {
                AppLog.i(TAG, "Timed switch after ${waitedSeconds}s at level=${level}: ${title} -> ${next.city} (serversInCountry=${if (total>=0) total else "unknown"}, server=${positionStr}, ip=${next.ip ?: "<none>"})")
            } else {
                AppLog.i(TAG, "Immediate switch at level=${level}: ${title} -> ${next.city} (serversInCountry=${if (total>=0) total else "unknown"}, server=${positionStr}, ip=${next.ip ?: "<none>"})")
            }
            cancel(resetCycle = false)
            try { ConnectionStateManager.setReconnectingHint(true); AppLog.d(TAG, "reconnectHint=true (switch)") } catch (e: Exception) { AppLog.w(TAG, "Failed to set reconnecting hint for switch", e) }
            try {
                AppLog.d(TAG, "Requesting explicit engine stop before retry")
                pendingConfig = next.config
                pendingTitle = title
                waitingStopForRetry = true
                scheduleStopRetryTimeout(appContext)
                val dispatched = VpnManager.stopVpn(appContext, preserveReconnectHint = true)
                if (!dispatched) {
                    AppLog.w(TAG, "Controller stop dispatch rejected before retry; aborting auto-switch")
                    cancel(resetCycle = true)
                    try { ConnectionStateManager.setReconnectingHint(false) } catch (e: Exception) { AppLog.w(TAG, "Failed to clear reconnecting hint after dispatch rejection", e) }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to request engine stop before retry", e)
                cancel(resetCycle = true)
                try { ConnectionStateManager.setReconnectingHint(false) } catch (ex: Exception) { AppLog.w(TAG, "Failed to clear reconnecting hint after stop exception", ex) }
            }
            return
        }

        if (fromTimer) {
            AppLog.i(TAG, "Timed switch: completed full server cycle for ${title ?: "<unknown>"} (serversInCountry=${if (total>=0) total else "unknown"})")
        } else {
            AppLog.i(TAG, "Immediate switch: completed full server cycle for ${title ?: "<unknown>"} (serversInCountry=${if (total>=0) total else "unknown"})")
        }
        try {
            val startIndex = cycleStartIndex
            if (startIndex != null) {
                SelectedCountryStore.setCurrentIndex(appContext, startIndex)
            }
        } catch (e: Exception) { AppLog.w(TAG, "Failed to restore start index after full cycle", e) }
        cancel(resetCycle = true)
        try {
            ConnectionStateManager.setReconnectingHint(false)
            ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        } catch (e: Exception) { AppLog.w(TAG, "Failed to reset state after no-alternative path", e) }
        try {
            AppLog.d(TAG, "Requesting explicit engine stop (no-alternative path)")
            stopper(appContext)
        } catch (e: Exception) { AppLog.w(TAG, "Failed to request engine stop (no-alternative path)", e) }
    }

    private fun scheduleIdleTolerance(appContext: Context, level: ConnectionStatus) {
        if (idleToleranceLevel == level && idleToleranceRunnable != null) return
        cancelIdleTolerance()
        idleToleranceLevel = level
        AppLog.d(TAG, "Idle tolerance started for level=${level}")
        val r = Runnable {
            if (idleToleranceLevel != level) return@Runnable
            AppLog.d(TAG, "Idle tolerance elapsed for level=${level}")
            start(appContext, level)
        }
        idleToleranceRunnable = r
        handler.postDelayed(r, UNKNOWN_PAUSED_GRACE_MS)
    }

    private fun cancelIdleTolerance() {
        idleToleranceRunnable?.let { handler.removeCallbacks(it) }
        idleToleranceRunnable = null
        idleToleranceLevel = null
    }

    private fun logEngineLevel(level: ConnectionStatus, source: String) {
        if (level == lastEngineLevel && source == lastEngineSource) return
        lastEngineLevel = level
        lastEngineSource = source
        AppLog.dThrottled(
            TAG,
            "Engine level received: level=$level source=$source",
            key = "engine-level-$level-$source"
        )
    }

    private fun scheduleStopRetryTimeout(appContext: Context) {
        stopRetryTimeoutRunnable?.let { handler.removeCallbacks(it) }
        val hasPending = pendingConfig != null
        AppLog.d(TAG, "Stop retry timeout scheduled (${STOP_RETRY_TIMEOUT_MS}ms, pending=$hasPending)")
        val r = Runnable {
            if (!waitingStopForRetry) return@Runnable
            val cfg = pendingConfig
            val title = pendingTitle
            pendingConfig = null
            pendingTitle = null
            waitingStopForRetry = false
            AppLog.w(TAG, "Stop retry timeout; starting next server without NOTCONNECTED")
            if (cfg != null) {
                handler.postDelayed({ starter(appContext, cfg, title, true) }, START_AFTER_STOP_DELAY_MS.toLong())
            } else {
                AppLog.w(TAG, "Stop retry timeout; missing pending config")
            }
        }
        stopRetryTimeoutRunnable = r
        handler.postDelayed(r, STOP_RETRY_TIMEOUT_MS)
    }

    @JvmStatic
    fun setNoReplyThresholdForTest(seconds: Int) {
        noReplyThresholdSeconds = seconds.coerceAtLeast(1)
    }

    @JvmStatic
    fun resetNoReplyThreshold() {
        noReplyThresholdSeconds = NO_REPLY_SWITCH_THRESHOLD_SECONDS
    }

    @JvmStatic
    fun setRepliedThresholdForTest(seconds: Int) {
        repliedThresholdSeconds = seconds.coerceAtLeast(1)
    }

    @JvmStatic
    fun resetRepliedThreshold() {
        repliedThresholdSeconds = REPLIED_SWITCH_THRESHOLD_SECONDS
    }
}



