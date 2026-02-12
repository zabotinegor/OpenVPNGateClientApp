package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog

class DisconnectReceiver : BroadcastReceiver() {
    private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "DisconnectReceiver"

    override fun onReceive(context: Context, intent: Intent?) {
        AppLog.i(TAG, "Disconnect action received from notification.")
        val pendingResult = goAsync()
        Thread {
            try {
                val dispatched = VpnManager.stopVpn(context.applicationContext)
                if (!dispatched) {
                    AppLog.w(TAG, "Controller stop dispatch rejected; launching DisconnectVPN fallback")
                    try {
                        context.startActivity(Intent().apply {
                            setClassName(context.applicationContext, "de.blinkt.openvpn.activities.DisconnectVPN")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (activityError: Exception) {
                        AppLog.w(TAG, "Failed to launch DisconnectVPN fallback activity", activityError)
                    }
                }
            } catch (e: Exception) {
                AppLog.w(TAG, "Unexpected stop path error; launching DisconnectVPN", e)
                try {
                    context.startActivity(Intent().apply {
                        setClassName(context.applicationContext, "de.blinkt.openvpn.activities.DisconnectVPN")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                } catch (activityError: Exception) {
                    AppLog.w(TAG, "Failed to launch DisconnectVPN fallback activity", activityError)
                }
            } finally {
                pendingResult.finish()
            }
        }.start()
    }
}



