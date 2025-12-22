package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclient.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.yahorzabotsin.openvpnclient.vpn.ConnectionStateManager

object ServerAutoSwitcher {
    private val TAG = com.yahorzabotsin.openvpnclient.core.logging.LogTags.APP + ':' + "ServerAutoSwitcher"
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
                Log.d(TAG, "Observed NOTCONNECTED after stop; starting next server")
                handler.postDelayed({ starter(appContext, cfg, title, true) }, START_AFTER_STOP_DELAY_MS.toLong())
                return
            }
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
                Log.d(TAG, "Auto-switch timer level change: ${timerLevel} -> ${level}")
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
            Log.d(TAG, "Auto-switch disabled; skipping chained switch")
            return
        }
        applyTimeoutFromSettings(appContext)
        if (cycleStartIndex == null) {
            cycleStartIndex = runCatching { SelectedCountryStore.getCurrentIndex(appContext) }.getOrNull()
        }
        try { ConnectionStateManager.setReconnectingHint(true); Log.d(TAG, "reconnectHint=true (begin chained switch)") } catch (e: Exception) { Log.w(TAG, "Failed to set reconnecting hint for chained switch", e) }
        Log.i(TAG, "Begin chained switch (title=${title ?: "<none>"}, cfgLen=${config.length})")
        pendingConfig = config
        pendingTitle = title
        waitingStopForRetry = true
        scheduleStopRetryTimeout(appContext)
        try { VpnManager.stopVpn(appContext, preserveReconnectHint = true) } catch (e: Exception) { Log.w(TAG, "Failed to request engine stop for chained switch", e) }
    }

    private fun start(appContext: Context, level: ConnectionStatus) {
        if (runnable != null) return
        applyTimeoutFromSettings(appContext)
        seconds = 0
        timerActive = true
        timerLevel = level
        val th = thresholdFor(level)
        _remainingSeconds.value = th
        Log.d(TAG, "Timeout timer started for level=${level}")
        try {
            val country = SelectedCountryStore.getSelectedCountry(appContext)
            val total = SelectedCountryStore.getServers(appContext).size
            val current = SelectedCountryStore.currentServer(appContext)?.city
            Log.d(TAG, "Selected country=${country ?: "<none>"} servers=$total current=${current ?: "<none>"}")
        } catch (e: Exception) { Log.w(TAG, "Failed to log selected country info", e) }
        val r = object : Runnable {
            override fun run() {
                if (!timerActive) { Log.d(TAG, "Switch timer canceled (state changed)"); return }
                seconds += 1
                val threshold = thresholdFor(timerLevel ?: level)
                _remainingSeconds.value = (threshold - seconds).coerceAtLeast(0)
                if (seconds % 5 == 0 || seconds >= threshold) {
                    Log.d(TAG, "Switch wait: ${seconds}s (level=${timerLevel})")
                }
                if (seconds >= threshold) {
                    if (!isEnabled(appContext)) {
                        Log.d(TAG, "Auto-switch disabled; stopping timer and engine to avoid hang")
                        cancel(resetCycle = true)
                        try {
                            ConnectionStateManager.setReconnectingHint(false)
                            ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
                        } catch (e: Exception) { Log.w(TAG, "Failed to reset state when auto-switch disabled", e) }
                        try { stopper(appContext) } catch (e: Exception) { Log.w(TAG, "Failed to stop engine when auto-switch disabled", e) }
                        return
                    }
                    val title = SelectedCountryStore.getSelectedCountry(appContext)
                    val total = try { SelectedCountryStore.getServers(appContext).size } catch (e: Exception) { Log.w(TAG, "Failed to get server count", e); -1 }
                    val next = try {
                        if (cycleStartIndex == null) {
                            cycleStartIndex = SelectedCountryStore.getCurrentIndex(appContext)
                        }
                        SelectedCountryStore.nextServerCircular(appContext, cycleStartIndex)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to resolve next server circularly", e); null
                    }
                    if (next != null) {
                        val position = runCatching { SelectedCountryStore.getCurrentPosition(appContext) }.getOrNull()
                        val positionStr = position?.let { "${it.first}/${it.second}" } ?: "unknown"
                        Log.i(TAG, "Timed switch after ${threshold}s at level=${timerLevel}: ${title} -> ${next.city} (serversInCountry=${if (total>=0) total else "unknown"}, server=${positionStr}, ip=${next.ip ?: "<none>"})")
                        cancel(resetCycle = false)
                        try { ConnectionStateManager.setReconnectingHint(true); Log.d(TAG, "reconnectHint=true (timed switch)") } catch (e: Exception) { Log.w(TAG, "Failed to set reconnecting hint for timed switch", e) }
                        try {
                            Log.d(TAG, "Requesting explicit engine stop before retry")
                            pendingConfig = next.config
                            pendingTitle = title
                            waitingStopForRetry = true
                            scheduleStopRetryTimeout(appContext)
                            VpnManager.stopVpn(appContext, preserveReconnectHint = true)
                        } catch (e: Exception) { Log.w(TAG, "Failed to request engine stop before retry", e) }
                        return
                    } else {
                        Log.i(TAG, "Timed switch: completed full server cycle for ${title ?: "<unknown>"} (serversInCountry=${if (total>=0) total else "unknown"})")
                        try {
                            val startIndex = cycleStartIndex
                            if (startIndex != null) {
                                SelectedCountryStore.setCurrentIndex(appContext, startIndex)
                            }
                        } catch (e: Exception) { Log.w(TAG, "Failed to restore start index after full cycle", e) }
                        cancel(resetCycle = true)
                        try {
                            ConnectionStateManager.setReconnectingHint(false)
                            ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
                        } catch (e: Exception) { Log.w(TAG, "Failed to reset state after no-alternative path", e) }
                        try {
                            Log.d(TAG, "Requesting explicit engine stop (no-alternative path)")
                            stopper(appContext)
                        } catch (e: Exception) { Log.w(TAG, "Failed to request engine stop (no-alternative path)", e) }
                        return
                    }
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
            Log.d(TAG, "Switch timer stopped at ${seconds}s (level=${timerLevel})")
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
        if (resetCycle) {
            cycleStartIndex = null
        }
    }

    private fun scheduleIdleTolerance(appContext: Context, level: ConnectionStatus) {
        if (idleToleranceLevel == level && idleToleranceRunnable != null) return
        cancelIdleTolerance()
        idleToleranceLevel = level
        Log.d(TAG, "Idle tolerance started for level=${level}")
        val r = Runnable {
            if (idleToleranceLevel != level) return@Runnable
            Log.d(TAG, "Idle tolerance elapsed for level=${level}")
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
        Log.d(TAG, "Engine level received: level=$level source=$source")
    }

    private fun scheduleStopRetryTimeout(appContext: Context) {
        stopRetryTimeoutRunnable?.let { handler.removeCallbacks(it) }
        val hasPending = pendingConfig != null
        Log.d(TAG, "Stop retry timeout scheduled (${STOP_RETRY_TIMEOUT_MS}ms, pending=$hasPending)")
        val r = Runnable {
            if (!waitingStopForRetry) return@Runnable
            val cfg = pendingConfig
            val title = pendingTitle
            pendingConfig = null
            pendingTitle = null
            waitingStopForRetry = false
            Log.w(TAG, "Stop retry timeout; starting next server without NOTCONNECTED")
            if (cfg != null) {
                handler.postDelayed({ starter(appContext, cfg, title, true) }, START_AFTER_STOP_DELAY_MS.toLong())
            } else {
                Log.w(TAG, "Stop retry timeout; missing pending config")
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

