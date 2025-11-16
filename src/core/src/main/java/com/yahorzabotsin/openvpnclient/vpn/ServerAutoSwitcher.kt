package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import de.blinkt.openvpn.core.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ServerAutoSwitcher {
    private const val TAG = "ServerAutoSwitcher"
    private const val NO_REPLY_SWITCH_THRESHOLD_SECONDS = 10
    private const val REPLIED_SWITCH_THRESHOLD_SECONDS = 10
    private const val START_AFTER_STOP_DELAY_MS = 350
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
    private val _remainingSeconds = MutableStateFlow<Int?>(null)
    val remainingSeconds = _remainingSeconds.asStateFlow()

    private fun thresholdFor(level: ConnectionStatus): Int = when (level) {
        ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED -> repliedThresholdSeconds
        ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET -> noReplyThresholdSeconds
        else -> noReplyThresholdSeconds
    }

    fun onEngineLevel(appContext: Context, level: ConnectionStatus) {
        if (waitingStopForRetry && level == ConnectionStatus.LEVEL_NOTCONNECTED) {
            val cfg = pendingConfig
            val title = pendingTitle
            pendingConfig = null
            pendingTitle = null
            waitingStopForRetry = false
            if (cfg != null) {
                Log.d(TAG, "Observed NOTCONNECTED after stop; starting next server")
                handler.postDelayed({ starter(appContext, cfg, title, true) }, START_AFTER_STOP_DELAY_MS.toLong())
                return
            }
        }

        val timeoutLevels = setOf(
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED
        )
        if (level in timeoutLevels) {
            if (!timerActive) {
                start(appContext, level)
            } else if (timerLevel != level) {
                // Restart timer when CONNECTING sub-level changes, giving full timeout per level
                cancel()
                start(appContext, level)
            }
        } else {
            cancel()
        }
    }

    fun beginChainedSwitch(appContext: Context, config: String, title: String?) {
        try { ConnectionStateManager.setReconnectingHint(true); Log.d(TAG, "reconnectHint=true (begin chained switch)") } catch (e: Exception) { Log.w(TAG, "Failed to set reconnecting hint for chained switch", e) }
        pendingConfig = config
        pendingTitle = title
        waitingStopForRetry = true
        try { VpnManager.stopVpn(appContext, preserveReconnectHint = true) } catch (e: Exception) { Log.w(TAG, "Failed to request engine stop for chained switch", e) }
    }

    private fun start(appContext: Context, level: ConnectionStatus) {
        if (runnable != null) return
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
                Log.d(TAG, "Switch wait: ${seconds}s (level=${timerLevel})")
                if (seconds >= threshold) {
                    val next = SelectedCountryStore.nextServer(appContext)
                    val title = SelectedCountryStore.getSelectedCountry(appContext)
                    val total = try { SelectedCountryStore.getServers(appContext).size } catch (e: Exception) { Log.w(TAG, "Failed to get server count", e); -1 }
                    if (next != null) {
                        Log.i(TAG, "Timed switch after ${threshold}s at level=${timerLevel}: ${title} -> ${next.city} (serversInCountry=${if (total>=0) total else "unknown"})")
                        cancel()
                        try { ConnectionStateManager.setReconnectingHint(true); Log.d(TAG, "reconnectHint=true (timed switch)") } catch (e: Exception) { Log.w(TAG, "Failed to set reconnecting hint for timed switch", e) }
                        try {
                            Log.d(TAG, "Requesting explicit engine stop before retry")
                            pendingConfig = next.config
                            pendingTitle = title
                            waitingStopForRetry = true
                            VpnManager.stopVpn(appContext, preserveReconnectHint = true)
                        } catch (e: Exception) { Log.w(TAG, "Failed to request engine stop before retry", e) }
                        return
                    } else {
                        Log.i(TAG, "Timed switch: no alternative servers available in selected country (serversInCountry=${if (total>=0) total else "unknown"})")
                        cancel()
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

    private fun cancel() {
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
        _remainingSeconds.value = null
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
