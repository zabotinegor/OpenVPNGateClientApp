package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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

    @Volatile
    private var lastActionStartDispatchElapsedRealtimeMs: Long = 0L

    private const val RECENT_ACTION_START_DISPATCH_WINDOW_MS = 2_000L

    private const val IDLE_RECHECK_AFTER_FAILED_START_DELAY_MS = RECENT_ACTION_START_DISPATCH_WINDOW_MS + 250L

    private val recheckHandler = Handler(Looper.getMainLooper())

    private var idleRecheckRunnable: Runnable? = null

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

    /**
     * R9-3 (fix-cycle 9, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-9.md):
     * hand authority back from this bridge marker to [OpenVpnService]'s own `userInitiatedStart`
     * flag once `ACTION_START` has actually been delivered and processed -- exactly as
     * [lastActionStartDispatchElapsedRealtimeMs]'s declaration comment already says should happen
     * ("Once onStartCommand() runs, userInitiatedStart is the authoritative, longer-lived signal").
     * Before this fix nothing ever called this outside of tests, so the marker stayed "recent" for
     * the full [RECENT_ACTION_START_DISPATCH_WINDOW_MS] even after the start had fully landed --
     * every `hasRecentActionStartDispatch()` guard (cycle 7's original site plus fix-cycle 8's two
     * additions) then DROPPED an intervening stop decision outright instead of merely deferring it,
     * for up to that whole window. Call this from `onStartCommand()`'s `ACTION_START` branch right
     * after `userInitiatedStart = true` is set, so the two signals hand off at the same point the
     * comment already documents.
     */
    @JvmStatic
    internal fun clearRecentActionStartDispatch() {
        lastActionStartDispatchElapsedRealtimeMs = 0L
    }

    /**
     * See [IDLE_RECHECK_AFTER_FAILED_START_DELAY_MS]'s declaration comment. Called only from
     * [startControllerService]'s catch blocks, only for a failed `ACTION_START` dispatch. Posts a
     * single delayed [stopControllerIfIdle] re-check timed to run after
     * [RECENT_ACTION_START_DISPATCH_WINDOW_MS] has definitely elapsed, so
     * `hasRecentActionStartDispatch()` is guaranteed false by the time it runs and cannot itself
     * re-suppress the very teardown it exists to unblock. [stopControllerIfIdle] already no-ops
     * whenever [ConnectionStateManager] is not `DISCONNECTED` by then (a later retry succeeded, or
     * the user reconnected some other way), so this is safe to fire unconditionally on every failed
     * `ACTION_START` dispatch.
     */
    private fun scheduleIdleRecheckAfterFailedStartDispatch(context: Context) {
        val appContext = context.applicationContext
        idleRecheckRunnable?.let { recheckHandler.removeCallbacks(it) }
        val recheck = Runnable {
            idleRecheckRunnable = null
            if (OpenVpnService.isInstanceAlive) {
                stopControllerIfIdle(appContext)
            }
        }
        idleRecheckRunnable = recheck
        recheckHandler.postDelayed(recheck, IDLE_RECHECK_AFTER_FAILED_START_DELAY_MS)
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
                lastActionStartDispatchElapsedRealtimeMs = android.os.SystemClock.elapsedRealtime()
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            true
        } catch (e: IllegalStateException) {
            AppLog.w(TAG, "Failed to start controller service for action=$action", e)
            if (action == ACTION_START) scheduleIdleRecheckAfterFailedStartDispatch(context)
            false
        } catch (e: SecurityException) {
            AppLog.w(TAG, "Security error while starting controller for action=$action", e)
            if (action == ACTION_START) scheduleIdleRecheckAfterFailedStartDispatch(context)
            false
        } catch (e: RuntimeException) {
            AppLog.w(TAG, "Runtime error while starting controller for action=$action", e)
            if (action == ACTION_START) scheduleIdleRecheckAfterFailedStartDispatch(context)
            false
        }
    }
}