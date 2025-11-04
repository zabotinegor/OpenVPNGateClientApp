package com.yahorzabotsin.openvpnclient.vpn

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yahorzabotsin.openvpnclient.core.servers.SelectedCountryStore
import de.blinkt.openvpn.core.ConnectionStatus

object ServerAutoSwitcher {
    private const val TAG = "ServerAutoSwitcher"
    private const val NO_REPLY_SWITCH_THRESHOLD_SECONDS = 10
    private const val START_AFTER_STOP_DELAY_MS = 350
    @Volatile private var noReplyThresholdSeconds: Int = NO_REPLY_SWITCH_THRESHOLD_SECONDS
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var seconds: Int = 0
    @Volatile private var inNoReply: Boolean = false
    internal var starter: (Context, String, String?, Boolean) -> Unit = { ctx, config, title, isReconnect -> VpnManager.startVpn(ctx, config, title, isReconnect) }
    internal var stopper: (Context) -> Unit = { ctx -> VpnManager.stopVpn(ctx) }
    @Volatile private var waitingStopForRetry: Boolean = false
    @Volatile private var pendingConfig: String? = null
    @Volatile private var pendingTitle: String? = null

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

        if (level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET) {
            if (!inNoReply) {
                inNoReply = true
                start(appContext)
            }
        } else {
            cancel()
        }
    }

    fun beginChainedSwitch(appContext: Context, config: String, title: String?) {
        try { ConnectionStateManager.setReconnectingHint(true); Log.d(TAG, "reconnectHint=true (begin chained switch)") } catch (_: Exception) {}
        pendingConfig = config
        pendingTitle = title
        waitingStopForRetry = true
        try { VpnManager.stopVpn(appContext, preserveReconnectHint = true) } catch (e: Exception) { Log.w(TAG, "Failed to request engine stop for chained switch", e) }
    }

    private fun start(appContext: Context) {
        if (runnable != null) return
        seconds = 0
        Log.d(TAG, "No-reply timer started")
        try {
            val country = SelectedCountryStore.getSelectedCountry(appContext)
            val total = SelectedCountryStore.getServers(appContext).size
            val current = SelectedCountryStore.currentServer(appContext)?.city
            Log.d(TAG, "Selected country=${country ?: "<none>"} servers=$total current=${current ?: "<none>"}")
        } catch (e: Exception) { Log.w(TAG, "Failed to log selected country info", e) }
        val r = object : Runnable {
            override fun run() {
                if (!inNoReply) { Log.d(TAG, "No-reply timer canceled (state changed)"); return }
                seconds += 1
                Log.d(TAG, "No-reply wait: ${seconds}s")
                if (seconds >= noReplyThresholdSeconds) {
                    val next = SelectedCountryStore.nextServer(appContext)
                    val title = SelectedCountryStore.getSelectedCountry(appContext)
                    val total = try { SelectedCountryStore.getServers(appContext).size } catch (e: Exception) { Log.w(TAG, "Failed to get server count", e); -1 }
                    if (next != null) {
                        Log.i(TAG, "Timed switch: >${noReplyThresholdSeconds}s without server reply, switching to: ${title} -> ${next.city} (serversInCountry=${if (total>=0) total else "unknown"})")
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
        if (inNoReply || seconds > 0) {
            Log.d(TAG, "No-reply timer stopped at ${seconds}s")
        }
        inNoReply = false
        seconds = 0
        waitingStopForRetry = false
        pendingConfig = null
        pendingTitle = null
    }

    @JvmStatic
    fun setNoReplyThresholdForTest(seconds: Int) {
        noReplyThresholdSeconds = seconds.coerceAtLeast(1)
    }

    @JvmStatic
    fun resetNoReplyThreshold() {
        noReplyThresholdSeconds = NO_REPLY_SWITCH_THRESHOLD_SECONDS
    }
}
