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
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var seconds: Int = 0
    @Volatile private var inNoReply: Boolean = false

    fun onEngineLevel(appContext: Context, level: ConnectionStatus) {
        if (level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET) {
            if (!inNoReply) {
                inNoReply = true
                start(appContext)
            }
        } else {
            cancel()
        }
    }

    private fun start(appContext: Context) {
        if (runnable != null) return
        seconds = 0
        Log.d(TAG, "No-reply timer started")
        val r = object : Runnable {
            override fun run() {
                if (!inNoReply) { Log.d(TAG, "No-reply timer canceled (state changed)"); runnable = null; return }
                seconds += 1
                Log.d(TAG, "No-reply wait: ${seconds}s")
                if (seconds >= NO_REPLY_SWITCH_THRESHOLD_SECONDS) {
                    val next = SelectedCountryStore.nextServer(appContext)
                    val title = SelectedCountryStore.getSelectedCountry(appContext)
                    if (next != null) {
                        Log.i(TAG, "Timed switch: >${NO_REPLY_SWITCH_THRESHOLD_SECONDS}s without server reply, switching to: ${title} -> ${next.city}")
                        cancel()
                        VpnManager.startVpn(appContext, next.config, title)
                        return
                    } else {
                        Log.i(TAG, "Timed switch: no alternative servers available in selected country")
                        cancel()
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
    }
}
