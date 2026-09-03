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
import com.yahorzabotsin.openvpnclientgate.core.servers.probe.ProbeRequestQueue

object ServerAutoSwitcher {
    private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "ServerAutoSwitcher"
    private const val NO_REPLY_SWITCH_THRESHOLD_SECONDS = 5
    private const val REPLIED_SWITCH_THRESHOLD_SECONDS = 8
    private const val REPLIED_TIMEOUT_EXTRA_SECONDS = 3
    private const val START_AFTER_STOP_DELAY_MS = 350
    private const val STOP_RETRY_TIMEOUT_MS = 5_000L
    // Bounded re-dispatch for the stop-retry-timeout blank-config branch when ACTION_STOP cannot
    // even be handed to the controller service. Deliberately the same shape and values as
    // OpenVpnService's STOP_DISPATCH_MAX_ATTEMPTS / STOP_DISPATCH_RETRY_DELAY_MS
    // (scheduleStopRetryOrFail()), because this branch is standing in for exactly that machinery:
    // a rejected dispatch never reaches the service, so the service can never retry it itself.
    private const val TIMEOUT_STOP_DISPATCH_MAX_ATTEMPTS = 3
    private const val TIMEOUT_STOP_DISPATCH_RETRY_DELAY_MS = 1_000L
    private const val UNKNOWN_PAUSED_GRACE_MS = 3_000L
    @Volatile private var noReplyThresholdSeconds: Int = NO_REPLY_SWITCH_THRESHOLD_SECONDS
    @Volatile private var repliedThresholdSeconds: Int = REPLIED_SWITCH_THRESHOLD_SECONDS
    private val handler = Handler(Looper.getMainLooper())
    private var runnable: Runnable? = null
    private var seconds: Int = 0
    @Volatile private var timerActive: Boolean = false
    @Volatile private var timerLevel: ConnectionStatus? = null
    internal var starter: (Context, String, String?, Boolean) -> Boolean = { ctx, config, title, isReconnect -> VpnManager.startVpn(ctx, config, title, isReconnect) }
    // F1-8 (fix-cycle 8, PR #140 round 3 -- Codex comment 3922158403 and Kody comment 3922135864
    // reported this independently): this dispatcher returns Boolean, NOT Unit.
    // VpnManager.stopVpn() -> startControllerService() returns false when Context.startService()
    // is rejected (IllegalStateException from the background-start restriction, SecurityException,
    // ...), and on that path NOTHING reaches OpenVpnService: startUserStopTeardown() never runs, so
    // none of the service's own confirmation-timeout/retry machinery is armed either. While this
    // field was typed (Context) -> Unit, every call site silently discarded that signal, and the
    // stop-retry-timeout branch below settled the app to DISCONNECTED regardless -- reporting a
    // possibly still-live tunnel as disconnected with no stop pending anywhere. Call sites that
    // genuinely cannot act on a rejection may still ignore the result, but they must do so
    // deliberately.
    internal var stopper: (Context) -> Boolean = { ctx -> VpnManager.stopVpn(ctx) }
    // Fix-cycle 6 (docs/qa-evidence/feature-86cb5y61z-reconnect-dispatch-state-machine-review-5.md,
    // F1): a SEPARATE dispatcher from `stopper` above, used only by the blank-config fall-through
    // below. `stopper` routes through VpnManager.stopVpn() -> OpenVpnService's full user-stop
    // teardown (startUserStopTeardown()), which unconditionally calls
    // ConnectionStateManager.updateState(DISCONNECTING) -- correct when the engine is still live
    // (the no-alternative path below, requestSwitchNow()'s full-cycle-exhausted branch), but wrong
    // here: the blank-config branch is only reached after the engine has ALREADY reported
    // LEVEL_NOTCONNECTED, so there is nothing left to tear down and no guaranteed further engine
    // event to resolve DISCONNECTING back to DISCONNECTED. `idleNotificationStopper` routes through
    // the pre-existing, already-tested VpnManager.stopControllerIfIdle() -> ACTION_STOP_IF_IDLE
    // path instead, which only clears the retained controller foreground notification and stops
    // the service -- it never mutates connection state (its only interaction with
    // ConnectionStateManager is reading state.value as a guard), so it cannot force the
    // DISCONNECTED -> DISCONNECTING -> (possible STOP_FAILED latch) sequence review-5 proved.
    // F3-6 (fix-cycle 7): "never touches ConnectionStateManager at all" was imprecise --
    // ACTION_STOP_IF_IDLE's handler reads ConnectionStateManager.state.value as a guard
    // (OpenVpnService.kt) and stopControllerIfIdle() reads it again (VpnManager.kt); both reads are
    // harmless. The accurate claim is "never mutates connection state / never calls updateState()".
    //
    // F3-9 (fix-cycle 9, PR #140 round 4 -- Copilot thread PRRT_kwDOONeEXM6e2p0f): typed
    // (Context) -> Boolean, matching `stopper` above, so the sole call site can no longer discard
    // the dispatch result silently. Copilot's REMEDY (bounded retry into a DISCONNECTING +
    // STOP_FAILED failure state, mirroring dispatchStopAfterStopRetryTimeout()) is deliberately NOT
    // adopted, and the reasoning is worth stating because it is the exact inverse of fix-cycle 8's:
    //
    //   * Copilot's premise -- "the engine may still be connecting" -- does not hold on this path.
    //     Reaching the blank-config fall-through REQUIRES the engine to have already reported
    //     LEVEL_NOTCONNECTED. DISCONNECTED is therefore the truthful state, and MainViewModel
    //     offering Connect is correct, not a defect: the tunnel really is down.
    //   * Forcing DISCONNECTING + STOP_FAILED here would re-open precisely the latch fix-cycle 6
    //     closed (review-5 F1), reporting a stop failure for an engine that already stopped.
    //   * A rejected ACTION_STOP_IF_IDLE therefore costs only the controller-service reap: the
    //     retained foreground notification and a lingering idle service. Connection state is
    //     unaffected because this dispatcher never mutates it.
    //
    // And that residue self-heals automatically, which is the question gate-5's F1-R8 adjudication
    // asked of the three sibling stopper() sites and answered with the 5-second snapshot re-poll.
    // This site has that same property, via a STRONGER mechanism -- verified by trace, not assumed:
    // OpenVpnService.trafficPollRunnable re-runs trySyncStatusSnapshot() whenever the AIDL push
    // channel has been quiet for >5s (OpenVpnService.kt, `now - lastStatusSnapshotMs > 5_000L`),
    // which re-delivers LEVEL_NOTCONNECTED into syncEngineState(). There, `reconnectPending` is
    // false (this branch cleared reconnectingHint before dispatching, and userInitiatedStart is
    // false), so syncEngineState() calls exitControllerForeground() DIRECTLY -- an in-process call,
    // not a startService() dispatch, so unlike the rejected ACTION_STOP_IF_IDLE it cannot itself be
    // refused. The notification clears regardless. The idle service is then reaped by
    // appLifecycleObserver.onStop()'s stopControllerIfIdle() and the one-shot-sync stop chain.
    // A bounded re-dispatch here would duplicate machinery that already runs unconditionally, so
    // the result is logged rather than retried -- the deliberate-ignore the `stopper` declaration
    // comment above allows for, now actually deliberate and recorded instead of invisible.
    internal var idleNotificationStopper: (Context) -> Boolean = { ctx -> VpnManager.stopControllerIfIdle(ctx) }
    // AC-3.3: Optional callback invoked when DEFAULT_V2 auto-switch is triggered but the
    // selected-country server list is empty. The callback must hydrate the list and then
    // invoke the provided completion action on the main thread.
    @Volatile internal var v2HydrationCallback: ((Context, () -> Unit) -> Unit)? = null
    @Volatile internal var probeRequestQueue: ProbeRequestQueue? = null
    @Volatile private var v2HydrationPending: Boolean = false
    @Volatile private var waitingStopForRetry: Boolean = false
    @Volatile private var pendingConfig: String? = null
    @Volatile private var pendingTitle: String? = null
    @Volatile private var cycleStartIndex: Int? = null
    private var stopRetryTimeoutRunnable: Runnable? = null
    // Pending re-dispatch of the stop-retry-timeout ACTION_STOP (see
    // dispatchStopAfterStopRetryTimeout). Held in a field, like every other posted Runnable in this
    // class, so cancel() can remove it deterministically -- a user-initiated stop
    // (cancelForUserStop()) dispatches its own ACTION_STOP and must not race a stale re-dispatch.
    private var timeoutStopDispatchRunnable: Runnable? = null
    // QG4-2 (fix-cycle 8, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-4.md): the
    // retry-commit dispatch used to be posted as an anonymous `handler.postDelayed({ ... }, ...)`
    // lambda, which cancel() could not reference and therefore could never remove. A user
    // Disconnect landing inside the START_AFTER_STOP_DELAY_MS window (cancelForUserStop() ->
    // cancel(resetCycle = true)) cleared waitingStopForRetry/pendingConfig but left that lambda
    // armed, so it still fired ~350ms later and (a) auto-reconnected the app despite the explicit
    // Disconnect, and (b) armed a fresh FGS-start obligation while the user-stop teardown was still
    // running toward OpenVpnService.finishStopFlowConfirmed()'s stopSelf(). Tracking the posted
    // Runnable in a field -- exactly like stopRetryTimeoutRunnable/runnable above -- lets cancel()
    // remove it deterministically, closing both the functional and crash-adjacent routes at once.
    // Both retry-commit call sites (NOTCONNECTED-observed and stop-retry-timeout) share this single
    // field: only one of the two can ever be in flight at a time (waitingStopForRetry is true
    // until whichever site fires first, and each site clears it before posting this Runnable --
    // R9-4, fix-cycle 9: the prior wording of this parenthetical had the polarity backwards).
    private var retryStartRunnable: Runnable? = null
    @Volatile private var retryCommitInFlight: Boolean = false
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

    fun onEngineLevel(
        appContext: Context,
        level: ConnectionStatus,
        source: String,
        wasConnectingAtDispatch: Boolean? = null
    ) {
        logEngineLevel(level, source)

        if (waitingStopForRetry) {
            if (level == ConnectionStatus.LEVEL_NOTCONNECTED) {
                val cfg = pendingConfig
                val title = pendingTitle
                pendingConfig = null
                pendingTitle = null
                waitingStopForRetry = false
                stopRetryTimeoutRunnable?.let { handler.removeCallbacks(it) }
                stopRetryTimeoutRunnable = null
                if (!cfg.isNullOrBlank()) {
                    AppLog.d(TAG, "Observed NOTCONNECTED after stop; starting next server")
                    try {
                        ConnectionStateManager.setReconnectingHint(true)
                        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "Failed to re-assert reconnect invariant before retry start (NOTCONNECTED path)", e)
                    }
                    retryStartRunnable?.let { handler.removeCallbacks(it) }
                    val r = Runnable {
                        retryStartRunnable = null
                        retryCommitInFlight = false
                        if (!starter(appContext, cfg, title, true)) rollBackFailedRetryDispatch()
                    }
                    retryStartRunnable = r
                    retryCommitInFlight = true
                    handler.postDelayed(r, START_AFTER_STOP_DELAY_MS.toLong())
                    return
                } else {
                    // A blank next.config falls through here instead of dispatching a doomed
                    // ACTION_START. No retry will be attempted, so this branch must complete the
                    // abort the same way the no-alternative path below does, not merely clear the
                    // hint: OpenVpnService.syncEngineState() computes reconnectPending and calls
                    // ConnectionStateManager.updateFromEngine() SYNCHRONOUSLY on the AIDL binder
                    // thread, before this deferred main-thread callback ever runs (this level was
                    // captured with reconnectingHint still true, so updateFromEngine() already
                    // latched the app state at CONNECTING and syncEngineState() already skipped
                    // exitControllerForeground()). Clearing only the hint afterward does not undo
                    // either of those -- nothing re-evaluates app state once the hint flips, and
                    // with no retry and no later engine level ever arriving, both the UI state and
                    // the controller notification would stay latched forever. Reset the cycle and
                    // force the state back to DISCONNECTED here, then clear the controller
                    // notification below.
                    // Copilot PR #140 review (thread PRRT_kwDOONeEXM6ef1jb).
                    cancel(resetCycle = true)
                    try {
                        ConnectionStateManager.setReconnectingHint(false)
                        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
                    } catch (e: Exception) {
                        AppLog.w(TAG, "Failed to reset state on blank-config fall-through", e)
                    }
                    // Fix-cycle 6 (review-5 F1): this branch is NOT a mirror of the no-alternative
                    // path below (requestSwitchNow()'s full-cycle-exhausted branch), despite an
                    // earlier fix-cycle claiming otherwise. On the no-alternative path the engine is
                    // still CONNECTING, so a full stopper()/ACTION_STOP dispatch is correct -- there
                    // is a live attempt to tear down and a genuine NOTCONNECTED follows to resolve
                    // OpenVpnService's startUserStopTeardown() DISCONNECTING transition. Here the
                    // engine has ALREADY reported LEVEL_NOTCONNECTED (that report is the precondition
                    // for reaching this branch at all), so there is nothing left to tear down and no
                    // guaranteed further engine event. Routing this through stopper() would force
                    // ConnectionStateManager.updateState(DISCONNECTING) unconditionally
                    // (startUserStopTeardown(), OpenVpnService.kt) -- allowedFromDisconnected DOES
                    // include DISCONNECTING (ConnectionState.kt), so that transition is ACCEPTED, not
                    // rejected as a no-op as previously (incorrectly) claimed -- and if the engine
                    // then declines the redundant stop dispatch, state can latch at DISCONNECTING
                    // with a spurious STOP_FAILED error instead of resolving back to DISCONNECTED.
                    // idleNotificationStopper() routes through the pre-existing, already-tested
                    // VpnManager.stopControllerIfIdle() -> ACTION_STOP_IF_IDLE path instead: it only
                    // clears the retained controller foreground notification (the state above is
                    // already correctly DISCONNECTED) and never mutates connection state (it only
                    // reads it as a guard), so it cannot reintroduce that latch.
                    try {
                        AppLog.d(TAG, "Requesting controller notification cleanup (blank-config fall-through)")
                        // F3-9 (fix-cycle 9, Copilot thread PRRT_kwDOONeEXM6e2p0f): inspect the
                        // dispatch result instead of discarding it. Deliberately logged, not
                        // retried, and deliberately NOT escalated into a stop-failure state -- see
                        // idleNotificationStopper's declaration comment for why this path's
                        // engine-confirmed-idle premise makes DISCONNECTED truthful here and why the
                        // only residue (a retained notification / lingering idle controller) is
                        // already healed unconditionally by syncEngineState()'s in-process
                        // exitControllerForeground() on the next snapshot re-poll.
                        if (!idleNotificationStopper(appContext)) {
                            AppLog.w(
                                TAG,
                                "Controller notification cleanup dispatch rejected (blank-config " +
                                    "fall-through); connection state stays DISCONNECTED (engine " +
                                    "already confirmed NOTCONNECTED) and the retained notification " +
                                    "is cleared by the next snapshot re-poll's exitControllerForeground()"
                            )
                        }
                    } catch (e: Exception) {
                        AppLog.w(TAG, "Failed to request controller notification cleanup on blank-config fall-through", e)
                    }
                }
            } else {
                AppLog.i(TAG, "Ignoring level=$level (source=$source) while waiting for stop-before-retry confirmation")
                return
            }
        }

        if (retryCommitInFlight) {
            AppLog.i(TAG, "Ignoring level=$level (source=$source) while retry-commit dispatch is in flight")
            return
        }

        if (level == ConnectionStatus.UNKNOWN_LEVEL) {
            scheduleIdleTolerance(appContext, level)
            return
        } else {
            cancelIdleTolerance()
        }

        val shouldSwitchImmediately =
            level == ConnectionStatus.LEVEL_AUTH_FAILED ||
                (source == "AIDL" && level == ConnectionStatus.LEVEL_NONETWORK)
        if (shouldSwitchImmediately) {
            val isConnecting = wasConnectingAtDispatch ?: try {
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

    /**
     * @return true only if a switch was actually begun. False means nothing was dispatched --
     *   auto-switch is off, or the stop command was rejected -- and the caller must not treat it as
     *   a recovery in progress. Callers that merely want best-effort behaviour may ignore it.
     */
    fun beginChainedSwitch(appContext: Context, config: String, title: String?): Boolean {
        if (!isEnabled(appContext)) {
            AppLog.d(TAG, "Auto-switch disabled; skipping chained switch")
            return false
        }
        applyTimeoutFromSettings(appContext)
        if (cycleStartIndex == null) {
            cycleStartIndex = runCatching { SelectedCountryStore.getCurrentIndex(appContext) }.getOrNull()
        }
        try { ConnectionStateManager.setReconnectingHint(true); AppLog.d(TAG, "reconnectHint=true (begin chained switch)") } catch (e: Exception) { AppLog.w(TAG, "Failed to set reconnecting hint for chained switch", e) }
        AppLog.i(TAG, "Begin chained switch (title=${title ?: "<none>"}, cfgLen=${config.length})")
        // F2-9: a chained switch is a fresh cycle, so any stop-retry-timeout re-dispatch left armed
        // by a PREVIOUS cycle must not survive into it -- otherwise it fires a non-preserve
        // ACTION_STOP mid-switch and cancelForUserStop() tears this cycle down. requestSwitchNow()'s
        // switch branch already gets this via its cancel(resetCycle = false); this entry point had
        // no equivalent. See cancelPendingStopDispatchForFreshStart().
        cancelPendingStopDispatchForFreshStart()
        cancelIdleTolerance()
        pendingConfig = config.takeIf { it.isNotBlank() }
        pendingTitle = title
        waitingStopForRetry = true
        scheduleStopRetryTimeout(appContext)
        return try {
            val dispatched = VpnManager.stopVpn(appContext, preserveReconnectHint = true)
            if (!dispatched) {
                AppLog.w(TAG, "Controller stop dispatch rejected for chained switch; aborting auto-switch")
                cancel(resetCycle = true)
                try { ConnectionStateManager.setReconnectingHint(false) } catch (e: Exception) { AppLog.w(TAG, "Failed to clear reconnecting hint after dispatch rejection", e) }
            }
            dispatched
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to request engine stop for chained switch", e)
            cancel(resetCycle = true)
            try { ConnectionStateManager.setReconnectingHint(false) } catch (ex: Exception) { AppLog.w(TAG, "Failed to clear reconnecting hint after stop exception", ex) }
            false
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
        if (timerActive || seconds > 0 || waitingStopForRetry) {
            AppLog.d(TAG, "Switch timer stopped at ${seconds}s (level=${timerLevel}, waitingStopForRetry=${waitingStopForRetry})")
        }
        timerActive = false
        timerLevel = null
        seconds = 0
        waitingStopForRetry = false
        pendingConfig = null
        pendingTitle = null
        stopRetryTimeoutRunnable?.let { handler.removeCallbacks(it) }
        stopRetryTimeoutRunnable = null
        timeoutStopDispatchRunnable?.let { handler.removeCallbacks(it) }
        timeoutStopDispatchRunnable = null
        retryStartRunnable?.let { handler.removeCallbacks(it) }
        retryStartRunnable = null
        retryCommitInFlight = false
        cancelIdleTolerance()
        _remainingSeconds.value = null
        v2HydrationPending = false
        if (resetCycle) {
            cycleStartIndex = null
        }
    }

    /**
     * Cancels any in-progress switch timer or stop-retry wait immediately, with no side
     * effects beyond stopping this object's own internal machinery (no reconnect attempt
     * is triggered). Call this from a genuine user/system-initiated stop teardown so an
     * already-running timer from a prior engine state cannot fire after the stop and
     * silently reconnect. Must be called from the main thread -- see onEngineLevel's
     * declaration comment on why this object's internal timer state assumes a single
     * (main-looper) caller. See PR #126 round 18 (Codex P1, comment 3736956722).
     */
    fun cancelForUserStop() {
        cancel(resetCycle = true)
    }

    /**
     * Drops a pending stop-retry-timeout re-dispatch (see [dispatchStopAfterStopRetryTimeout])
     * because a fresh connection attempt has superseded it.
     *
     * F2-9 (fix-cycle 9, PR #140 round 4, Codex thread PRRT_kwDOONeEXM6e2oec): when the
     * blank-config timeout's ACTION_STOP is rejected, this class arms a re-dispatch
     * [TIMEOUT_STOP_DISPATCH_RETRY_DELAY_MS] later. [cancel] removes it, but NO fresh-start path
     * was calling [cancel]: `OpenVpnService.onStartCommand()`'s ACTION_START branch touches none of
     * this object's state, and [beginChainedSwitch] arms a new cycle without clearing the old
     * re-dispatch. So a user who tapped Connect inside that one-second window (typically right
     * after returning to the foreground, which is also what lifts the background-start restriction
     * that caused the rejection) got the retry firing a real, non-preserve ACTION_STOP against the
     * connection they had just started -- tearing it down via startUserStopTeardown(), whose
     * cancelForUserStop() then also killed the new cycle.
     *
     * Deliberately narrow: it clears ONLY [timeoutStopDispatchRunnable], never the switch timer or
     * [cycleStartIndex]. Calling full [cancel] from ACTION_START would reset the cycle start index
     * on every auto-switch retry commit (which reaches ACTION_START through [starter]), making
     * nextServerCircular()'s wrap detection give up early -- the exact defect
     * [rollBackFailedRetryDispatch]'s comment describes.
     *
     * Dropping the pending stop is the correct resolution, not merely the safe one: ACTION_START
     * already performs the equivalent teardown for the superseded stop (userInitiatedStop = false,
     * the stop retry/confirmation/bind runnables removed, `clearStopFailure()`, and the persisted
     * pending-stop intent cleared), so nothing is left owing a stop once a start commits.
     *
     * Must be called from the main thread, like every other entry point on this object.
     */
    fun cancelPendingStopDispatchForFreshStart() {
        val pending = timeoutStopDispatchRunnable ?: return
        handler.removeCallbacks(pending)
        timeoutStopDispatchRunnable = null
        AppLog.i(
            TAG,
            "Cancelled pending stop-retry-timeout re-dispatch: a fresh connection start supersedes it"
        )
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

        if (v2HydrationPending) {
            AppLog.d(TAG, "DEFAULT_V2: hydration pending, ignoring switch request (level=${level})")
            return
        }

        val title = SelectedCountryStore.getSelectedCountry(appContext)
        val total = try { SelectedCountryStore.getServers(appContext).size } catch (e: Exception) { AppLog.w(TAG, "Failed to get server count", e); -1 }
        val failingServerId = if (level != ConnectionStatus.LEVEL_NONETWORK) {
            SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(appContext)
        } else 0
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
                    if (failingServerId != 0) {
                        try { probeRequestQueue?.enqueue(failingServerId) } catch (e: Exception) {
                            AppLog.w(TAG, "DEFAULT_V2: failed to enqueue hardprobe for serverId=$failingServerId", e)
                        }
                    }
                    v2HydrationPending = true
                    callback(appContext) {
                        handler.post {
                            if (!v2HydrationPending) {
                                AppLog.d(TAG, "DEFAULT_V2: hydration callback received but state was reset, skipping retry")
                                return@post
                            }
                            v2HydrationPending = false
                            val hydratedTotal = try { SelectedCountryStore.getServers(appContext).size } catch (_: Exception) { 0 }
                            if (hydratedTotal > 0) {
                                AppLog.i(TAG, "DEFAULT_V2: hydration complete ($hydratedTotal servers), starting from first server")
                                SelectedCountryStore.resetIndex(appContext)
                                cycleStartIndex = null
                                cancel(resetCycle = false)
                                val firstServer = try { SelectedCountryStore.currentServer(appContext) } catch (_: Exception) { null }
                                val hydrationTitle = SelectedCountryStore.getSelectedCountry(appContext)
                                if (firstServer != null && !firstServer.config.isNullOrBlank()) {
                                    beginChainedSwitch(appContext, firstServer.config!!, hydrationTitle)
                                } else {
                                    AppLog.w(TAG, "DEFAULT_V2: hydration complete but server config is blank after reset, stopping engine")
                                    cancel(resetCycle = true)
                                    try { ConnectionStateManager.setReconnectingHint(false) } catch (e: Exception) { AppLog.w(TAG, "Failed to reset reconnecting hint after hydration no-server", e) }
                                    try { ConnectionStateManager.updateState(ConnectionState.DISCONNECTED) } catch (e: Exception) { AppLog.w(TAG, "Failed to reset state after hydration no-server", e) }
                                    try { stopper(appContext) } catch (e: Exception) { AppLog.w(TAG, "Failed to stop engine after hydration no-server", e) }
                                }
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

        // Enqueue hardprobe for the failing server — skip for LEVEL_NONETWORK (device network loss,
        // not server failure) and id=0 (unknown server, no useful probe target).
        if (failingServerId != 0) {
            try { probeRequestQueue?.enqueue(failingServerId) } catch (e: Exception) { AppLog.w(TAG, "Failed to enqueue hardprobe for serverId=$failingServerId", e) }
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
                pendingConfig = next.config.takeIf { it.isNotBlank() }
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
            // Unlike the stop-retry-timeout branch, this path reaches here with the engine still
            // CONNECTING and still emitting levels, so a rejected dispatch is recoverable from the
            // next engine event or an explicit user stop; it is logged rather than retried here.
            if (!stopper(appContext)) {
                AppLog.w(TAG, "Controller stop dispatch rejected (no-alternative path)")
            }
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

    // Rolls back CONNECTING re-assertion when a retry dispatch fails (e.g. FGS-from-background
    // restriction). Also resets the switch cycle (cancel(resetCycle = true)): without this,
    // cycleStartIndex survived the aborted attempt, so the next auto-switch cycle's
    // nextServerCircular wrap detection was evaluated against a stale start index and could
    // believe the country's server list had already been exhausted, giving up earlier than it
    // should. Both call sites already run on the main looper (posted via this class's handler),
    // matching cancel()'s single-main-looper-caller invariant.
    private fun rollBackFailedRetryDispatch() {
        AppLog.w(TAG, "Retry-commit ACTION_START dispatch failed; rolling back CONNECTING re-assertion")
        cancel(resetCycle = true)
        try {
            ConnectionStateManager.setReconnectingHint(false)
            ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to roll back reconnect invariant after failed retry dispatch", e)
        }
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
            if (!cfg.isNullOrBlank()) {
                try {
                    ConnectionStateManager.setReconnectingHint(true)
                    ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to re-assert reconnect invariant before retry start (timeout path)", e)
                }
                retryStartRunnable?.let { handler.removeCallbacks(it) }
                val startR = Runnable {
                    retryStartRunnable = null
                    retryCommitInFlight = false
                    if (!starter(appContext, cfg, title, true)) rollBackFailedRetryDispatch()
                }
                retryStartRunnable = startR
                retryCommitInFlight = true
                handler.postDelayed(startR, START_AFTER_STOP_DELAY_MS.toLong())
            } else {
                // F1-6 (fix-cycle 7, docs/qa-evidence/feature-86cb5y61z-reconnect-dispatch-state-
                // machine-review-6.md): this is the TIMEOUT TWIN of the blank-config fall-through in
                // onEngineLevel() above (fixed in fix-cycle 6) -- both are reached with
                // pendingConfig == null after `config.takeIf { it.isNotBlank() }`, but this one fires
                // because NOTCONNECTED never arrived within STOP_RETRY_TIMEOUT_MS, not because it
                // did. Before this fix the branch only logged, so reconnectingHint stayed true,
                // state stayed CONNECTING (the stop was dispatched with preserveReconnectHint = true,
                // so ACTION_STOP took the "preserve" branch and never ran startUserStopTeardown()),
                // and the controller FGS notification was retained -- with no retry in flight and no
                // further event able to resolve any of it. The identical permanent latch this whole
                // fix cycle exists to close, just reached via the timeout path instead of
                // NOTCONNECTED.
                //
                // This branch must NOT reuse the NOTCONNECTED sibling's idleNotificationStopper()
                // remedy. That remedy's entire premise is that the engine has ALREADY reported
                // LEVEL_NOTCONNECTED, so forcing state to DISCONNECTED matches reality, and
                // idleNotificationStopper() -> ACTION_STOP_IF_IDLE dispatches no engine stop at all
                // (VpnManager.stopControllerIfIdle() only clears the notification and stops the
                // controller service -- it guards on ConnectionStateManager already being
                // DISCONNECTED before it even builds the intent). Here that premise does not hold:
                // the timeout fired precisely because there is no confirmation the engine ever
                // stopped, and the earlier preserve-branch stop armed none of OpenVpnService's
                // confirmation-timeout/retry machinery (that machinery only exists on the
                // startUserStopTeardown() path). Forcing ConnectionStateManager to DISCONNECTED and
                // only clearing the notification here could both lie about a possibly still-live
                // tunnel and stopSelf() a service that may still own a real VPN interface.
                //
                // Instead this mirrors requestSwitchNow()'s no-alternative path below (stopper() /
                // ACTION_STOP, non-preserve): treat the engine as potentially still live and dispatch
                // a REAL stop. startUserStopTeardown() force-sets DISCONNECTING, genuinely requests
                // the engine to stop, and exitControllerForeground() clears the notification
                // unconditionally at ACTION_STOP entry regardless of which branch runs next -- and,
                // unlike the already-dispatched preserve-branch stop, it arms OpenVpnService's own
                // confirmation-timeout/retry machinery (STOP_CONFIRMATION_TIMEOUT_MS,
                // STOP_DISPATCH_MAX_ATTEMPTS). The outcome is always bounded: a genuine NOTCONNECTED
                // resolves it to DISCONNECTED, or repeated engine silence resolves it to the
                // documented STOP_FAILED error state -- never a silent permanent latch.
                //
                // F1-8 (fix-cycle 8, PR #140 round 3: Codex 3922158403, Kody 3922135864): the stop
                // is now dispatched FIRST and its result inspected, and only a dispatch that
                // actually reached the controller settles the app to DISCONNECTED -- see
                // dispatchStopAfterStopRetryTimeout(). Forcing DISCONNECTED up front (as this
                // branch did before) discarded stopper()'s Boolean entirely, so a
                // startService()-rejected stop still reported a possibly live tunnel as
                // disconnected, with no stop pending in this class and none armed in
                // OpenVpnService either.
                AppLog.w(TAG, "Stop retry timeout; missing pending config")
                cancel(resetCycle = true)
                try {
                    ConnectionStateManager.setReconnectingHint(false)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to clear reconnecting hint on stop-retry timeout with blank config", e)
                }
                dispatchStopAfterStopRetryTimeout(appContext, attempt = 1)
            }
        }
        stopRetryTimeoutRunnable = r
        handler.postDelayed(r, STOP_RETRY_TIMEOUT_MS)
    }

    /**
     * Dispatches the real ACTION_STOP for the stop-retry-timeout blank-config branch and settles
     * app state according to what actually happened to that dispatch (F1-8, fix-cycle 8, PR #140
     * round 3: Codex 3922158403 / Kody 3922135864).
     *
     * Fix-cycle 7's choice of dispatcher is unchanged and still correct: this path has no engine
     * confirmation of idleness, so it must use the real [stopper] (ACTION_STOP ->
     * OpenVpnService.startUserStopTeardown(), which genuinely asks the engine to stop and arms the
     * service's own confirmation-timeout/retry machinery), not the NOTCONNECTED sibling's
     * notification-only [idleNotificationStopper]. What changes here is only how the dispatch
     * RESULT is handled:
     *
     * - dispatched (`true`): the controller has the stop, so startUserStopTeardown() owns the
     *   outcome from here -- and this method publishes NO state of its own. F1-9 (fix-cycle 9,
     *   PR #140 round 4, Kody thread PRRT_kwDOONeEXM6e2mam): fix-cycle 8 settled DISCONNECTED
     *   immediately on the dispatch acknowledgment, but `stopper()` is
     *   VpnManager.stopVpn() -> Context.startService(), which returns true the moment the intent is
     *   ACCEPTED for delivery -- not when OpenVpnService receives it. onStartCommand()'s ACTION_STOP
     *   branch runs later on the main looper, and only then does startUserStopTeardown() set
     *   DISCONNECTING. So the old ordering published a false idle/disconnected state for the whole
     *   asynchronous delivery window: every ConnectionStateManager consumer (MainViewModel renders
     *   DISCONNECTED as "ready to Connect") could observe a tunnel that is still being torn down as
     *   already down. Returning without settling leaves state where the caller left it (CONNECTING),
     *   which is accurate -- a stop is genuinely in flight -- until the service reports otherwise.
     *
     *   This does NOT reopen the permanent CONNECTING latch this branch exists to close, and that
     *   was verified by trace rather than assumed: on an accepted non-preserve ACTION_STOP,
     *   OpenVpnService.startUserStopTeardown() calls
     *   ConnectionStateManager.updateState(DISCONNECTING) UNCONDITIONALLY (outside its
     *   `if (!userInitiatedStop || forceReset)` guard, and CONNECTING -> DISCONNECTING is in
     *   ConnectionState.kt's allowedFromConnecting), then requestStopIcsOpenVpn() arms
     *   STOP_CONFIRMATION_TIMEOUT_MS / STOP_DISPATCH_MAX_ATTEMPTS. Every continuation from there is
     *   terminal and observable: finishStopFlowConfirmed() -> DISCONNECTED, or markStopFailure() ->
     *   STOP_FAILED. There is no path on which an accepted dispatch publishes nothing.
     * - rejected (`false`, or a throw): the intent never reached OpenVpnService, so no teardown and
     *   no service-side retry exist. Re-dispatch up to [TIMEOUT_STOP_DISPATCH_MAX_ATTEMPTS] times
     *   ([TIMEOUT_STOP_DISPATCH_RETRY_DELAY_MS] apart -- the same bounded shape as
     *   OpenVpnService.scheduleStopRetryOrFail()), and if every attempt is rejected surface
     *   DISCONNECTING + [ConnectionStateManager.VpnError.STOP_FAILED] -- markStopFailure()'s exact
     *   end state, the only pairing ConnectionControlsPresenter renders as the stop-failed status,
     *   and one that keeps the ACTIVE stop button so the user can retry by hand. Never DISCONNECTED:
     *   a tunnel nobody has managed to stop must not be reported as stopped.
     *
     * Always runs on the main looper (posted through this class's [handler]), matching the
     * single-main-looper-caller invariant the rest of this object assumes.
     */
    private fun dispatchStopAfterStopRetryTimeout(appContext: Context, attempt: Int) {
        timeoutStopDispatchRunnable?.let { handler.removeCallbacks(it) }
        timeoutStopDispatchRunnable = null
        val dispatched = try {
            AppLog.d(
                TAG,
                "Requesting explicit engine stop (stop-retry timeout, no engine confirmation, attempt=$attempt)"
            )
            stopper(appContext)
        } catch (e: Exception) {
            AppLog.w(
                TAG,
                "Failed to request engine stop on stop-retry timeout with blank config (attempt=$attempt)",
                e
            )
            false
        }
        if (dispatched) {
            // F1-9: publish nothing here. The controller now owns the outcome and will publish
            // DISCONNECTING -> DISCONNECTED (or STOP_FAILED) itself; anticipating that with a
            // DISCONNECTED of our own would be a false idle report for the delivery window. See
            // this method's KDoc.
            AppLog.i(
                TAG,
                "Engine stop dispatch accepted on stop-retry timeout (attempt=$attempt); " +
                    "leaving teardown state to OpenVpnService's user-stop confirmation flow"
            )
            return
        }
        if (attempt < TIMEOUT_STOP_DISPATCH_MAX_ATTEMPTS) {
            AppLog.w(
                TAG,
                "Engine stop dispatch rejected on stop-retry timeout (attempt=$attempt); " +
                    "retrying in ${TIMEOUT_STOP_DISPATCH_RETRY_DELAY_MS}ms"
            )
            val retry = Runnable {
                timeoutStopDispatchRunnable = null
                dispatchStopAfterStopRetryTimeout(appContext, attempt + 1)
            }
            timeoutStopDispatchRunnable = retry
            handler.postDelayed(retry, TIMEOUT_STOP_DISPATCH_RETRY_DELAY_MS)
            return
        }
        AppLog.e(
            TAG,
            "Engine stop dispatch rejected on stop-retry timeout after $attempt attempts; " +
                "surfacing STOP_FAILED instead of reporting an unconfirmed tunnel as disconnected"
        )
        try {
            ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)
            ConnectionStateManager.setStopFailure()
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to surface stop failure after exhausted stop dispatch", e)
        }
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

    @JvmStatic
    fun setProbeRequestQueueForTest(queue: ProbeRequestQueue?) {
        probeRequestQueue = queue
    }

    @JvmStatic
    fun resetForTest() {
        cancel(resetCycle = true)
    }

    // Read-only test seam for the F11 regression coverage (rollBackFailedRetryDispatch() must
    // reset the switch cycle): cycleStartIndex has no other externally observable effect until a
    // whole second auto-switch cycle plays out, which unit tests should not need to drive just to
    // assert this one field.
    @JvmStatic
    fun cycleStartIndexForTest(): Int? = cycleStartIndex

    // Read-only test seam for the F2-9 regression coverage. Whether a stop-retry-timeout
    // re-dispatch is still armed is otherwise only observable by letting it fire, which is exactly
    // what the fix prevents -- so asserting on the fire alone cannot distinguish "cancelled" from
    // "not yet due".
    @JvmStatic
    fun hasPendingStopDispatchForTest(): Boolean = timeoutStopDispatchRunnable != null
}



