package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.content.ContextCompat
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog

object VpnManager {

    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"
    const val ACTION_STOP_IF_IDLE = "stop_if_idle"
    const val ACTION_SYNC_STATUS = "sync_status"
    private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "VpnManager"

    fun extraConfigKey(context: Context) = "${context.packageName}.vpn.CONFIG"
    fun extraTitleKey(context: Context) = "${context.packageName}.vpn.TITLE"
    fun actionKey(context: Context) = "${context.packageName}.vpn.ACTION"

    fun extraAutoSwitchKey(context: Context) = "${context.packageName}.vpn.AUTOSWITCH"
    fun extraPreserveReconnectKey(context: Context) = "${context.packageName}.vpn.PRESERVE_RECONNECT"

    fun startVpn(context: Context, base64Config: String, displayName: String? = null, isReconnect: Boolean = false): Boolean {
        AppLog.d(TAG, "startVpn")
        val decodedConfig = try {
            String(Base64.decode(base64Config, Base64.DEFAULT))
        } catch (_: IllegalArgumentException) {
            base64Config
        }
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(extraConfigKey(context), decodedConfig)
            if (!displayName.isNullOrBlank()) putExtra(extraTitleKey(context), displayName)
            putExtra(actionKey(context), ACTION_START)
            putExtra(extraAutoSwitchKey(context), isReconnect)
        }
        return startControllerService(context, intent, ACTION_START)
    }

    fun stopVpn(context: Context, preserveReconnectHint: Boolean = false): Boolean {
        AppLog.d(TAG, "stopVpn")
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(actionKey(context), ACTION_STOP)
            putExtra(extraPreserveReconnectKey(context), preserveReconnectHint)
        }
        return startControllerService(context, intent, ACTION_STOP)
    }

    fun stopControllerIfIdle(context: Context): Boolean {
        AppLog.d(TAG, "stopControllerIfIdle")
        if (ConnectionStateManager.state.value != ConnectionState.DISCONNECTED) {
            AppLog.d(TAG, "skip stopControllerIfIdle: VPN is active")
            return false
        }
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(actionKey(context), ACTION_STOP_IF_IDLE)
        }
        return startControllerService(context, intent, ACTION_STOP_IF_IDLE)
    }

    fun syncStatus(context: Context): Boolean {
        AppLog.d(TAG, "syncStatus")
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(actionKey(context), ACTION_SYNC_STATUS)
        }
        return startControllerService(context, intent, ACTION_SYNC_STATUS)
    }

    private fun startControllerService(context: Context, intent: Intent, action: String): Boolean {
        return try {
            if (action == ACTION_START) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: IllegalStateException) {
            AppLog.w(TAG, "Failed to start controller service for action=$action", e)
            false
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Security error while starting controller for action=$action", e)
            false
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "Runtime error while starting controller for action=$action", e)
            false
        }
    }
}



