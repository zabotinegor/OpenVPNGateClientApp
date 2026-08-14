package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog

object VpnManager {

    const val ACTION_START = "start"
    const val ACTION_STOP = "stop"
    const val ACTION_PAUSE = "pause"
    const val ACTION_RESUME = "resume"
    const val ACTION_STOP_IF_IDLE = "stop_if_idle"
    const val ACTION_SYNC_STATUS = "sync_status"
    private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "VpnManager"

    fun extraConfigKey(context: Context) = "${context.packageName}.vpn.CONFIG"
    fun extraTitleKey(context: Context) = "${context.packageName}.vpn.TITLE"
    fun actionKey(context: Context) = "${context.packageName}.vpn.ACTION"

    fun extraAutoSwitchKey(context: Context) = "${context.packageName}.vpn.AUTOSWITCH"
    fun extraPreserveReconnectKey(context: Context) = "${context.packageName}.vpn.PRESERVE_RECONNECT"

    // Fix-cycle 7 QA finding (docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-qa-2.md,
    // "2026-08-14 continuation 2"): a genuine FATAL RemoteServiceException
    // $ForegroundServiceDidNotStartInTimeException was reproduced when OpenVpnService's one-shot
    // idle-teardown stopSelf() decision (stopAfterOneShotSyncConfirmedRunnable) fired only 3ms
    // before a fresh ACTION_START dispatched via startControllerService() below. Android's
    // ActivityManagerService begins its "waiting for startForeground()" obligation the instant
    // ContextCompat.startForegroundService() is CALLED, not once the Intent is actually delivered
    // to onStartCommand() -- so a Handler-scheduled stopSelf() decision that only recognizes a
    // fresh start via OpenVpnService's own userInitiatedStart flag (set inside onStartCommand)
    // cannot see it in time when the AMS/Binder round-trip delivering the Intent takes longer than
    // the few milliseconds separating the two events. This is a distinct root cause from review-7's
    // R7-1 (ConnectionStateManager staleness): here ConnectionStateManager's DISCONNECTED state is
    // genuinely correct at the moment stopSelf() is decided -- the race is purely about AMS's FGS
    // obligation clock starting before this app process can possibly know a new start intent even
    // exists.
    //
    // Fix: record when an ACTION_START dispatch is attempted, synchronously, on the SAME thread
    // that calls startControllerService() -- immediately BEFORE the actual
    // startForegroundService() call. Both this write and OpenVpnService's read
    // (hasRecentActionStartDispatch()) happen on the app's main thread (VpnManager.startVpn()'s
    // callers -- MainActivityCore's Connect tap and ServerAutoSwitcher's retry-timer starter --
    // and OpenVpnService's statusHandler all run on Looper.getMainLooper()), so as long as the
    // write happens-before the read in main-thread execution order, this closes the gap
    // deterministically: no AMS/Binder IPC latency can beat a same-thread, synchronous field
    // write that already completed before the write's own function call returned.
    @Volatile
    private var lastActionStartDispatchElapsedRealtimeMs: Long = 0L

    // Generous upper bound on the AMS/Binder round-trip between this call and OpenVpnService
    // .onStartCommand() actually running and setting its own userInitiatedStart=true (typically
    // single-digit milliseconds; kept far larger to tolerate scheduling pressure, cold starts, or
    // Doze/App-Standby deferral). Once onStartCommand() runs, userInitiatedStart is the
    // authoritative, longer-lived signal -- this flag only needs to bridge the brief pre-delivery
    // gap, and a stale flag left set for up to this long after a start that never actually landed
    // is an acceptable, bounded cost (mirrors the existing ONE_SHOT_STOP_CONFIRM_DELAY_MS /
    // STOP_RETRY_TIMEOUT_MS style of bounded safety windows elsewhere in this bug's fix history).
    private const val RECENT_ACTION_START_DISPATCH_WINDOW_MS = 2_000L

    /**
     * True if an `ACTION_START` dispatch via [startVpn] was attempted within the last
     * [RECENT_ACTION_START_DISPATCH_WINDOW_MS]. See [lastActionStartDispatchElapsedRealtimeMs]'s
     * declaration comment for the FGS-obligation-timing race this closes.
     */
    internal fun hasRecentActionStartDispatch(
        nowElapsedRealtimeMs: Long = android.os.SystemClock.elapsedRealtime()
    ): Boolean {
        val last = lastActionStartDispatchElapsedRealtimeMs
        return last > 0L && (nowElapsedRealtimeMs - last) <= RECENT_ACTION_START_DISPATCH_WINDOW_MS
    }

    @JvmStatic
    internal fun resetActionStartDispatchTrackingForTest() {
        lastActionStartDispatchElapsedRealtimeMs = 0L
    }

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

    @MainThread
    fun pauseVpn(context: Context): Boolean {
        AppLog.d(TAG, "pauseVpn")
        val currentState = ConnectionStateManager.state.value
        AppLog.d(TAG, "pauseVpn: current state = $currentState")

        // Only send pause if we're already connected or transitioning from connected
        if (currentState != ConnectionState.CONNECTED && currentState != ConnectionState.PAUSING) {
            AppLog.w(TAG, "pauseVpn: ignoring pause request, not in CONNECTED state (current=$currentState)")
            return false
        }

        val previousState = currentState
        ConnectionStateManager.beginPauseTransition()
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(actionKey(context), ACTION_PAUSE)
        }
        val result = startControllerService(context, intent, ACTION_PAUSE)
        if (!result) {
            AppLog.w(TAG, "pauseVpn: failed to send pause command, rolling state back to $previousState")
            if (previousState == ConnectionState.CONNECTED) {
                // Restore CONNECTED through an allowed transition path.
                ConnectionStateManager.updateState(ConnectionState.CONNECTING)
            }
            ConnectionStateManager.updateState(previousState)
        }
        AppLog.i(TAG, "pauseVpn: sent pause command, result=$result")
        return result
    }

    @MainThread
    fun resumeVpn(context: Context): Boolean {
        AppLog.d(TAG, "resumeVpn")
        val previousState = ConnectionStateManager.state.value
        // Reflect reconnecting UI immediately after resume tap while engine status catches up.
        ConnectionStateManager.beginResumeTransition()
        val intent = Intent(context.applicationContext, OpenVpnService::class.java).apply {
            putExtra(actionKey(context), ACTION_RESUME)
        }
        val result = startControllerService(context, intent, ACTION_RESUME)
        if (!result) {
            AppLog.w(TAG, "resumeVpn: failed to send resume command, rolling state back to $previousState")
            ConnectionStateManager.updateState(previousState)
        }
        return result
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
                // Record the dispatch attempt BEFORE the actual call -- see
                // lastActionStartDispatchElapsedRealtimeMs's declaration comment. Recorded even if
                // the call below throws: a failed dispatch still means AMS may have registered the
                // FGS-start obligation before raising the exception, and the flag's cost if a start
                // never truly lands is only a bounded RECENT_ACTION_START_DISPATCH_WINDOW_MS delay
                // to the idle-teardown path, not a correctness issue.
                lastActionStartDispatchElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
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