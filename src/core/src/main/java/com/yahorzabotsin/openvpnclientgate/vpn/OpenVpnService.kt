package com.yahorzabotsin.openvpnclientgate.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.Handler
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.yahorzabotsin.openvpnclientgate.core.ApiConstants
import com.yahorzabotsin.openvpnclientgate.core.logging.AppLog
import com.yahorzabotsin.openvpnclientgate.core.BuildConfig
import com.yahorzabotsin.openvpnclientgate.core.R
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOption
import com.yahorzabotsin.openvpnclientgate.core.dns.DnsOptions
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import com.yahorzabotsin.openvpnclientgate.core.servers.ServersV2SyncCoordinator
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import de.blinkt.openvpn.core.ConfigParser.ConfigParseError
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import de.blinkt.openvpn.core.IServiceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import de.blinkt.openvpn.core.IStatusCallbacks
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.ui.main.MainSelectionInteractor
import de.blinkt.openvpn.core.TrafficHistory
import de.blinkt.openvpn.core.StatusSnapshot
import com.yahorzabotsin.openvpnclientgate.core.filter.AppFilterStore
import com.yahorzabotsin.openvpnclientgate.core.servers.probe.ProbeRequestQueue
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class OpenVpnService : Service(), VpnStatus.StateListener, VpnStatus.LogListener, VpnStatus.ByteCountListener {

    internal companion object {
        private const val ENGINE_ACTION_PAUSE_VPN = "de.blinkt.openvpn.PAUSE_VPN"
        private const val ENGINE_ACTION_RESUME_VPN = "de.blinkt.openvpn.RESUME_VPN"

        private val TAG = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ':' + "OpenVpnService"
        const val DEFAULT_COMPAT_MODE = 20400
        const val KEY_OVPN3 = "ovpn3"
        const val KEY_DISABLE_CONFIRMATION = "disableconfirmation"
        private val AUTO_SWITCH_LEVELS = setOf(
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_AUTH_FAILED
        )
        private val STOP_TERMINAL_LEVELS = setOf(
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.UNKNOWN_LEVEL
        )
        private val numberRegex = Regex("\\d+")
        private val ipv4Regex = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b")
        private val urlRegex = Regex("\\bhttps?://\\S+\\b")
        private val hexRegex = Regex("\\b[0-9a-fA-F]{8,}\\b")
        private const val MAX_THROTTLE_KEY_LENGTH = 96
        private const val ONE_SHOT_STOP_DELAY_MS = 1_000L
        // Buffer between "decided to stop after a passive status sync" and the actual
        // stopSelf() call, re-running the same guard checks (userInitiatedStart/Stop,
        // != DISCONNECTED) immediately before the deferred stopSelf() actually fires. On its own
        // this buffer does NOT close the underlying AMS "bringing down service while still waiting
        // for start foreground" crash window -- it only narrows the specific 1000ms-arrival slice
        // of it and, taken alone, would simply relocate the same-width hazard to ~1000-1400ms after
        // the sync (quality-gate finding G1, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-2.md).
        // The actual closure has two parts, one per production ACTION_START dispatcher (review-4
        // F1, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-4.md):
        // isAppForegroundVisible() in stopAfterOneShotSyncConfirmedRunnable excludes the UI
        // dispatcher (stopSelf() is never called while any activity is started -- see
        // VpnManager.startVpn()'s MainActivityCore.kt caller); the
        // != ConnectionState.DISCONNECTED state guard excludes ServerAutoSwitcher's background
        // retry-timer dispatcher, which is not gated by UI visibility at all -- reconnectingHint
        // holds state at CONNECTING for the whole auto-switch stop-to-start gap. This buffer still
        // earns its keep on top of both guards: it catches the narrower
        // case where stopAfterOneShotSyncRunnable's own ONE_SHOT_STOP_DELAY_MS timer and a fresh
        // ACTION_START's removeCallbacks(stopAfterOneShotSyncRunnable) call land on the main-thread
        // looper at nearly the same tick, so that a stage-1 "decided to stop" that already started
        // executing still gets one more chance to see userInitiatedStart=true before the real
        // stopSelf() fires. Device-reproduced at a ~1058ms gap; see
        // docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-qa.md ADDENDUM. Value is
        // clearly longer than realistic same-tick IPC/looper jitter but short enough not to
        // meaningfully delay legitimate idle cleanup.
        private const val ONE_SHOT_STOP_CONFIRM_DELAY_MS = 400L
        // Fix-cycle 13 (86cb35fbt, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-qa-4.md
        // section 2): manual QA round 4 device-reproduced a genuine, previously-undiscovered
        // RemoteServiceException$ForegroundServiceDidNotStartInTimeException in the ENGINE's OWN
        // de.blinkt.openvpn.core.OpenVPNService (a different class, process (:openvpn), and
        // manifest from this controller -- not the crash class this flow's prior 12 fix cycles
        // addressed). Repro: connect to a live multi-server country, background the app, toggle
        // airplane mode ON ~10s then OFF while backgrounded, with status_stall_timeout_seconds set
        // low -- the resulting rapid auto-switch stop/retry churn crashed the engine's own service.
        //
        // Root cause: VPNLaunchHelper.startOpenVpn() calls Context.startForegroundService()
        // directly against the engine's OpenVPNService on every ACTION_START this controller
        // forwards to it (see startIcsOpenVpn() below). The engine's own onStartCommand() only
        // calls Service.startForeground() when !foregroundNotificationVisible() -- i.e. it assumes
        // an already-visible notification means the fresh startForegroundService() obligation this
        // NEW call just registered with AMS is already satisfied. Android does not actually work
        // that way: the FGS-start deadline is armed per startForegroundService() call, not
        // satisfied merely by some notification happening to still be on screen. That assumption
        // breaks when the PREVIOUS session's own teardown (OpenVPNThread's exit path ->
        // OpenVPNService.openvpnStopped() -> endVpnService() -> stopForeground()+stopSelf(), which
        // runs off the engine's main thread) is still landing at AMS at the moment this fresh
        // startForegroundService() call is made -- e.g. under the extra scheduling pressure of
        // airplane-mode-driven connectivity churn. ServerAutoSwitcher's own START_AFTER_STOP_DELAY_MS
        // (350ms) already spaces the ACTION_START dispatch to THIS controller after observing the
        // engine's NOTCONNECTED confirmation; this buffer adds further headroom at the boundary
        // closest to the actual fault -- immediately before the call that reaches into the engine
        // process -- giving that async teardown materially more real time to land before the next
        // FGS obligation is armed. Applied ONLY to reconnect dispatches (isReconnect == true, i.e.
        // auto-switch retries, which are the only ACTION_START callers that could possibly be
        // racing a stop they themselves just issued): a fresh user-initiated Connect tap has no
        // preceding stop to race, so it is dispatched immediately as before -- this buffer must not
        // add latency to that path. See the ACTION_START handler below for the guarded dispatch
        // (re-checks userInitiatedStop/serviceDestroyed/connectionAttemptGeneration immediately
        // before firing, mirroring dispatchAutoSwitcherOnEngineLevel's established pattern, so a
        // genuine stop or a newer attempt landing inside this window supersedes it cleanly instead
        // of racing ServerAutoSwitcher's own retryCommitInFlight/rollBackFailedRetryDispatch
        // machinery).
        //
        // R14-4 (docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-14.md): this value
        // is an explicitly UNVALIDATED, probabilistic constant, not a measured or provable bound.
        // No measurement of the engine's actual teardown duration (openvpnStopped() ->
        // endVpnService() -> stopForeground()+stopSelf() landing at AMS) exists anywhere in this
        // flow's evidence. The only measured timing this flow ever captured (.sdlc/status.json
        // defect entry, 2026-08-11: "5/5 short-gap (300-460 ms) trials ... passed", "only the
        // ~1000 ms+ region is dangerous") describes a DIFFERENT mechanism -- the controller's own
        // stopAfterOneShotSyncRunnable race -- and does not transfer to the engine's teardown. 500ms
        // widens the timing margin but cannot provably eliminate the race. The deterministic
        // alternative -- observing the engine service's actual death via the existing
        // engineConnection ServiceConnection.onServiceDisconnected callback (or a binder
        // DeathRecipient), which the controller already binds to in requestStopIcsOpenVpn() -- was
        // assessed and deferred as a larger, legitimate follow-up rather than implemented here. If
        // the target crash recurs in the field, prefer that deterministic signal over further
        // tuning this constant.
        private const val ENGINE_RECONNECT_DISPATCH_BUFFER_MS = 500L
        private const val ONE_SHOT_SYNC_TIMEOUT_MS = 15_000L
        private const val CONTROLLER_NOTIFICATION_ID = 7014
        private const val PAUSE_CONFIRMATION_TIMEOUT_MS = 3_000L
        private const val RESUME_CONFIRMATION_TIMEOUT_MS = 5_000L
        private const val STOP_DISPATCH_MAX_ATTEMPTS = 3
        private const val STOP_DISPATCH_RETRY_DELAY_MS = 1_000L
        private const val STOP_CONFIRMATION_TIMEOUT_MS = 8_000L
        private const val STOP_BIND_TIMEOUT_MS = 2_000L
        private const val STOP_PREFS_NAME = "vpn_stop_teardown"
        private const val PREF_PENDING_STOP_INTENT = "pending_stop_intent"
        private const val PREF_STOP_FAILURE_COUNT = "stop_failure_count"
        private const val PREF_STOP_STALE_RECONCILE_COUNT = "stop_stale_reconcile_count"
        private const val WATCHDOG_POLL_INTERVAL_MS = 2_000L
        private const val WATCHDOG_MIN_TRAFFIC_DELTA_BYTES = 256L
        private const val WATCHDOG_PROBE_TIMEOUT_MS = 2_000
        private const val WATCHDOG_FAILURE_THRESHOLD = 3
        private const val WATCHDOG_RECOVERY_COOLDOWN_MS = 15_000L
        private const val WATCHDOG_CONNECTED_WARMUP_MS = 10_000L
        private const val WATCHDOG_MAX_RECOVERY_ATTEMPTS = 3
        private const val WATCHDOG_FALLBACK_HTTPS_PORT = 443
        private const val WATCHDOG_DEFAULT_OPENVPN_PORT = 1194

        // R11-1 (fix-cycle 11, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-11.md):
        // whether ANY OpenVpnService instance is currently alive (there is only ever one at a
        // time; Android guarantees onDestroy() of a prior instance completes before a new one's
        // onCreate() begins). Set true as the first statement of onCreate() and false as the
        // first statement of onDestroy(), mirroring serviceDestroyed's own "set before anything
        // else" placement. Exists so VpnManager.scheduleIdleRecheckAfterFailedStartDispatch()'s
        // delayed re-check can tell "no controller currently exists" apart from "a controller
        // exists and might be idle" BEFORE calling stopControllerIfIdle() -- which, called with
        // no live instance, ends in context.startService() and therefore CREATES a brand-new
        // OpenVpnService just to immediately tear it down. That brand-new instance's onCreate()
        // unconditionally calls enterControllerForeground() (load-bearing for the genuine
        // ACTION_START FGS obligation -- see that method's own declaration comment below), so the
        // phantom instance issues a real, user-visible "Establishing secure connection..."
        // notification for a start that never actually landed. This restores, for the new
        // VpnManager caller, the same invariant appLifecycleObserver already documents for the
        // pre-existing stopControllerIfIdle() caller: "never spins up a new instance just to
        // immediately stop it" (see that field's declaration comment a few hundred lines below).
        @Volatile
        internal var isInstanceAlive: Boolean = false
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Track engine binding for start/stop coordination
    private var engineBinder: IOpenVPNServiceInternal? = null
    private var boundToEngine = false

    // Remember whether start/stop were user-driven vs auto-switch.
    // @Volatile: written on the main thread (onStartCommand / startUserStopTeardown),
    // read on the AIDL binder thread (IStatusCallbacks.Stub.updateStateString →
    // syncEngineState / shouldIgnoreLevelAfterUserStop / handleEngineLevelForStop).
    // Without @Volatile the JVM may cache stale values in the binder thread's register/cache,
    // causing the FGS guard or stop-flow checks to act on outdated state.
    @Volatile private var userInitiatedStart = false
    @Volatile private var userInitiatedStop = false
    // Distinct from userInitiatedStop above: userInitiatedStop models "the user asked to
    // disconnect", but this Service instance keeps running (onDestroy() is not called) and can
    // reconnect -- see onStartCommand()/ACTION_START, which clears userInitiatedStop and reuses
    // the same instance. serviceDestroyed models "this Service instance is being torn down" and
    // is set exactly once, as the very first statement in onDestroy(), before any teardown step
    // (including the autoSwitchDispatchToken sweep and unregisterStatusCallback()) runs. A new
    // connection after a full stop (stopSelf()) creates a brand-new OpenVpnService instance with
    // this flag freshly false, so it never needs resetting.
    // @Volatile: written on the main thread (onDestroy) and read on the AIDL binder thread
    // (dispatchAutoSwitcherOnEngineLevel, invoked from updateStateString) to close the TOCTOU
    // window where an in-flight binder callback -- already past this check but not yet at the
    // postAtTime() enqueue when round-6's code ran -- could enqueue a fresh auto-switch dispatch
    // after the removeCallbacksAndMessages(autoSwitchDispatchToken) sweep already ran and after
    // unregisterStatusCallback() should have silenced it. See PR #126 review thread (round 7,
    // Codex P2, follow-up to the shared-token fix).
    @Volatile private var serviceDestroyed = false
    @Volatile private var ignoreConnectedUntilNotConnected = false
    // Same cross-thread visibility requirement as above: stopRequestId/stopStartedAtMs are
    // written on the main thread (startUserStopTeardown) and read on the AIDL binder thread
    // (syncEngineState via maybeStartStaleStopReconciliation).
    @Volatile private var stopRequestId: String? = null
    @Volatile private var stopStartedAtMs: Long = 0L
    private var stopAttempt: Int = 0
    private var stopAwaitingConfirmation: Boolean = false
    private var stopBindPending: Boolean = false
    private var stopLastFailureReason: String? = null
    private val stopPrefs: SharedPreferences by lazy {
        getSharedPreferences(STOP_PREFS_NAME, MODE_PRIVATE)
    }

    // Suppress duplicate engine state callbacks while we manage retries
    private var suppressEngineState = true

    // Track per-session auto-switch attempts
    private var sessionTotalServers: Int = -1
    private var sessionAttempt: Int = 0
    // Timestamp (via watchdogNowMs()) marking when the CURRENT connection attempt began -- set
    // on every ACTION_START (fresh start or auto-switch reconnect), alongside sessionAttempt.
    // Written and read on the main thread only (onStartCommand / applyStatusSnapshot), so no
    // @Volatile is required here (unlike the binder-thread-written fields above/below).
    // Used by applyStatusSnapshot() to distinguish a cached snapshot that PREDATES this attempt
    // (genuinely stale/irrelevant leftover from a past, different attempt -- round 8's
    // scenario) from one that IS reporting on this still-ongoing attempt, just old because the
    // attempt itself has been stuck the whole time (round 9's scenario: must NOT be rejected,
    // or ServerAutoSwitcher never gets a chance to run). See PR #126 round 9 (Codex P2, comment
    // 3733934640).
    private var currentAttemptStartMs: Long = 0L
    // Paired with currentAttemptStartMs above using SystemClock.elapsedRealtime() (device-uptime
    // based; immune to wall-clock corrections such as automatic NTP sync or a manual/system
    // clock change). Recorded at the same moments as currentAttemptStartMs -- every ACTION_START
    // and the round-15 ACTION_SYNC_STATUS backfill below -- so applyStatusSnapshot() can tell a
    // genuine backward wall-clock jump during the current attempt apart from a snapshot that
    // truly predates it: if the device wall clock is corrected backward after currentAttemptStartMs
    // is captured, every later snapshot's wall-clock timestampMs reads earlier than
    // currentAttemptStartMs even though real (monotonic) time keeps moving forward, so the
    // existing predates-check alone would reject every snapshot from the current attempt forever.
    // See PR #126 round 16 (Codex P2, comment 3735937824).
    // Left at its default 0L by any path that sets currentAttemptStartMs without also setting
    // this field (e.g. pre-round-16 unit tests using reflection to set currentAttemptStartMs
    // directly) -- the safety net in applyStatusSnapshot() is gated on this being > 0L so an
    // unset baseline degrades to the exact pre-round-16 wall-clock-only behavior instead of
    // comparing against a meaningless value.
    private var currentAttemptStartElapsedRealtimeMs: Long = 0L
    // Monotonically-increasing counter, bumped on every ACTION_START (fresh start or
    // auto-switch reconnect), alongside currentAttemptStartMs above. Narrower and additive to
    // currentAttemptStartMs/serviceDestroyed: those two solve their own specific problems
    // (snapshot staleness, instance teardown) and are left as-is. This counter exists solely to
    // close a stop-then-restart race in dispatchAutoSwitcherOnEngineLevel() where the SAME
    // service instance is reused (serviceDestroyed never becomes true) --
    // 1) an old AIDL binder callback carrying a stale level passes the serviceDestroyed check
    //    and captures the generation valid at that moment;
    // 2) the main thread runs a user-stop sweep (startUserStopTeardown), cancelling queued
    //    dispatches via autoSwitchDispatchToken;
    // 3) the binder thread's postAtTime() call enqueues its deferred dispatch AFTER the sweep
    //    already ran, so the sweep never saw it;
    // 4) a fresh ACTION_START arrives, reusing the same instance, and clears userInitiatedStop
    //    back to false as part of starting the new attempt;
    // 5) the queued runnable from step 3 executes: its userInitiatedStop re-check (round 5) now
    //    sees false -- cleared by the NEW start in step 4 -- so it does NOT skip, and the stale
    //    callback would otherwise proceed to switch away from the brand-new attempt.
    // Comparing the generation captured in step 1 against the live value at execution time
    // (step 5) closes this gap: a mismatch means a newer attempt has started since this
    // dispatch was queued, so it is skipped unconditionally, regardless of what
    // userInitiatedStop currently reads (unreliable for this specific race, per above).
    // R20-1 (fix-cycle 21, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-20.md):
    // a plain @Volatile Int is NOT enough here. @Volatile only guarantees visibility of the most
    // recently written value, not atomicity of the read-modify-write `+= 1` below -- and this
    // field has THREE writer sites, one of which (finishStopFlowConfirmed(), reached via
    // updateStateString -> syncEngineState -> handleEngineLevelForStop) runs on the AIDL binder
    // thread, not the main thread: the engine process is declared `android:process=":openvpn"`
    // in the engine manifest, so that callback genuinely lands on a binder thread-pool thread,
    // and nothing in that call chain marshals it to main. A concurrent binder-thread
    // finishStopFlowConfirmed() and main-thread ACTION_START can both read the same value and
    // both write value+1, losing an increment. A lost increment leaves the live generation one
    // lower than it should be, letting a dispatch captured under an already-superseded attempt
    // match the live generation at every guard built on this counter (:1318, :1256/:1226,
    // :2777, :2804/:2816, :2834) and reach ServerAutoSwitcher -- the same skip-without-trying
    // end-state as every other variant in this defect family. AtomicInteger's incrementAndGet()/
    // get() close this: increments are atomic regardless of which thread calls them, and get()
    // always returns the true, fully-visible live value. See PR #126 review thread (round 12,
    // Codex P2, comment 3734663965) for the original stop-then-restart race this field exists to
    // close; R20-1 only changes HOW the counter is mutated, not why it exists.
    private val connectionAttemptGeneration = AtomicInteger(0)

    // PR #127 review round 3 (Codex P1, thread 3792922991, on the ACTION_START branch's
    // blank-config isNullOrBlank() early return): during the ENGINE_RECONNECT_DISPATCH_BUFFER_MS
    // window, the just-stopped (previous) engine can still deliver a late terminal AIDL level
    // (LEVEL_NONETWORK/LEVEL_AUTH_FAILED) for the CURRENT generation -- the buffer only delays
    // startIcsOpenVpn(), so no new engine process exists yet to have produced that level, meaning
    // any level received before the buffer fires is necessarily stale. connectionAttemptGeneration
    // alone does not catch this: it is bumped by ACTION_START itself (the `connectionAttemptGeneration
    // += 1` statement earlier in this same branch) BEFORE the stray level arrives, so
    // dispatchAutoSwitcherOnEngineLevel()'s existing dispatchedForGeneration != connectionAttemptGeneration
    // check (which guards against a dispatch queued for an OLDER generation) captures the level
    // under the already-current generation and forwards it to ServerAutoSwitcher.onEngineLevel()
    // unfiltered. That stray delivery can re-trigger requestSwitchNow(), whose preserveReconnect
    // stop then sweeps reconnectEngineDispatchToken and cancels the still-pending deferred
    // dispatch -- skipping the selected server entirely without ever trying it.
    // Holds the generation of the MOST RECENTLY posted reconnect engine-dispatch buffer that is
    // still pending (i.e. its deferred Runnable posted below has not yet run or been swept), or -1
    // when none is pending.
    // R18-1 (fix-cycle 19, QG9-1/QG9-2, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-
    // gate-9.md): set right after the ACTION_START branch's blank-config isNullOrBlank() early
    // return, NOT right before postAtTime() (where it originally sat) -- the several statements in
    // between (SharedPreferences reads/write, an AppLog.i call) were a real suppression-window gap
    // on the normal retry path. Also deliberately NOT set before that early return: doing so would
    // latch this marker permanently on a reachable blank-config reconnect, because the deferred
    // Runnable that clears it is never posted on that path. This is the ONLY site that arms this
    // field; the (former) second site right before postAtTime() was removed rather than kept as a
    // redundant second writer -- see that call site's comment.
    // R16-1 (fix-cycle 17, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-16.md):
    // a single Int field cannot represent more than one pending buffer at a time, so when two
    // buffers are in flight (an earlier one superseded by a newer ACTION_START before it ran --
    // see reconnectStart_supersededByNewerReconnectStart_dispatchesOnlyOnce), this field holds
    // only the NEWER buffer's generation from the moment the newer ACTION_START is processed. The
    // Runnable clears the marker ONLY if the marker still equals that Runnable's own captured
    // dispatchGeneration -- guarding against the earlier, superseded buffer's Runnable wiping the
    // newer buffer's still-live marker when it resolves first. A Runnable therefore self-invalidates
    // the moment its OWN buffer resolves (fires or is skipped); it never touches a different
    // generation's marker.
    // R5-1 (fix-cycle 18, PR #127 review round 5, thread 3793613337): the clear is NOT the
    // Runnable's first statement -- on the success path it happens right AFTER startIcsOpenVpn(),
    // not before. This field is read directly (no handler hop) on the AIDL binder thread inside
    // dispatchAutoSwitcherOnEngineLevel(), so clearing before the engine dispatch call actually
    // fires left a real cross-thread window where a late binder callback could observe -1, evade
    // suppression, and cancel/skip the server this very Runnable was in the middle of starting. The
    // guard-failure branches (stop/destroy, generation mismatch) never reach the engine, so they
    // still clear immediately -- only the dispatch path defers the clear, and only to the far side
    // of the one call it guards, still inside the same synchronous Runnable execution.
    // Any subsequent event that bumps
    // connectionAttemptGeneration (a fresh ACTION_START, the preserveReconnect ACTION_STOP bump,
    // finishStopFlowConfirmed()) also implicitly invalidates a still-pending value here, since the
    // equality check in dispatchAutoSwitcherOnEngineLevel() below compares against the LIVE
    // counter -- no separate sweep site is needed for those three paths. startUserStopTeardown()
    // (R16-2) IS a fourth sweep site (it removes the pending Runnable via
    // reconnectEngineDispatchToken without running it) but does NOT bump connectionAttemptGeneration,
    // so it can leave this marker latched at a stale generation. That is benign today only because
    // userInitiatedStop is true throughout the latched interval, which independently discards any
    // level at the deferred dispatch's own inner `userInitiatedStop || serviceDestroyed` guard, and
    // because the latch cannot outlive the next generation bump (finishStopFlowConfirmed() or a fresh
    // ACTION_START, both of which occur before any new attempt can begin).
    // @Volatile for cross-thread visibility (same requirement connectionAttemptGeneration has,
    // though that field is now an AtomicInteger for a stronger reason -- see its declaration
    // comment, R20-1): every write to THIS field is a plain assignment, always on the main
    // thread (ACTION_START / the deferred Runnable's clearMarkerIfOwn()), never a read-modify-
    // write and never from the binder thread, so @Volatile's visibility guarantee alone is
    // sufficient here -- there is no lost-update risk to convert to AtomicInteger for. It is
    // read on the AIDL binder thread inside dispatchAutoSwitcherOnEngineLevel().
    @Volatile private var reconnectDispatchPendingGeneration: Int = -1

    // Byte count tracking for local listener vs AIDL callbacks
    private var lastLocalByteUpdateTs: Long = 0L
    // Written and read on the AIDL binder thread only (updateByteCount(inBytes, outBytes)),
    // but Android's binder thread pool may service successive calls on different worker
    // threads, so @Volatile is required for cross-call memory visibility.
    @Volatile private var aidlLastInBytes: Long = 0L
    @Volatile private var aidlLastOutBytes: Long = 0L
    @Volatile private var lastAidlByteUpdateTs: Long = 0L
    @Volatile private var controllerForegroundActive = false

    // Binding to status service for engine logs/metrics
    // Written on the AIDL binder thread (statusDeathRecipient's binderDied callback, invoked on
    // a binder-pool thread when the status service dies) and read on the main looper
    // (trafficPollRunnable, isAidlFresh() via applyStatusSnapshot). Same cross-thread visibility
    // requirement as lastStatusSnapshotMs/lastLiveStatusMs below: without @Volatile the main
    // thread could observe a stale cached boundToStatus/statusBinder value after a binder death,
    // masking a dead status channel.
    @Volatile private var statusBinder: IServiceStatus? = null
    @Volatile private var boundToStatus = false
    private var statusRebindDelayMs = 500L
    // Written on the AIDL binder thread (updateStateString) and read on the main looper
    // (applyStatusSnapshot, via onServiceConnected / trafficPollRunnable). Same cross-thread
    // visibility requirement as aidlLastInBytes/aidlLastOutBytes above: without @Volatile the
    // main thread can observe a stale cached value, e.g. computing livePushStale=false when the
    // live push channel has actually died, silently defeating the stale-push auto-switch fix.
    @Volatile private var lastStatusSnapshotMs: Long = 0L
    @Volatile private var lastLiveStatusMs: Long = 0L
    // Monotonic counterpart to lastLiveStatusMs (SystemClock.elapsedRealtime() via
    // elapsedRealtimeMs()), paired the same way currentAttemptStartElapsedRealtimeMs pairs with
    // currentAttemptStartMs: it makes isAidlFresh() immune to a backward wall-clock jump after the
    // last live AIDL push, which would otherwise make a stalled push channel look falsely "fresh"
    // for as long as wall-clock time takes to naturally catch back up.
    @Volatile private var lastLiveStatusElapsedRealtimeMs: Long = 0L
    private var staleSnapshotCount: Int = 0
    private enum class StatusSource { AIDL, VPN_STATUS }
    private var statusSource: StatusSource? = null
    private var lastStatusSourceSwitchMs: Long = 0L
    private val aidlFreshWindowMs = 3_000L
    // History (rounds 8-13): this gate started as a per-level allowlist
    // (staleSnapshotTimeoutLevels) that grew by one entry almost every round --
    // LEVEL_CONNECTING_NO_SERVER_REPLY_YET/LEVEL_CONNECTING_SERVER_REPLIED/LEVEL_AUTH_FAILED
    // from the original fix, LEVEL_NONETWORK (round 8, Codex P2: it drives
    // ServerAutoSwitcher's shouldSwitchImmediately fast path -- level == LEVEL_AUTH_FAILED ||
    // (source == "AIDL" && level == LEVEL_NONETWORK) -- and without the gate a stale cached
    // NONETWORK reading could immediately stop/switch a currently-fresh CONNECTING attempt),
    // LEVEL_NOTCONNECTED (round 11, Codex P2, comment 3734228641: ServerAutoSwitcher treats it
    // either as a waitingStopForRetry stop-confirmation or, in its else branch, as a reason to
    // cancel(...) the active switch timer -- a stale reading could misfire either path), and
    // LEVEL_CONNECTED (round 12, Codex P2, comment 3734663954: the same else-branch cancel(...)
    // path, this time indistinguishable from a genuine "current attempt just connected"
    // signal). By round 14 the allowlist held 7 of the 10 possible ConnectionStatus values,
    // and Codex (comment 3735319526) pointed out that the remaining 3 -- LEVEL_START,
    // LEVEL_WAITING_FOR_USER_INPUT, LEVEL_VPNPAUSED -- hit the EXACT SAME
    // ServerAutoSwitcher.onEngineLevel else-branch cancel(...) path as LEVEL_NOTCONNECTED /
    // LEVEL_CONNECTED above: none of the three is in ServerAutoSwitcher's own `timeoutLevels`
    // set, none is UNKNOWN_LEVEL, none is the AUTH_FAILED/AIDL+NONETWORK immediate-switch case,
    // so a stale/predating snapshot carrying any of them would incorrectly cancel a healthy
    // active switch timer exactly like the round-11/12 bugs.
    //
    // Rather than add a 4th (and inevitably 5th, 6th...) entry, round 14 removed the allowlist
    // entirely: since adding those 3 named levels would have made the set equal to the FULL
    // ConnectionStatus domain (10 of 10 values -- see ConnectionStatus.java), enumerating "every
    // level" and simply applying the check unconditionally are behaviorally identical for this
    // closed enum, and only the latter closes the "one more level missing" bug class for good.
    // No ConnectionStatus value has a reason to skip this check: the predates-current-attempt /
    // age logic below already handles "is this genuinely the current attempt's data" correctly
    // regardless of which level the snapshot carries, so every level -- current members and any
    // added to the enum in the future -- goes through the same gate now.
    private val staleSnapshotMaxAgeMs = 10_000L
    // Round 16 (Codex P2, comment 3735937824): thresholds for the backward-wall-clock-jump
    // safety net in applyStatusSnapshot(). clockJumpMinDetectableMs filters out the sub-second
    // measurement noise between two separate watchdogNowMs()/elapsedRealtimeMs() reads from
    // being mistaken for a genuine clock correction. clockJumpSlackMs is a small tolerance added
    // on top of the estimated jump size when deciding whether a snapshot's predates-gap is fully
    // explained by that detected jump, absorbing the same read skew.
    private val clockJumpMinDetectableMs = 1_000L
    private val clockJumpSlackMs = 2_000L
    private val liveStatusGraceMs = 5_000L
    private val statusHandler = Handler(Looper.getMainLooper())
    private val trafficHandler = Handler(Looper.getMainLooper())
    private var lastPolledDatapoint: TrafficHistory.TrafficDatapoint? = null
    private var lastPolledState: ConnectionState? = null
    private data class HealthWatchdogState(
        var connectedSinceMs: Long = 0L,
        var consecutiveFailures: Int = 0,
        var lastHealthyTimestamp: Long = 0L,
        var lastRecoveryTimestamp: Long = 0L,
        var recoveryAttempts: Int = 0,
        var degraded: Boolean = false
    )
    private data class WatchdogProbeTarget(
        val host: String,
        val port: Int
    )
    private data class WatchdogRecoveryTarget(
        val config: String,
        val title: String?
    )
    private var watchdogState = HealthWatchdogState()

    /**
     * True from the moment the watchdog dispatches a recovery until traffic actually flows again,
     * the fail-safe fires, or the user starts a connection themselves.
     *
     * Recovery reconnects, so it causes the very connection-state transition that
     * [resetHealthWatchdog] zeroes. Without this flag [HealthWatchdogState.recoveryAttempts] would
     * restart at 0 after every attempt, WATCHDOG_MAX_RECOVERY_ATTEMPTS would never be reached, and
     * a server that connects cleanly but carries no traffic would be retried forever.
     */
    private var watchdogRecoveryInFlight = false
    internal var watchdogNowMs: () -> Long = { System.currentTimeMillis() }
    // Monotonic counterpart to watchdogNowMs, used only to pair with
    // currentAttemptStartElapsedRealtimeMs for the backward-wall-clock-jump safety net in
    // applyStatusSnapshot(). Injectable for the same reason watchdogNowMs is: deterministic unit
    // tests can simulate a clock jump without depending on real device uptime.
    internal var elapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }
    internal var watchdogProbeDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var watchdogProbe: (String, Int, Int) -> Boolean = { host, port, timeoutMs ->
        performReachabilityProbe(host, port, timeoutMs)
    }
    /**
     * Dispatches a recovery. Returns false when nothing was actually dispatched, so the caller can
     * fail safe instead of consuming budget on an attempt that never happened.
     * [ServerAutoSwitcher.beginChainedSwitch] reports false for every such case: auto-switch off,
     * a rejected stop command, or an exception while requesting the stop.
     */
    internal var watchdogRecoveryStarter: (Context, String, String?) -> Boolean = { ctx, config, title ->
        ServerAutoSwitcher.beginChainedSwitch(ctx, config, title)
    }
    private var watchdogProbeJob: Job? = null
    
    @Volatile private var probeQueue: ProbeRequestQueue? = null

    // Track pause action to ensure PAUSED state is reached
    private var pauseActionInFlight = false
    private var pauseActionStartedMs: Long = 0L
    // Track resume action to detect engine stall and roll back to PAUSED
    private var resumeActionInFlight = false
    private var lastAidlLevel: ConnectionStatus? = null
    private var lastAidlState: String? = null
    private var lastAidlStateUpdateMs: Long = 0L
    private var lastVpnStatusLevel: ConnectionStatus? = null
    private var lastVpnStatusState: String? = null
    private var lastVpnStatusStateUpdateMs: Long = 0L
    private var lastEngineLevel: ConnectionStatus? = null
    private var lastEngineDetail: String? = null
    private var lastEngineLevelLogMs: Long = 0L
    private var oneShotSyncRequested = false
    private var oneShotSyncReceivedInitialState = false

    // Structural closure for quality-gate finding G1 (fix-cycle 3), UI dispatcher only: a genuine
    // ACTION_START from a human Connect tap is dispatched from a visible activity (see
    // VpnManager.startVpn()'s MainActivityCore.kt caller). ONE_SHOT_STOP_CONFIRM_DELAY_MS alone
    // only relocates the AMS "bringing down service while still waiting for start foreground"
    // race to a later timer tick; it does not remove the possibility that some tick coincides with
    // a real Connect tap. Gating the actual stopSelf() call on "is any activity currently started"
    // instead removes the coincidence entirely for that dispatcher: stopSelf() from this one-shot
    // path and a UI-driven ACTION_START now require mutually exclusive process-lifecycle states,
    // not merely low-probability timer alignment. See stopAfterOneShotSyncConfirmedRunnable and
    // appLifecycleObserver below. A SECOND ACTION_START dispatcher exists --
    // ServerAutoSwitcher's background retry timers, not gated by UI visibility at all -- and is
    // excluded separately by the != ConnectionState.DISCONNECTED state guard in both
    // stopAfterOneShotSyncRunnable and stopAfterOneShotSyncConfirmedRunnable (review-4 F1,
    // docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-4.md).
    // Injectable, like watchdogNowMs/elapsedRealtimeMs elsewhere in this class: tests override
    // this field via reflection instead of trying to drive the real process-wide
    // ProcessLifecycleOwner singleton through Robolectric's Activity lifecycle simulation, which
    // isn't wired to it without app-level androidx-startup initialization.
    internal var appForegroundVisibleProvider: () -> Boolean = {
        try {
            ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        } catch (t: Throwable) {
            // Fail closed: if we cannot determine visibility, assume an activity could be visible
            // and suppress the stop rather than risk racing a Connect tap. Leaving an idle
            // controller instance running a little longer is cheap (see
            // ONE_SHOT_STOP_CONFIRM_DELAY_MS's declaration comment); tearing one down under a live
            // foreground-service-start obligation is the crash this fix exists to prevent.
            AppLog.w(TAG, "Unable to read process lifecycle state; treating app as foreground", t)
            true
        }
    }

    // Assumes this Service runs in the app's main process (it has no android:process in any
    // manifest today, unlike the engine's :openvpn process -- CoreApp.isMainProcess() guards its
    // own ProcessLifecycleOwner usage for the same reason). If that ever changes,
    // ProcessLifecycleOwner here would never observe an activity and this would silently return
    // false forever, making the G1 suppression a permanent no-op (review-4 F7).
    private fun isAppForegroundVisible(): Boolean = appForegroundVisibleProvider()

    // Reaps a one-shot-sync-created controller instance once the UI that made it unsafe to stop
    // goes away, via the pre-existing, already-tested ACTION_STOP_IF_IDLE path (VpnManager
    // .stopControllerIfIdle(), previously only triggered from SettingsActivity). Registered only
    // while this Service instance is alive (onCreate/onDestroy below), so it never spins up a new
    // instance just to immediately stop it, and it targets an already-running instance -- which
    // does not hit Android's "cannot startService() from the background" restriction the way
    // starting a brand-new service would. ACTION_STOP_IF_IDLE itself only stops when
    // ConnectionStateManager is DISCONNECTED, so it is a no-op whenever a real session (including
    // an in-flight auto-switch reconnect) is active. This is a single edge event, not a retry loop
    // (review-4 F4): if the VPN is not yet DISCONNECTED at the moment ON_STOP fires, or the
    // startService() call it issues is rejected, nothing re-arms it until the next
    // foreground-then-background cycle. Bounded and non-crashing -- the instance has already
    // dropped its foreground notification via exitControllerForeground() -- just not immediate.
    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            AppLog.i(TAG, "App UI left foreground; reaping idle controller via ACTION_STOP_IF_IDLE")
            VpnManager.stopControllerIfIdle(this@OpenVpnService)
        }
    }

    // ProcessLifecycleOwner.get() requires the main thread; both onCreate()/onDestroy() already
    // run there. Mirrors CoreApp.registerSseLifecycleObserver()'s defensive runCatching -- lifecycle
    // -process initialization is not guaranteed in every embedding (e.g. some test/host manifests),
    // and isAppForegroundVisible() already fails closed if the state can't be read afterward.
    private fun registerAppLifecycleObserver() {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        }.onFailure { AppLog.w(TAG, "Failed to register app lifecycle observer", it) }
    }

    private fun unregisterAppLifecycleObserver() {
        runCatching {
            ProcessLifecycleOwner.get().lifecycle.removeObserver(appLifecycleObserver)
        }.onFailure { AppLog.w(TAG, "Failed to unregister app lifecycle observer", it) }
    }

    private val stopAfterOneShotSyncRunnable = Runnable {
        if (!oneShotSyncRequested) return@Runnable
        if (!oneShotSyncReceivedInitialState) {
            AppLog.d(TAG, "One-shot status sync pending; keep controller alive")
            return@Runnable
        }
        if (userInitiatedStart || userInitiatedStop) return@Runnable
        if (ConnectionStateManager.state.value != ConnectionState.DISCONNECTED) {
            AppLog.d(TAG, "One-shot sync keeping controller alive while VPN is not idle")
            return@Runnable
        }
        // Do not call stopSelf() here. Defer it by ONE_SHOT_STOP_CONFIRM_DELAY_MS and
        // re-run the same guard checks in stopAfterOneShotSyncConfirmedRunnable immediately
        // before the deferred stopSelf() actually fires -- see ONE_SHOT_STOP_CONFIRM_DELAY_MS's
        // declaration comment for the boundary-timing race this closes. If a genuine
        // ACTION_START is the reason this runnable ran at all (its removeCallbacks() call
        // arrived just after this runnable had already started executing), that same
        // ACTION_START sets userInitiatedStart = true very early in onStartCommand() -- well
        // before this buffer elapses -- so the confirmed runnable's re-check correctly aborts.
        AppLog.d(TAG, "One-shot status sync decided to stop; confirming after ${ONE_SHOT_STOP_CONFIRM_DELAY_MS}ms buffer")
        statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
        statusHandler.postDelayed(stopAfterOneShotSyncConfirmedRunnable, ONE_SHOT_STOP_CONFIRM_DELAY_MS)
    }

    // Second stage of the one-shot-sync stop decision -- see stopAfterOneShotSyncRunnable and
    // ONE_SHOT_STOP_CONFIRM_DELAY_MS. This is a genuinely separate Runnable/Handler token, so
    // removeCallbacks(stopAfterOneShotSyncRunnable) does NOT automatically cancel a pending
    // instance of this one; every cancellation site for the former must also cancel this one.
    private val stopAfterOneShotSyncConfirmedRunnable = Runnable {
        if (!oneShotSyncRequested) return@Runnable
        if (!oneShotSyncReceivedInitialState) return@Runnable
        if (userInitiatedStart || userInitiatedStop) {
            // AppLog.i, not .d: AppReleaseTree drops DEBUG in release builds, and this line is
            // the one field-visible proof that the buffered re-check actually engaged (G5).
            AppLog.i(TAG, "One-shot sync stop aborted by buffered re-check (userInitiatedStart/Stop set)")
            return@Runnable
        }
        // Fix-cycle 7 QA finding (docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-qa-2.md,
        // "2026-08-14 continuation 2"): userInitiatedStart above is only set once ACTION_START's
        // Intent has actually been delivered to onStartCommand(). A fresh ACTION_START dispatch can
        // be issued by VpnManager.startVpn() (Connect tap, or ServerAutoSwitcher's retry timer) and
        // still be in AMS/Binder transit when this runnable's re-check runs -- observed with a gap
        // as small as 3ms between this stopSelf() decision and the following ACTION_START, well
        // under the AMS round-trip latency. Android's FGS-start obligation begins the instant
        // startForegroundService() is CALLED, not once delivered, so stopSelf() here can still race
        // an outstanding obligation this app process cannot yet see via userInitiatedStart. See
        // VpnManager.hasRecentActionStartDispatch()'s declaration comment for why checking it here
        // closes this gap deterministically. Distinct mechanism from review-7's R7-1
        // (ConnectionStateManager staleness): state is genuinely DISCONNECTED here, not stale.
        if (VpnManager.hasRecentActionStartDispatch()) {
            AppLog.i(TAG, "One-shot sync stop aborted: recent ACTION_START dispatch still in flight")
            return@Runnable
        }
        if (ConnectionStateManager.state.value != ConnectionState.DISCONNECTED) {
            // Excludes ServerAutoSwitcher's background retry-timer ACTION_START dispatcher
            // (review-4 F1): reconnectingHint holds state at CONNECTING for the whole auto-switch
            // stop-to-start gap, so this guard alone already makes stopSelf() and that dispatcher
            // mutually exclusive, the same way isAppForegroundVisible() below does for the UI
            // dispatcher.
            AppLog.d(TAG, "One-shot sync keeping controller alive while VPN is not idle (buffered re-check)")
            return@Runnable
        }
        if (isAppForegroundVisible()) {
            // G1 structural closure, UI dispatcher: never call stopSelf() from this one-shot path
            // while any activity is started -- that is the only condition under which a genuine
            // ACTION_START from a human Connect tap can arrive (MainActivityCore.kt). The OTHER
            // production ACTION_START dispatcher, ServerAutoSwitcher's background retry timers, is
            // not gated by UI visibility at all and is excluded instead by the != DISCONNECTED
            // guard above (review-4 F1,
            // docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-4.md). Leave
            // oneShotSyncRequested/oneShotSyncReceivedInitialState set; appLifecycleObserver.onStop()
            // reaps the still-idle instance via ACTION_STOP_IF_IDLE the moment the UI actually goes
            // away, and a genuine ACTION_START aborts this decision outright via the checks above.
            AppLog.i(TAG, "One-shot sync stop suppressed while app UI is foreground; deferring to UI-gone-away teardown")
            return@Runnable
        }
        oneShotSyncRequested = false
        oneShotSyncReceivedInitialState = false
        // AppLog.i, not .d: this is the actual stopSelf() event the device diagnosis for this bug
        // is anchored on (gate-2's PID 8057 timeline). Promoting only the abort/suppression lines
        // to INFO while leaving this one at DEBUG would show every averted stop and no executed
        // one in a release-build log (review-4 F6).
        AppLog.i(TAG, "One-shot status sync complete; stopping controller service")
        stopSelf()
    }
    private val oneShotSyncTimeoutRunnable = Runnable {
        if (!oneShotSyncRequested || oneShotSyncReceivedInitialState) return@Runnable
        AppLog.w(TAG, "One-shot sync timeout; stopping controller with current state")
        oneShotSyncReceivedInitialState = true
        scheduleOneShotStop(0L)
    }

    private val stopRetryRunnable = Runnable {
        if (!userInitiatedStop) return@Runnable
        AppLog.w(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} retry=true reason=${stopLastFailureReason ?: "unknown"} next_attempt=${stopAttempt + 1}")
        stopAwaitingConfirmation = false
        stopBindPending = false
        requestStopIcsOpenVpn()
    }

    private val stopConfirmationTimeoutRunnable = Runnable {
        if (!userInitiatedStop || !stopAwaitingConfirmation) return@Runnable
        stopAwaitingConfirmation = false
        stopLastFailureReason = "confirmation_timeout"
        AppLog.w(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} attempt=$stopAttempt dispatch=sent confirm=false reason=confirmation_timeout")
        scheduleStopRetryOrFail("confirmation_timeout")
    }

    private val stopBindTimeoutRunnable = Runnable {
        if (!userInitiatedStop || !stopBindPending) return@Runnable
        stopBindPending = false
        stopLastFailureReason = "bind_timeout"
        stopAttempt += 1
        AppLog.w(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} attempt=$stopAttempt dispatch=not_sent reason=bind_timeout")
        scheduleStopRetryOrFail("bind_timeout")
    }

    private fun newStopRequestId(): String = UUID.randomUUID().toString().substring(0, 8)

    private fun hasPendingStopIntent(): Boolean = stopPrefs.getBoolean(PREF_PENDING_STOP_INTENT, false)

    private fun persistPendingStopIntent(pending: Boolean) {
        stopPrefs.edit().putBoolean(PREF_PENDING_STOP_INTENT, pending).commit()
    }

    private fun incrementStopFailureCounter(): Int {
        val next = stopPrefs.getInt(PREF_STOP_FAILURE_COUNT, 0) + 1
        stopPrefs.edit().putInt(PREF_STOP_FAILURE_COUNT, next).apply()
        return next
    }

    private fun incrementStaleReconcileCounter(): Int {
        val next = stopPrefs.getInt(PREF_STOP_STALE_RECONCILE_COUNT, 0) + 1
        stopPrefs.edit().putInt(PREF_STOP_STALE_RECONCILE_COUNT, next).apply()
        return next
    }

    private fun startUserStopTeardown(reason: String, forceReset: Boolean = false) {
        if (!userInitiatedStop || forceReset) {
            userInitiatedStop = true
            userInitiatedStart = false
            ignoreConnectedUntilNotConnected = true
            stopRequestId = newStopRequestId()
            stopStartedAtMs = System.currentTimeMillis()
            stopAttempt = 0
            stopAwaitingConfirmation = false
            stopBindPending = false
            stopLastFailureReason = null
            persistPendingStopIntent(true)
            ConnectionStateManager.clearStopFailure()
            AppLog.i(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} session=${sessionAttempt} source=$reason started=true")
        }
        try {
            ConnectionStateManager.setReconnectingHint(false)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to clear reconnecting hint on user stop", e)
        }
        try {
            ConnectionStateManager.updateSpeedMbps(0.0)
        } catch (_: Exception) {
        }
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTING)
        pauseActionInFlight = false
        resumeActionInFlight = false
        statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
        statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
        // This sweep only cancels dispatches to ServerAutoSwitcher that are queued but not yet
        // run (see autoSwitchDispatchToken's declaration comment). It does nothing to a
        // ServerAutoSwitcher switch timer that was ALREADY running before this teardown began --
        // that timer lives on ServerAutoSwitcher's own separate main-looper Handler, and the one
        // AIDL callback that would normally stop it (LEVEL_NOTCONNECTED reaching onEngineLevel)
        // is intentionally discarded during a user/system stop by
        // dispatchAutoSwitcherOnEngineLevel's userInitiatedStop/serviceDestroyed guard. Without an
        // explicit cancel here, that timer fires a few seconds after an explicit disconnect and
        // silently reconnects. See PR #126 round 18 (Codex P1, comment 3736956722).
        statusHandler.removeCallbacksAndMessages(autoSwitchDispatchToken)
        // R14-2: sweep the reconnect engine-dispatch token here too -- see its declaration
        // comment. A plain user Disconnect (ACTION_STOP, preserveReconnect=false) reaches this
        // function and must cancel any reconnect dispatch still queued from a prior auto-switch
        // retry exactly as it already cancels the auto-switch reaction dispatches above.
        statusHandler.removeCallbacksAndMessages(reconnectEngineDispatchToken)
        // startUserStopTeardown() can be reached synchronously from the AIDL binder thread via
        // maybeStartStaleStopReconciliation() -> syncEngineState() -> updateStateString() (the
        // "stale_relaunch" path) -- it is NOT guaranteed to run on the main thread the way the
        // ACTION_STOP and watchdog_fail_safe call sites are. ServerAutoSwitcher's internal timer
        // state is plain, non-volatile state that assumes a single main-looper caller (the same
        // invariant dispatchAutoSwitcherOnEngineLevel's Looper check below protects), so the
        // cancellation itself must be dispatched onto the main thread exactly like
        // dispatchAutoSwitcherOnEngineLevel already does for the same reason. Posted untagged
        // (not with autoSwitchDispatchToken): that token exists to let teardown cancel
        // forward-looking auto-switch REACTION dispatches -- this post IS the cancellation
        // action, so tagging it the same way would risk a later
        // removeCallbacksAndMessages(autoSwitchDispatchToken) sweep (e.g. from onDestroy())
        // wiping out this cancel-the-timer post before it runs.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            ServerAutoSwitcher.cancelForUserStop()
        } else {
            statusHandler.post { ServerAutoSwitcher.cancelForUserStop() }
        }
        requestStopIcsOpenVpn()
    }

    private fun scheduleStopRetryOrFail(reason: String) {
        if (!userInitiatedStop) return
        if (stopAttempt >= STOP_DISPATCH_MAX_ATTEMPTS) {
            markStopFailure(reason)
            return
        }
        statusHandler.removeCallbacks(stopRetryRunnable)
        statusHandler.postDelayed(stopRetryRunnable, STOP_DISPATCH_RETRY_DELAY_MS)
    }

    private fun markStopFailure(reason: String) {
        stopAwaitingConfirmation = false
        stopBindPending = false
        stopLastFailureReason = reason
        statusHandler.removeCallbacks(stopRetryRunnable)
        statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
        statusHandler.removeCallbacks(stopBindTimeoutRunnable)
        ConnectionStateManager.setStopFailure()
        val elapsedMs = if (stopStartedAtMs > 0L) System.currentTimeMillis() - stopStartedAtMs else -1L
        val failureCount = incrementStopFailureCounter()
        AppLog.e(
            TAG,
            "stop_flow requestId=${stopRequestId ?: "<none>"} attempts=$stopAttempt dispatch=failed confirm=false elapsed_ms=$elapsedMs reason=$reason failure_count=$failureCount"
        )
    }

    private fun finishStopFlowConfirmed(level: ConnectionStatus, source: String) {
        if (!userInitiatedStop) return
        stopAwaitingConfirmation = false
        stopBindPending = false
        statusHandler.removeCallbacks(stopRetryRunnable)
        statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
        statusHandler.removeCallbacks(stopBindTimeoutRunnable)
        ignoreConnectedUntilNotConnected = false
        userInitiatedStop = false
        // Bump the generation here too, not just on ACTION_START (round 12): a full
        // user-initiated stop-to-shutdown never fires ACTION_START, but this confirmation runs
        // BEFORE onDestroy() actually executes (serviceDestroyed is set there, not here) and
        // BEFORE stopSelf() completes teardown. A binder callback whose deferred dispatch was
        // enqueued before this confirmation ran, but which executes during this exact window,
        // would otherwise see userInitiatedStop=false (just cleared above), serviceDestroyed=
        // false (onDestroy hasn't run yet), and an unchanged generation -- passing all three
        // defensive checks in dispatchAutoSwitcherOnEngineLevel and incorrectly starting
        // auto-switch from stale data after the user explicitly disconnected. Bumping here closes
        // that window with the same mechanism round 12 already introduced. See PR #126 round 13
        // (Codex P2, comment 3734974192).
        // R20-1 (fix-cycle 21): this call runs on the AIDL binder thread (reached via
        // updateStateString -> syncEngineState -> handleEngineLevelForStop), so incrementAndGet()
        // -- not a plain `+= 1` -- is required to avoid losing a concurrent bump from ACTION_START
        // on the main thread. See connectionAttemptGeneration's declaration comment.
        connectionAttemptGeneration.incrementAndGet()
        ConnectionStateManager.clearStopFailure()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        val serverId = if (level != ConnectionStatus.LEVEL_NONETWORK) {
            SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)
        } else 0
        if (serverId != 0) {
            try { probeQueue?.enqueue(serverId) } catch (e: Exception) {
                AppLog.w(TAG, "Failed to enqueue hardprobe on user disconnect", e)
            }
        }
        persistPendingStopIntent(false)
        val elapsedMs = if (stopStartedAtMs > 0L) System.currentTimeMillis() - stopStartedAtMs else -1L
        AppLog.i(
            TAG,
            "stop_flow requestId=${stopRequestId ?: "<none>"} attempts=$stopAttempt dispatch=sent confirm=true level=$level source=$source elapsed_ms=$elapsedMs"
        )
        stopRequestId = null
        stopStartedAtMs = 0L
        stopAttempt = 0
        stopLastFailureReason = null
        if (boundToEngine) {
            try {
                unbindService(engineConnection)
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to unbind engine after confirmed stop", e)
            }
            boundToEngine = false
        }
        // QG4-2(b) (fix-cycle 8, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-4.md):
        // same hasRecentActionStartDispatch() marker guard as cycle 7's
        // stopAfterOneShotSyncConfirmedRunnable site (see that Runnable's declaration comment for
        // the full rationale). ServerAutoSwitcher's orphaned retry lambda (QG4-2(a)) can dispatch a
        // fresh ACTION_START while a user-stop teardown is still running toward this stopSelf() --
        // that dispatch arms a fresh FGS-start obligation this stopSelf() would tear down before it
        // is discharged, reproducing the same crash class this bug's fix-flow removes.
        if (VpnManager.hasRecentActionStartDispatch()) {
            AppLog.i(TAG, "Confirmed-stop stopSelf() aborted: recent ACTION_START dispatch still in flight")
            return
        }
        stopSelf()
    }

    private fun maybeStartStaleStopReconciliation(level: ConnectionStatus, source: String): Boolean {
        if (level != ConnectionStatus.LEVEL_CONNECTED) return false
        if (!hasPendingStopIntent()) return false
        if (userInitiatedStart) return false
        if (userInitiatedStop) return false

        val reconcileCount = incrementStaleReconcileCounter()
        AppLog.w(
            TAG,
            "stale_stop_guard source=$source pending_stop_intent=true observed_level=$level reconcile_count=$reconcileCount"
        )
        startUserStopTeardown("stale_relaunch")
        return true
    }

    private fun maybeClearStaleStopIntentOnIdleLevel(level: ConnectionStatus, source: String) {
        if (level !in STOP_TERMINAL_LEVELS) return
        if (!hasPendingStopIntent()) return
        if (userInitiatedStop || userInitiatedStart) return

        persistPendingStopIntent(false)
        ConnectionStateManager.clearStopFailure()
        AppLog.i(
            TAG,
            "stop_flow pending intent cleared on idle engine level=$level source=$source pending_stop_intent=false"
        )
    }

    private fun totalServersStr(): String =
        if (sessionTotalServers >= 0) sessionTotalServers.toString() else "unknown"
    

    private val engineConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            engineBinder = IOpenVPNServiceInternal.Stub.asInterface(service)
            boundToEngine = true
            stopBindPending = false
            statusHandler.removeCallbacks(stopBindTimeoutRunnable)
            tryStopVpn()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            engineBinder = null
            boundToEngine = false
        }
    }

    override fun onCreate() {
        // R11-1: set before anything else, mirroring onDestroy()'s serviceDestroyed placement --
        // see isInstanceAlive's declaration comment above.
        isInstanceAlive = true
        super.onCreate()
        AppLog.i(TAG, "Service created")
        ensureEngineNotificationChannels()
        ensureEnginePreferences()
        // Satisfy Android's startForegroundService() obligation immediately in onCreate(),
        // eliminating the race between stopAfterOneShotSyncRunnable (stopSelf) and startForeground()
        // delivery when startForegroundService() is called while a sync-started service is stopping.
        // R8-4/QG4-5 (docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-4.md): this is
        // NOT a 5-second timer expiring -- bringDownServiceLocked() fires immediately (3-11ms
        // observed on-device) once AMS decides the obligation went unsatisfied. The understated
        // "5-second" framing is what made the QG4-1/QG4-2/QG4-3 stopSelf() sites look safe by
        // inspection when they were not.
        // enterControllerForeground() never calls stopSelf() on failure (QG4-1): a startForeground()
        // throw here just leaves controllerForegroundActive=false; the intent has not even been
        // delivered yet, so there is nothing to stop -- a subsequent ACTION_START will retry.
        enterControllerForeground()
        registerAppLifecycleObserver()
        VpnStatus.addStateListener(this)
        VpnStatus.addLogListener(this)
        VpnStatus.addByteCountListener(this)
        bindStatusService()

        trafficHandler.post(trafficPollRunnable)

        runCatching {
            val v2Sync = GlobalContext.get().get<ServersV2SyncCoordinator>()
            val selectionInteractor = GlobalContext.get().get<MainSelectionInteractor>()
            ServerAutoSwitcher.v2HydrationCallback = { ctx, onDone ->
                serviceScope.launch {
                    try {
                        val hasCountry = !SelectedCountryStore.getSelectedCountry(ctx).isNullOrBlank()
                        if (hasCountry) {
                            v2Sync.syncSelectedCountryServers(ctx)
                        } else {
                            AppLog.i(TAG, "DEFAULT_V2 hydration: no selected country, bootstrapping initial selection")
                            selectionInteractor.loadInitialSelection(cacheOnly = false)
                        }
                    } catch (e: Exception) {
                        AppLog.w(TAG, "DEFAULT_V2 on-demand hydration failed", e)
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main) { onDone() }
                    }
                }
            }
        }.onFailure { e ->
            AppLog.w(TAG, "Failed to wire DEFAULT_V2 hydration callback", e)
        }

        runCatching {
            val queue = GlobalContext.get().get<ProbeRequestQueue>()
            probeQueue = queue
            ServerAutoSwitcher.probeRequestQueue = queue
        }.onFailure { e ->
            AppLog.w(TAG, "Failed to wire ProbeRequestQueue", e)
        }
    }

    private fun updateStatusSource(source: StatusSource, reason: String) {
        if (statusSource != source) {
            statusSource = source
            lastStatusSourceSwitchMs = System.currentTimeMillis()
            AppLog.i(TAG, "Status source -> ${source.name} (${reason})")
        }
    }

    private fun logEngineStateChange(
        source: String,
        level: ConnectionStatus,
        state: String?
    ) {
        val now = System.currentTimeMillis()
        val previousLevel: ConnectionStatus?
        val previousState: String?
        when (source) {
            "AIDL" -> {
                previousLevel = lastAidlLevel
                previousState = lastAidlState
                lastAidlLevel = level
                lastAidlState = state
                lastAidlStateUpdateMs = now
            }
            "VPN_STATUS" -> {
                previousLevel = lastVpnStatusLevel
                previousState = lastVpnStatusState
                lastVpnStatusLevel = level
                lastVpnStatusState = state
                lastVpnStatusStateUpdateMs = now
            }
            else -> {
                previousLevel = null
                previousState = null
            }
        }
        if (previousLevel != level || previousState != state) {
            AppLog.d(TAG, "Engine state (${source}): level=${level} state=${state ?: "<null>"}")
        }
    }

    private fun getLatestObservedEngineState(): Pair<ConnectionStatus?, String?> {
        if (isAidlFresh()) {
            return if (lastAidlStateUpdateMs > 0L || lastAidlLevel != null) {
                lastAidlLevel to lastAidlState
            } else {
                ConnectionStateManager.engineLevel.value to ConnectionStateManager.engineDetail.value
            }
        }

        return when {
            lastVpnStatusStateUpdateMs > lastAidlStateUpdateMs -> lastVpnStatusLevel to lastVpnStatusState
            lastAidlStateUpdateMs > 0L -> lastAidlLevel to lastAidlState
            else -> ConnectionStateManager.engineLevel.value to ConnectionStateManager.engineDetail.value
        }
    }

    private fun isAidlFresh(): Boolean {
        // Round 17 fix (Codex P2, comment 3736234632): measured purely with monotonic time
        // (elapsedRealtimeMs()/lastLiveStatusElapsedRealtimeMs), not wall-clock. The previous
        // wall-clock-only check (now - lastLiveStatusMs) could be fooled by a backward clock jump
        // after the last live push: the delta goes negative/small, so a stalled push channel keeps
        // reporting "fresh" -- silently reproducing this PR's "stuck on Connecting..." bug via a
        // clock-jump vector, since applyStatusSnapshot() derives allowAutoSwitch from
        // !isAidlFresh().
        return boundToStatus && lastLiveStatusMs > 0L && lastLiveStatusElapsedRealtimeMs > 0L &&
            (elapsedRealtimeMs() - lastLiveStatusElapsedRealtimeMs) <= aidlFreshWindowMs
    }

    private fun shouldUseVpnStatus(): Boolean = !isAidlFresh()

    private fun shouldSupplementAidlWithVpnStatus(level: ConnectionStatus): Boolean {
        if (!isAidlFresh()) return false
        if (ConnectionStateManager.state.value != ConnectionState.CONNECTING) return false
        return level == ConnectionStatus.LEVEL_START ||
            level == ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET ||
            level == ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED ||
            level == ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT
    }
    private fun bindStatusService() {
        try {
            val statusIntent = Intent().apply { setClassName(applicationContext, "de.blinkt.openvpn.core.OpenVPNStatusService") }
            try {
                startService(statusIntent)
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to start status service", e)
            }
            boundToStatus = bindService(statusIntent, statusConnection, Context.BIND_AUTO_CREATE)
            AppLog.d(TAG, "Binding status service: $boundToStatus")
            if (!boundToStatus) {
                scheduleStatusRebind()
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "Failed to bind status service", t)
            scheduleStatusRebind()
        }
    }

    private val statusDeathRecipient = IBinder.DeathRecipient {
        AppLog.w(TAG, "Status binder died; scheduling rebind")
        statusBinder = null
        boundToStatus = false
        updateStatusSource(StatusSource.VPN_STATUS, "status binder died")
        scheduleStatusRebind()
    }

    private fun scheduleStatusRebind() {
        statusHandler.removeCallbacks(statusRebindRunnable)
        statusHandler.postDelayed(statusRebindRunnable, statusRebindDelayMs)
        AppLog.d(TAG, "Scheduled status rebind in ${statusRebindDelayMs}ms")
        statusRebindDelayMs = (statusRebindDelayMs * 2).coerceAtMost(8_000L)
    }

    private val statusRebindRunnable = Runnable {
        if (boundToStatus) return@Runnable
        bindStatusService()
    }

    private fun ensureEnginePreferences() {
        try {
            val prefs = de.blinkt.openvpn.core.Preferences.getDefaultSharedPreferences(this)
            if (prefs.getBoolean(KEY_OVPN3, true)) prefs.edit().putBoolean(KEY_OVPN3, false).apply()
            if (!prefs.getBoolean(KEY_DISABLE_CONFIRMATION, false)) prefs.edit().putBoolean(KEY_DISABLE_CONFIRMATION, true).apply()
        } catch (t: Throwable) {
            AppLog.w(TAG, "Failed to set default OpenVPN preferences (ovpn3=false, disableconfirmation=true)", t)
        }
    }

    private fun ensureEngineNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.notificationChannels.map { it.id }.toSet()
        fun createIfMissing(id: String, name: String, importance: Int, desc: String) {
            if (!existing.contains(id)) nm.createNotificationChannel(NotificationChannel(id, name, importance).apply { description = desc })
        }
        createIfMissing(de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_BG_ID, "OpenVPN Background", NotificationManager.IMPORTANCE_MIN, "Background status")
        createIfMissing(de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID, "OpenVPN Status", NotificationManager.IMPORTANCE_LOW, "Connection status updates")
        createIfMissing(de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_USERREQ_ID, "OpenVPN Requests", NotificationManager.IMPORTANCE_HIGH, "User requests")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.getStringExtra(VpnManager.actionKey(this))) {
            VpnManager.ACTION_START -> {
                AppLog.i(TAG, "ACTION_START")
                stopAwaitingConfirmation = false
                stopBindPending = false
                stopLastFailureReason = null
                stopRequestId = null
                userInitiatedStop = false
                ignoreConnectedUntilNotConnected = false
                statusHandler.removeCallbacks(stopRetryRunnable)
                statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
                statusHandler.removeCallbacks(stopBindTimeoutRunnable)
                ConnectionStateManager.clearStopFailure()
                if (hasPendingStopIntent()) {
                    persistPendingStopIntent(false)
                    AppLog.i(TAG, "stop_flow pending intent cleared on fresh ACTION_START pending_stop_intent=false")
                }
                if (!enterControllerForeground()) return START_NOT_STICKY
                oneShotSyncRequested = false
                oneShotSyncReceivedInitialState = false
                statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
                statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
                statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
                pauseActionInFlight = false
                resumeActionInFlight = false
                statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                val config = intent.getStringExtra(VpnManager.extraConfigKey(this))
                val title = intent.getStringExtra(VpnManager.extraTitleKey(this))
                userInitiatedStart = true
                // R9-3 (fix-cycle 9): hand the dispatch-marker's authority over to
                // userInitiatedStart now that ACTION_START has actually landed and been processed
                // -- see VpnManager.clearRecentActionStartDispatch()'s declaration comment. Without
                // this, hasRecentActionStartDispatch() stayed "recent" for the full bridge window
                // even after this start fully landed, causing every guarded stopSelf() site to drop
                // (not defer) an intervening stop decision.
                VpnManager.clearRecentActionStartDispatch()
                if (ConnectionStateManager.state.value == ConnectionState.DISCONNECTING) {
                    ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
                }
                val isReconnect = intent.getBooleanExtra(VpnManager.extraAutoSwitchKey(this), false)
                try {
                    ConnectionStateManager.setReconnectingHint(isReconnect)
                    AppLog.d(TAG, "reconnectHint=${isReconnect} (start)")
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to set reconnecting hint on start", e)
                }
                if (isReconnect) {
                    sessionAttempt = if (sessionAttempt <= 0) 1 else sessionAttempt + 1
                } else {
                    sessionTotalServers = try { SelectedCountryStore.getServers(applicationContext).size } catch (_: Exception) { -1 }
                    sessionAttempt = 1
                    // A user-initiated start is a fresh budget. Only auto-switch reconnects
                    // (isReconnect) continue an in-flight watchdog recovery chain.
                    watchdogRecoveryInFlight = false
                    watchdogState.recoveryAttempts = 0
                }
                // Every ACTION_START (fresh start or auto-switch reconnect) begins a new
                // connection attempt -- record when, so applyStatusSnapshot() can tell a
                // snapshot reporting on THIS attempt apart from one left over from a past one.
                currentAttemptStartMs = watchdogNowMs()
                // Paired monotonic baseline -- see currentAttemptStartElapsedRealtimeMs's
                // declaration comment for why this is captured alongside the wall-clock value.
                currentAttemptStartElapsedRealtimeMs = elapsedRealtimeMs()
                // Bump alongside currentAttemptStartMs: see connectionAttemptGeneration's
                // declaration comment for the stop-then-restart race this closes (round 12), and
                // for why this is incrementAndGet() rather than `+= 1` (R20-1, fix-cycle 21).
                connectionAttemptGeneration.incrementAndGet()
                if (config.isNullOrBlank()) { AppLog.e(TAG, "No config to start"); stopSelf(); return START_NOT_STICKY }
                if (isReconnect) {
                    // R18-1 (fix-cycle 19, QG9-1/QG9-2, docs/qa-evidence/86cb35fbt-vpn-foreground-
                    // service-crash-gate-9.md): arm reconnectDispatchPendingGeneration HERE, right
                    // after the blank-config early return immediately above, not further down right
                    // before postAtTime() (where it used to sit). That lower placement left a
                    // several-statement window -- SharedPreferences reads and a write, an AppLog.i
                    // call -- during which dispatchAutoSwitcherOnEngineLevel()'s suppression
                    // predicate was structurally false, letting a stray AIDL level from the
                    // just-stopped engine reach ServerAutoSwitcher and skip the newly selected
                    // server. Deliberately does NOT sit BEFORE the isNullOrBlank()/stopSelf() early
                    // return directly above: gate-9 proved by mutation that placement creates a
                    // reachable permanent latch -- a blank-config reconnect never reaches the
                    // deferred Runnable below, so clearMarkerIfOwn() never runs and this marker
                    // stays == connectionAttemptGeneration forever, suppressing every subsequent
                    // AIDL level until a later ACTION_START. See
                    // reconnectDispatchPendingGeneration's declaration comment.
                    reconnectDispatchPendingGeneration = connectionAttemptGeneration.get()
                }
                val targetIp = runCatching { SelectedCountryStore.getIpForConfig(applicationContext, config) }.getOrNull()
                    ?: runCatching { SelectedCountryStore.currentServer(applicationContext)?.ip }.getOrNull()
                try {
                    SelectedCountryStore.ensureIndexForConfig(applicationContext, config, targetIp)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to align server index with config being started", e)
                }
                run {
                    val titleStr = title?.let { ": $it" } ?: ""
                    val position = runCatching { SelectedCountryStore.getCurrentPosition(applicationContext) }.getOrNull()
                    val positionStr = position?.let { "${it.first}/${it.second}" } ?: "unknown"
                    val ipStr = targetIp ?: runCatching { SelectedCountryStore.currentServer(applicationContext)?.ip }.getOrNull()
                    AppLog.i(TAG, "Session attempt ${sessionAttempt} (serversInCountry=${totalServersStr()}, server=${positionStr}, ip=${ipStr ?: "<none>"})${titleStr}")
                }
                try {
                    SelectedCountryStore.saveLastStartedConfig(applicationContext, title, config, targetIp)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to persist last started config", e)
                }
                ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                suppressEngineState = false
                if (isReconnect) {
                    // See ENGINE_RECONNECT_DISPATCH_BUFFER_MS's declaration comment (fix-cycle 13,
                    // 86cb35fbt). Only auto-switch retries (isReconnect == true) can possibly be
                    // racing a stop they themselves just issued against the engine's own async
                    // teardown -- delay just the engine-facing dispatch, not any of the
                    // bookkeeping/state work above, which must stay synchronous with this
                    // onStartCommand() invocation exactly as before.
                    val dispatchGeneration = connectionAttemptGeneration.get()
                    // reconnectDispatchPendingGeneration is already armed to this same generation
                    // above (R18-1, fix-cycle 19) so dispatchAutoSwitcherOnEngineLevel() can
                    // suppress any AIDL level from the just-stopped engine that arrives before this
                    // Runnable actually runs -- see reconnectDispatchPendingGeneration's declaration
                    // comment. No statement between that arm and this capture can rebump
                    // connectionAttemptGeneration, so dispatchGeneration is guaranteed equal to what
                    // was armed; re-assigning here would be redundant, not incorrect, but every
                    // extra writer to this field is itself part of the acknowledged root cause of
                    // this defect family (see the field's declaration comment), so this fix
                    // intentionally does not add a third one.
                    // Tagged with reconnectEngineDispatchToken (R14-2) instead of a bare
                    // postDelayed() so teardown can cancel this specific dispatch -- see the
                    // token's declaration comment. This is the second line of defence for R14-1:
                    // even where the guard below is defeated (as the preserveReconnect ACTION_STOP
                    // branch was), the sweep at that same branch now removes this dispatch outright.
                    statusHandler.postAtTime(Runnable {
                        // R16-1 (fix-cycle 17, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
                        // crash-review-16.md): clear ONLY if the marker still belongs to THIS
                        // Runnable's own generation. Two reconnect buffers can be pending at once
                        // (an earlier one superseded by a newer ACTION_START, but not yet run --
                        // see reconnectStart_supersededByNewerReconnectStart_dispatchesOnlyOnce),
                        // and reconnectDispatchPendingGeneration always holds only the MOST RECENT
                        // buffer's generation. An unconditional clear here would let the earlier,
                        // superseded buffer's Runnable wipe the newer, still-pending buffer's
                        // marker out from under it the moment the earlier one resolves -- reopening
                        // dispatchAutoSwitcherOnEngineLevel()'s stray-level suppression window for
                        // the newer buffer until IT resolves. See reconnectDispatchPendingGeneration's
                        // declaration comment.
                        //
                        // R5-1 (fix-cycle 18, PR #127 review round 5, thread 3793613337): WHERE this
                        // clear happens matters as much as the generation guard above. reconnectDispatchPendingGeneration
                        // is read directly (not via statusHandler) on the AIDL binder thread inside
                        // dispatchAutoSwitcherOnEngineLevel() -- so clearing it before startIcsOpenVpn()
                        // has actually asked the new engine to start left a genuine cross-thread window:
                        // a late binder callback landing in that gap observes -1 (already cleared),
                        // falls straight through the stale-level suppression guard, and queues an
                        // auto-switch dispatch that runs right after this Runnable with every generation
                        // guard already satisfied -- stopping and skipping the server this very Runnable
                        // just started, without it ever getting to run. The guard-failure branches below
                        // do not dispatch to the engine at all, so they still self-invalidate immediately
                        // (nothing pending to protect); only the success path defers the clear to
                        // immediately after startIcsOpenVpn() returns -- still synchronously inside this
                        // same Runnable execution, so no new window opens, the old one is simply moved to
                        // the far side of the one call it exists to guard.
                        fun clearMarkerIfOwn() {
                            if (reconnectDispatchPendingGeneration == dispatchGeneration) {
                                reconnectDispatchPendingGeneration = -1
                            }
                        }
                        // Mirrors dispatchAutoSwitcherOnEngineLevel's guard: a genuine user/system
                        // stop landing inside this buffer window, or a newer ACTION_START having
                        // already superseded this one, must make this a no-op rather than reaching
                        // into the engine for an attempt that is no longer current.
                        if (userInitiatedStop || serviceDestroyed) {
                            clearMarkerIfOwn()
                            // AppLog.i, not .d -- AppReleaseTree drops DEBUG in release builds, and
                            // this is the one field-visible proof that the guard actually engaged
                            // (R14-3, same reasoning as ServerAutoSwitcher.kt:197's R7-2 fix).
                            AppLog.i(TAG, "Reconnect engine-dispatch buffer elapsed but stop/destroy landed first; skipping start")
                            return@Runnable
                        }
                        if (connectionAttemptGeneration.get() != dispatchGeneration) {
                            clearMarkerIfOwn()
                            AppLog.i(TAG, "Reconnect engine-dispatch buffer elapsed but a newer attempt has begun; skipping start")
                            return@Runnable
                        }
                        startIcsOpenVpn(config, title)
                        clearMarkerIfOwn()
                    }, reconnectEngineDispatchToken, SystemClock.uptimeMillis() + ENGINE_RECONNECT_DISPATCH_BUFFER_MS)
                } else {
                    startIcsOpenVpn(config, title)
                }
            }
            VpnManager.ACTION_STOP -> {
                AppLog.i(TAG, "ACTION_STOP")
                exitControllerForeground()
                oneShotSyncRequested = false
                oneShotSyncReceivedInitialState = false
                statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
                statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
                statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
                pauseActionInFlight = false
                resumeActionInFlight = false
                statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                val preserveReconnect = intent.getBooleanExtra(VpnManager.extraPreserveReconnectKey(this), false)
                if (preserveReconnect) {
                    AppLog.d(TAG, "Preserving reconnect hint/state for retry stop")
                    userInitiatedStop = false
                    userInitiatedStart = true
                    ignoreConnectedUntilNotConnected = false
                    // R14-1 (fix-cycle 14, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-
                    // review-14.md): bump here too, mirroring finishStopFlowConfirmed()'s round-12
                    // precedent (see that function's declaration comment above for the full
                    // rationale). This is the ONLY stop ServerAutoSwitcher's retry path ever issues
                    // (VpnManager.stopVpn(preserveReconnectHint = true) at ServerAutoSwitcher.kt:293
                    // and :509) -- and it sets userInitiatedStop=false (just above) and leaves
                    // serviceDestroyed false, so without this bump it would pass all three checks
                    // in the ENGINE_RECONNECT_DISPATCH_BUFFER_MS deferred dispatch's guard
                    // unchanged, letting that dispatch fire mid-stop with a superseded config and
                    // re-arm a fresh engine FGS obligation -- structurally recreating the crash this
                    // buffer exists to widen. Bumping here closes that window with the same
                    // mechanism round 12 already introduced. incrementAndGet(), not `+= 1` --
                    // see connectionAttemptGeneration's declaration comment (R20-1, fix-cycle 21).
                    connectionAttemptGeneration.incrementAndGet()
                    // R14-2: sweep the reconnect engine-dispatch token here too (defence in depth
                    // alongside the generation bump above) -- see reconnectEngineDispatchToken's
                    // declaration comment.
                    statusHandler.removeCallbacksAndMessages(reconnectEngineDispatchToken)
                    statusHandler.removeCallbacks(stopRetryRunnable)
                    statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
                    statusHandler.removeCallbacks(stopBindTimeoutRunnable)
                    requestStopIcsOpenVpn()
                } else {
                    startUserStopTeardown("user_action", forceReset = true)
                }
            }
            VpnManager.ACTION_STOP_IF_IDLE -> {
                AppLog.d(TAG, "ACTION_STOP_IF_IDLE")
                if (ConnectionStateManager.state.value != ConnectionState.DISCONNECTED) {
                    AppLog.d(TAG, "Ignoring stop-if-idle while VPN is active")
                    return START_NOT_STICKY
                }
                // QG4-3 (fix-cycle 8, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-4.md):
                // same hasRecentActionStartDispatch() marker guard as the cycle-7 site and QG4-2's
                // finishStopFlowConfirmed() site. review-8's "FIFO ordering makes this safe" argument
                // only covers dispatch order (STOP_IF_IDLE issued after ACTION_START is always
                // delivered after it); it does not cover a fast Connect tap that arms a fresh FGS
                // obligation AFTER STOP_IF_IDLE was dispatched but BEFORE it is actually delivered
                // here -- this exact interleaving is the QA reproduction gesture (background, then
                // fast reconnect tap). state above can still read stale DISCONNECTED in that case.
                if (VpnManager.hasRecentActionStartDispatch()) {
                    AppLog.i(TAG, "Stop-if-idle stopSelf() aborted: recent ACTION_START dispatch still in flight")
                    return START_NOT_STICKY
                }
                exitControllerForeground()
                stopSelf()
            }
            VpnManager.ACTION_SYNC_STATUS -> {
                AppLog.d(TAG, "ACTION_SYNC_STATUS")
                if (ConnectionStateManager.state.value == ConnectionState.DISCONNECTED) {
                    exitControllerForeground()
                }
                oneShotSyncRequested = true
                oneShotSyncReceivedInitialState = false
                statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
                statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
                statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
                if (!boundToStatus) bindStatusService()
                val snapshotApplied = trySyncStatusSnapshot()
                if (!snapshotApplied) {
                    statusHandler.postDelayed(oneShotSyncTimeoutRunnable, ONE_SHOT_SYNC_TIMEOUT_MS)
                }
            }
            else -> {
                val action = intent?.getStringExtra(VpnManager.actionKey(this))
                when (action) {
                    VpnManager.ACTION_PAUSE -> {
                        AppLog.i(TAG, "ACTION_PAUSE")
                        pauseActionInFlight = true
                        pauseActionStartedMs = System.currentTimeMillis()
                        statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                        statusHandler.postDelayed(pauseActionTimeoutRunnable, PAUSE_CONFIRMATION_TIMEOUT_MS)
                        try {
                            startService(Intent(this, de.blinkt.openvpn.core.OpenVPNService::class.java).apply {
                                setAction(ENGINE_ACTION_PAUSE_VPN)
                            })
                            AppLog.d(TAG, "Forwarded PAUSE_VPN to engine, waiting for PAUSED confirmation (timeout=${PAUSE_CONFIRMATION_TIMEOUT_MS}ms)")
                        } catch (e: Exception) {
                            AppLog.w(TAG, "Failed to forward PAUSE_VPN to engine", e)
                            statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                            statusHandler.post(pauseActionTimeoutRunnable)
                        }
                    }
                    VpnManager.ACTION_RESUME -> {
                        AppLog.i(TAG, "ACTION_RESUME")
                        pauseActionInFlight = false
                        statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                        resumeActionInFlight = true
                        statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                        statusHandler.postDelayed(resumeActionTimeoutRunnable, RESUME_CONFIRMATION_TIMEOUT_MS)
                        try {
                            startService(Intent(this, de.blinkt.openvpn.core.OpenVPNService::class.java).apply {
                                setAction(ENGINE_ACTION_RESUME_VPN)
                            })
                            AppLog.d(TAG, "Forwarded RESUME_VPN to engine, waiting for CONNECTED confirmation (timeout=${RESUME_CONFIRMATION_TIMEOUT_MS}ms)")
                        } catch (e: Exception) {
                            AppLog.w(TAG, "Failed to forward RESUME_VPN to engine", e)
                            resumeActionInFlight = false
                            statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                            ConnectionStateManager.cancelResumeTransition()
                            ConnectionStateManager.updateState(ConnectionState.PAUSED)
                        }
                    }
                    else -> {
                        if (!action.isNullOrBlank()) {
                            AppLog.w(TAG, "Unknown action: $action")
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun scheduleOneShotStop(delayMs: Long = ONE_SHOT_STOP_DELAY_MS) {
        if (!oneShotSyncRequested) return
        statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
        // Restarting the stage-1 decision timer invalidates any stage-2 confirmation already
        // in flight from a previous decision -- cancel it too so a stale confirmed-runnable
        // can't fire stopSelf() after this fresh scheduling.
        statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
        statusHandler.postDelayed(stopAfterOneShotSyncRunnable, delayMs)
    }

    private fun onOneShotInitialStateSynced(reason: String) {
        if (!oneShotSyncRequested || oneShotSyncReceivedInitialState) return
        oneShotSyncReceivedInitialState = true
        statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
        AppLog.d(TAG, "One-shot initial state synced from $reason")
        scheduleOneShotStop()
    }

    private val pauseActionTimeoutRunnable = Runnable {
        if (!pauseActionInFlight) return@Runnable
        if (userInitiatedStop) return@Runnable
        val elapsedMs = System.currentTimeMillis() - pauseActionStartedMs
        pauseActionInFlight = false
        val (level, detail) = getLatestObservedEngineState()
        AppLog.w(TAG, "Pause action timeout after ${elapsedMs}ms: engine did not report PAUSED (lastLevel=${level ?: "<null>"})")
        try {
            when (level) {
                ConnectionStatus.LEVEL_CONNECTED -> {
                    // Restore connected state through valid transition path from PAUSING.
                    ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                    ConnectionStateManager.updateState(ConnectionState.CONNECTED)
                }
                ConnectionStatus.LEVEL_START,
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
                ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                    ConnectionStateManager.updateState(ConnectionState.CONNECTING)
                }
                ConnectionStatus.LEVEL_VPNPAUSED -> {
                    ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_VPNPAUSED, detail)
                }
                else -> Unit
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to reconcile app state after pause timeout", e)
        }
    }

    private val resumeActionTimeoutRunnable = Runnable {
        if (!resumeActionInFlight) return@Runnable
        if (userInitiatedStop) return@Runnable
        resumeActionInFlight = false
        val (level, detail) = getLatestObservedEngineState()
        AppLog.w(TAG, "Resume action timeout: engine did not confirm CONNECTED (lastLevel=${level ?: "<null>"})")
        try {
            ConnectionStateManager.cancelResumeTransition()
            if (level != null) {
                ConnectionStateManager.updateFromEngine(level, detail)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to reconcile app state after resume timeout", e)
        }
    }

    private fun startIcsOpenVpn(ovpnConfig: String, displayName: String?) {
        try {
            val cp = ConfigParser()
            val isr = InputStreamReader(ByteArrayInputStream(ovpnConfig.toByteArray()))
            cp.parseConfig(isr)
            val profile: VpnProfile = cp.convertProfile().apply {
                mName = displayName?.ifBlank { null } ?: (try { getString(R.string.app_name) } catch (_: Exception) { applicationInfo.loadLabel(packageManager)?.toString() ?: "VPN" })
                if (mCompatMode == 0) mCompatMode = DEFAULT_COMPAT_MODE
            }
            applyAppFilter(profile)
            applyDnsSettings(profile)
            ProfileManager.setTemporaryProfile(this, profile)
            VPNLaunchHelper.startOpenVpn(profile, applicationContext, null, true)
            AppLog.i(TAG, "Requested engine start (profile=${profile.mName})")
        } catch (e: ConfigParseError) {
            AppLog.e(TAG, "OVPN parse error", e); stopSelf()
        } catch (e: Exception) {
            AppLog.e(TAG, "Start error", e); stopSelf()
        }
    }

    private fun applyAppFilter(profile: VpnProfile) {
        // Establish the safe state (nothing excluded, list interpreted as a disallow list) BEFORE
        // the fallible read. loadExcludedPackages() can throw -- getStringSet raises
        // ClassCastException on a corrupted or wrong-typed preference -- and if it did so while
        // these two assignments came after it, the profile would keep whatever it already carried.
        // Do not reorder: this method must never leave app-routing directives half-applied.
        profile.mAllowedAppsVpn.clear()
        profile.mAllowedAppsVpnAreDisallowed = true
        try {
            val excluded = AppFilterStore.loadExcludedPackages(applicationContext)
            if (excluded.isNotEmpty()) {
                profile.mAllowedAppsVpn.addAll(excluded)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "Failed to apply app filter", t)
        }
    }

    private fun applyDnsSettings(profile: VpnProfile) {
        val option = try {
            UserSettingsStore.load(applicationContext).dnsOption
        } catch (_: Exception) {
            DnsOption.SERVER
        }
        val config = DnsOptions.resolve(option)
        if (!config.overrideDns) {
            profile.mOverrideDNS = false
            AppLog.i(TAG, "DNS apply: option=${option.name}, override=false (use server DNS)")
            return
        }
        profile.mOverrideDNS = true
        profile.mDNS1 = config.primary ?: ""
        profile.mDNS2 = config.secondary ?: ""
        AppLog.i(TAG, "DNS apply: option=${option.name}, dns1=${profile.mDNS1}, dns2=${profile.mDNS2}")
    }

    private fun requestStopIcsOpenVpn() {
        if (!boundToEngine) {
            val engineIntent = Intent(this, de.blinkt.openvpn.core.OpenVPNService::class.java).apply {
                action = de.blinkt.openvpn.core.OpenVPNService.START_SERVICE
            }
            val bound = bindService(engineIntent, engineConnection, Context.BIND_AUTO_CREATE)
            AppLog.d(TAG, "Binding engine to stop: $bound")
            if (!bound) {
                stopLastFailureReason = "bind_failed"
                stopAttempt += 1
                AppLog.w(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} attempt=$stopAttempt dispatch=not_sent reason=bind_failed")
                scheduleStopRetryOrFail("bind_failed")
                return
            }
            stopBindPending = true
            statusHandler.removeCallbacks(stopBindTimeoutRunnable)
            statusHandler.postDelayed(stopBindTimeoutRunnable, STOP_BIND_TIMEOUT_MS)
        } else tryStopVpn()
    }

    private fun tryStopVpn() {
        if (!userInitiatedStop) {
            val stopped = try {
                engineBinder?.stopVPN(false) ?: false
            } catch (e: RemoteException) {
                AppLog.e(TAG, "Binder stop error", e)
                false
            }
            AppLog.i(TAG, "stopVPN invoked, result=$stopped")
            if (boundToEngine) {
                try {
                    unbindService(engineConnection)
                } catch (e: Exception) {
                    AppLog.w(TAG, "Failed to unbind engine after stop", e)
                }
                boundToEngine = false
            }
            return
        }

        if (stopAttempt >= STOP_DISPATCH_MAX_ATTEMPTS) {
            markStopFailure("dispatch_attempt_limit")
            return
        }

        stopAttempt += 1
        stopBindPending = false
        statusHandler.removeCallbacks(stopBindTimeoutRunnable)

        try {
            val stopped = engineBinder?.stopVPN(false) ?: false
            AppLog.i(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} attempt=$stopAttempt dispatch_result=$stopped")
            if (stopped) {
                stopAwaitingConfirmation = true
                stopLastFailureReason = null
                statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
                statusHandler.postDelayed(stopConfirmationTimeoutRunnable, STOP_CONFIRMATION_TIMEOUT_MS)
            } else {
                stopLastFailureReason = "dispatch_false"
                AppLog.w(TAG, "stop_flow requestId=${stopRequestId ?: "<none>"} attempt=$stopAttempt dispatch=failed reason=dispatch_false")
                scheduleStopRetryOrFail("dispatch_false")
            }
        } catch (e: RemoteException) {
            AppLog.e(TAG, "Binder stop error", e)
            stopLastFailureReason = "binder_exception"
            scheduleStopRetryOrFail("binder_exception")
        }
    }

    private fun handleEngineLevelForStop(level: ConnectionStatus, source: String) {
        if (!userInitiatedStop) return
        when (level) {
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.UNKNOWN_LEVEL -> {
                finishStopFlowConfirmed(level, source)
            }
            else -> Unit
        }
    }

    private fun stopSelfSafely() { stopSelf() }

    override fun onDestroy() {
        // Set before anything else -- including the autoSwitchDispatchToken sweep a few lines
        // below and unregisterStatusCallback() further down -- so there is no window where a
        // binder thread reading this flag observes a stale false. See the field's doc comment
        // for why this is distinct from userInitiatedStop.
        serviceDestroyed = true
        // R11-1: see isInstanceAlive's declaration comment above.
        isInstanceAlive = false
        exitControllerForeground()
        unregisterAppLifecycleObserver()
        super.onDestroy()
        VpnStatus.removeStateListener(this)
        VpnStatus.removeLogListener(this)
        try { VpnStatus.removeByteCountListener(this) } catch (_: Exception) {}
        statusHandler.removeCallbacks(statusRebindRunnable)
        statusHandler.removeCallbacks(stopAfterOneShotSyncRunnable)
        statusHandler.removeCallbacks(stopAfterOneShotSyncConfirmedRunnable)
        statusHandler.removeCallbacks(oneShotSyncTimeoutRunnable)
        statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
        statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
        statusHandler.removeCallbacks(stopRetryRunnable)
        statusHandler.removeCallbacks(stopConfirmationTimeoutRunnable)
        statusHandler.removeCallbacks(stopBindTimeoutRunnable)
        statusHandler.removeCallbacksAndMessages(autoSwitchDispatchToken)
        // R14-2: sweep the reconnect engine-dispatch token here too -- see its declaration
        // comment. Without this, a deferred dispatch queued before onDestroy() would retain a
        // strong reference to this Service for up to ENGINE_RECONNECT_DISPATCH_BUFFER_MS after
        // destroy (the serviceDestroyed guard inside it still makes it a no-op, but the reference
        // leak and the missing-from-cleanup asymmetry with every other deferred dispatch in this
        // class were both flagged findings).
        statusHandler.removeCallbacksAndMessages(reconnectEngineDispatchToken)
        trafficHandler.removeCallbacks(trafficPollRunnable)
        lastPolledDatapoint = null
        lastPolledState = null
        if (boundToStatus) {
            try { statusBinder?.unregisterStatusCallback(statusCallbacks) } catch (_: Exception) {}
            try { unbindService(statusConnection) } catch (_: Exception) {}
            boundToStatus = false
            statusBinder = null
        }
        if (boundToEngine) { try { unbindService(engineConnection) } catch (e: Exception) { AppLog.w(TAG, "Failed to unbind engine on destroy", e) }; boundToEngine = false }
        serviceScope.cancel()
        ServerAutoSwitcher.v2HydrationCallback = null
        ServerAutoSwitcher.probeRequestQueue = null
        probeQueue = null
        AppLog.d(TAG, "Service destroyed and listener removed")
    }

    private fun enterControllerForeground(): Boolean {
        // Always (re)issue Service.startForeground() below, even if controllerForegroundActive is
        // already true. A genuine ACTION_START must get a fresh startForeground() call to satisfy
        // Android's foreground-service-start timing requirement, regardless of any prior
        // controllerForegroundActive state left over from an earlier ACTION_SYNC_STATUS-triggered
        // onCreate() call. Repeated/redundant startForeground() calls are idempotent and Android-
        // supported (they just (re)show/update the notification), so this is safe.
        try {
            val iconRes = if (applicationInfo.icon != 0) applicationInfo.icon else android.R.drawable.stat_sys_warning
            val title = runCatching { getString(R.string.vpn_notification_title_connecting) }.getOrElse { "VPN connecting" }
            val text = runCatching { getString(R.string.vpn_notification_text_connecting) }.getOrElse { "Establishing secure connection..." }
            val notification = NotificationCompat.Builder(
                this,
                de.blinkt.openvpn.core.OpenVPNService.NOTIFICATION_CHANNEL_NEWSTATUS_ID
            )
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            startForeground(CONTROLLER_NOTIFICATION_ID, notification)
            controllerForegroundActive = true
            return true
        } catch (t: Throwable) {
            // QG4-1 (fix-cycle 8, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-4.md):
            // do NOT call stopSelf() here. ACTION_START calls this method AFTER its own
            // startForegroundService() has already been dispatched (VpnManager.startVpn()), so the
            // resulting FGS-start obligation is still undischarged at this point -- calling
            // stopSelf() here reproduces exactly the bringDownServiceLocked()+fgRequired shape this
            // whole fix-flow exists to eliminate, converting a recoverable "could not show the
            // notification" failure into RemoteServiceException$ForegroundServiceDidNotStartInTimeException.
            // Not needed for correctness either: ACTION_START's own dispatcher (":998") already
            // returns START_NOT_STICKY when this returns false, and onCreate()'s call ignores the
            // return value entirely -- both callers already treat "false" as sufficient signal.
            AppLog.e(TAG, "Failed to enter controller foreground", t)
            controllerForegroundActive = false
            return false
        }
    }

    private fun exitControllerForeground() {
        if (!controllerForegroundActive) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        controllerForegroundActive = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @androidx.annotation.MainThread
    override fun updateState(
        state: String?,
        logmessage: String?,
        localizedResId: Int,
        level: ConnectionStatus,
        intent: Intent?
    ) {
        if (!shouldUseVpnStatus()) {
            updateStatusSource(StatusSource.AIDL, "AIDL fresh; ignore VpnStatus")
            logEngineStateChange("VPN_STATUS", level, state)
            if (shouldSupplementAidlWithVpnStatus(level)) {
                syncEngineState(level, state, allowAutoSwitch = false)
            }
            return
        }
        updateStatusSource(StatusSource.VPN_STATUS, "VpnStatus update")
        logEngineStateChange("VPN_STATUS", level, state)
        val failureLevelsHandledByService = setOf(
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED
        )
        if (level !in failureLevelsHandledByService) {
            AppLog.d(TAG, "Auto-switch source=VPN_STATUS (updateState)")
            try { ServerAutoSwitcher.onEngineLevel(applicationContext, level, "VPN_STATUS") } catch (e: Exception) { AppLog.w(TAG, "Failed to notify auto-switcher from updateState", e) }
        }
        if (maybeStartStaleStopReconciliation(level, "VPN_STATUS")) return
        maybeClearStaleStopIntentOnIdleLevel(level, "VPN_STATUS")
        if (shouldIgnoreLevelAfterUserStop(level)) return
        ConnectionStateManager.updateFromEngine(level, state)
        handleEngineLevelForStop(level, "VPN_STATUS")
        if (suppressEngineState) return

        if (userInitiatedStart && level in AUTO_SWITCH_LEVELS && !ConnectionStateManager.reconnectingHint.value) {
            val autoSwitchEnabled = try { com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore.load(applicationContext).autoSwitchWithinCountry } catch (_: Exception) { true }
            if (!autoSwitchEnabled) {
                AppLog.d(TAG, "Auto-switch disabled; skipping engine auto-switch path")
            } else {
                val candidates = try { SelectedCountryStore.getServers(applicationContext).size } catch (_: Exception) { -1 }
                if (candidates >= 0) AppLog.d(TAG, "Auto-switch candidates in selected country: ${candidates}")
                val vpnStatusFailingServerId = if (level != ConnectionStatus.LEVEL_NONETWORK) {
                    SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)
                } else 0
                val next = SelectedCountryStore.nextServer(applicationContext)
                if (vpnStatusFailingServerId != 0) {
                    try { probeQueue?.enqueue(vpnStatusFailingServerId) } catch (e: Exception) { AppLog.w(TAG, "VPN_STATUS fallback: failed to enqueue hardprobe for serverId=$vpnStatusFailingServerId", e) }
                }
                val title = SelectedCountryStore.getSelectedCountry(applicationContext)
                if (next != null) {
                val position = runCatching { SelectedCountryStore.getCurrentPosition(applicationContext) }.getOrNull()
                val positionStr = position?.let { "${it.first}/${it.second}" } ?: "unknown"
                AppLog.i(TAG, "Auto-switching to next server in country list: ${title} -> ${next.city} (server=${positionStr}, ip=${next.ip ?: "<none>"})")
                try { ConnectionStateManager.setReconnectingHint(true); AppLog.d(TAG, "reconnectHint=true (engine auto-switch)") } catch (e: Exception) { AppLog.w(TAG, "Failed to set reconnecting hint for engine auto-switch", e) }
                try { ServerAutoSwitcher.beginChainedSwitch(applicationContext, next.config, title) } catch (e: Exception) { AppLog.e(TAG, "Failed to begin chained server switch", e) }
                return
              } else {
                  userInitiatedStart = false
                  try { ConnectionStateManager.setReconnectingHint(false); AppLog.d(TAG, "reconnectHint=false (no more servers)") } catch (e: Exception) { AppLog.w(TAG, "Failed to clear reconnecting hint when no more servers", e) }
                AppLog.i(TAG, "Exhausted server list without success after ${sessionAttempt} attempts (serversInCountry=${totalServersStr()})")
              }
            }
        }
        when (level) {
              ConnectionStatus.LEVEL_CONNECTED -> {
                  userInitiatedStart = false
                  userInitiatedStop = false
                  resumeActionInFlight = false
                  statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                AppLog.i(TAG, "Connected after attempt ${sessionAttempt} (serversInCountry=${totalServersStr()})")
            }
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_AUTH_FAILED -> {
                // Reached when auto-switch is disabled (or the level isn't handled by the
                // auto-switch block above): a failed user-initiated start must still clear
                // userInitiatedStart here, otherwise syncEngineState's reconnectPending guard
                // keeps suppressing exitControllerForeground() forever, leaving the "VPN
                // connecting" foreground notification stuck after the failed attempt.
                userInitiatedStart = false
                resumeActionInFlight = false
                statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
            }
            ConnectionStatus.LEVEL_VPNPAUSED -> {
                pauseActionInFlight = false
                statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                AppLog.d(TAG, "Engine reported PAUSED, pause action complete")
            }
            ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT -> {
                AppLog.d(TAG, "Waiting for user input")
            }
            else -> {}
        }
    }

    override fun updateByteCount(inBytes: Long, outBytes: Long, diffIn: Long, diffOut: Long) {
        if (boundToStatus) return
        val now = System.currentTimeMillis()
        val last = lastLocalByteUpdateTs
        lastLocalByteUpdateTs = now
        val deltaMs = if (last > 0) (now - last).coerceAtLeast(1) else 1000L
        val totalDiffBytes = (diffIn + diffOut).coerceAtLeast(0)
        val bitsPerSec = (totalDiffBytes * 8.0) * (1000.0 / deltaMs.toDouble())
        val mbps = bitsPerSec / 1_000_000.0
        ConnectionStateManager.updateSpeedMbps(mbps)
        ConnectionStateManager.updateTraffic(inBytes, outBytes)
    }

    private val statusCallbacks = object : IStatusCallbacks.Stub() {
        override fun newLogItem(item: de.blinkt.openvpn.core.LogItem?) { }

        override fun updateStateString(
            state: String?,
            msg: String?,
            resid: Int,
            level: ConnectionStatus?,
            intent: Intent?
        ) {
            if (level == null) return
            lastStatusSnapshotMs = watchdogNowMs()
            lastLiveStatusMs = lastStatusSnapshotMs
            lastLiveStatusElapsedRealtimeMs = elapsedRealtimeMs()
            staleSnapshotCount = 0
            updateStatusSource(StatusSource.AIDL, "AIDL update")
            logEngineStateChange("AIDL", level, state)
            try {
                syncEngineState(level, state, allowAutoSwitch = true)
                onOneShotInitialStateSynced("AIDL callback")
                if (level == ConnectionStatus.LEVEL_CONNECTED) {
                    resumeActionInFlight = false
                    statusHandler.removeCallbacks(resumeActionTimeoutRunnable)
                    persistLastSuccessfulConfig()
                    tryRestoreTrafficSnapshot()
                } else if (level == ConnectionStatus.LEVEL_VPNPAUSED) {
                    pauseActionInFlight = false
                    statusHandler.removeCallbacks(pauseActionTimeoutRunnable)
                }
            } catch (t: Throwable) {
                AppLog.w(TAG, "Failed to sync state from status service: level=$level state=$state", t)
            }
        }

        override fun connectedVPN(uuid: String?) { }

        override fun notifyProfileVersionChanged(uuid: String?, profileVersion: Int) { }

        override fun updateByteCount(inBytes: Long, outBytes: Long) {
            val now = System.currentTimeMillis()
            val last = lastAidlByteUpdateTs
            val prevIn = aidlLastInBytes
            val prevOut = aidlLastOutBytes
            aidlLastInBytes = inBytes
            aidlLastOutBytes = outBytes
            lastAidlByteUpdateTs = now
            val deltaMs = if (last > 0) (now - last).coerceAtLeast(1) else 1000L
            val diffIn = (inBytes - prevIn).coerceAtLeast(0)
            val diffOut = (outBytes - prevOut).coerceAtLeast(0)
            val totalDiffBytes = diffIn + diffOut
            val bitsPerSec = (totalDiffBytes * 8.0) * (1000.0 / deltaMs.toDouble())
            val mbps = bitsPerSec / 1_000_000.0
            ConnectionStateManager.updateSpeedMbps(mbps)
            ConnectionStateManager.updateTraffic(inBytes, outBytes)
        }
    }

    private fun tryRestoreTrafficSnapshot() {
        val binder = statusBinder ?: return
        val history: TrafficHistory = try {
            binder.trafficHistory
        } catch (e: RemoteException) {
            AppLog.w(TAG, "Failed to get traffic history from status service", e)
            return
        } ?: return

        val seconds = history.seconds
        val minutes = history.minutes
        val hours = history.hours

        val nonEmptyLists = listOf(seconds, minutes, hours).filter { it.isNotEmpty() }
        if (nonEmptyLists.isEmpty()) return

        val earliest = nonEmptyLists
            .map { it.first() }
            .minByOrNull { it.timestamp }
            ?: return
        val latest = nonEmptyLists
            .map { it.last() }
            .maxByOrNull { it.timestamp }
            ?: return

        ConnectionStateManager.restoreConnectionStartIfEmpty(earliest.timestamp)
        ConnectionStateManager.updateTraffic(latest.`in`, latest.out)
    }

    private val statusConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null) {
                AppLog.w(TAG, "Status service connected with null binder; scheduling rebind")
                statusBinder = null
                boundToStatus = false
                scheduleStatusRebind()
                return
            }
            statusBinder = IServiceStatus.Stub.asInterface(service)
            boundToStatus = true
            statusRebindDelayMs = 500L
            updateStatusSource(StatusSource.AIDL, "status service connected")
            AppLog.i(TAG, "Status service connected")
            try {
                service?.linkToDeath(statusDeathRecipient, 0)
            } catch (e: RemoteException) {
                AppLog.w(TAG, "Failed to link status binder death", e)
            }
            try {
                statusBinder?.registerStatusCallback(statusCallbacks)
            } catch (e: RemoteException) {
                AppLog.e(TAG, "Failed to register status callback", e)
            }
            trySyncStatusSnapshot()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            statusBinder = null
            boundToStatus = false
            updateStatusSource(StatusSource.VPN_STATUS, "status service disconnected")
            AppLog.w(TAG, "Status service disconnected")
            scheduleStatusRebind()
        }
    }

    private val trafficPollRunnable = object : Runnable {
        override fun run() {
            try {
                val snapshotBinder = statusBinder
                if (snapshotBinder != null) {
                    val now = watchdogNowMs()
                    if (lastStatusSnapshotMs == 0L || now - lastStatusSnapshotMs > 5_000L) {
                        trySyncStatusSnapshot()
                    }
                }

                val currentState = ConnectionStateManager.state.value
                if (currentState != lastPolledState) {
                    // A watchdog-driven recovery reconnects, so it lands here itself. Carry the
                    // attempt count across that transition -- otherwise the watchdog resets its own
                    // budget every time it spends some of it. Timing fields are deliberately NOT
                    // carried: the new tunnel gets a fresh warmup grace period.
                    val carriedRecoveryAttempts =
                        if (watchdogRecoveryInFlight) watchdogState.recoveryAttempts else 0
                    if (currentState == ConnectionState.CONNECTED) {
                        resetHealthWatchdog(nowMs = watchdogNowMs())
                    } else {
                        lastPolledDatapoint = null
                        resetHealthWatchdog()
                    }
                    watchdogState.recoveryAttempts = carriedRecoveryAttempts
                    lastPolledState = currentState
                }

                // Harden baseline CONNECTED state establishment
                val trafficBinder = statusBinder
                var sampleAdvanced = false
                var trafficDelta = 0L
                if (trafficBinder != null) {
                    val history = try {
                        trafficBinder.trafficHistory
                    } catch (_: Exception) {
                        null
                    }

                    if (history != null) {
                        val seconds = history.seconds
                        val minutes = history.minutes
                        val hours = history.hours
                        val nonEmptyLists = listOf(seconds, minutes, hours).filter { it.isNotEmpty() }
                        if (nonEmptyLists.isNotEmpty()) {
                            val latest = nonEmptyLists.maxByOrNull { it.last().timestamp }!!.last()
                            val previous = lastPolledDatapoint

                            if (previous != null && latest.timestamp > previous.timestamp) {
                                sampleAdvanced = true
                                val diffIn = (latest.`in` - previous.`in`).coerceAtLeast(0L)
                                val diffOut = (latest.out - previous.out).coerceAtLeast(0L)
                                trafficDelta = diffIn + diffOut
                                val deltaMs = (latest.timestamp - previous.timestamp).coerceAtLeast(1L)
                                val bitsPerSec = (trafficDelta * 8.0) * (1000.0 / deltaMs.toDouble())
                                val mbps = bitsPerSec / 1_000_000.0
                                if (shouldPublishTrafficMetrics(currentState)) {
                                    ConnectionStateManager.updateSpeedMbps(mbps)
                                }
                            }

                            if (shouldPublishTrafficMetrics(currentState)) {
                                ConnectionStateManager.updateTraffic(latest.`in`, latest.out)
                            }
                            lastPolledDatapoint = latest
                        }
                    }
                }

                // Only force CONNECTED when we have verified health evidence from traffic samples.
                val engineLevel = ConnectionStateManager.engineLevel.value
                if (shouldForceConnectedState(engineLevel, sampleAdvanced, trafficDelta) &&
                    ConnectionStateManager.state.value != ConnectionState.CONNECTED &&
                    !pauseActionInFlight && !resumeActionInFlight && !userInitiatedStop) {
                    AppLog.i(TAG, "Hardened: Forcing CONNECTED state after engine connected and healthy traffic")
                    ConnectionStateManager.updateState(ConnectionState.CONNECTED)
                }

                if (ConnectionStateManager.state.value == ConnectionState.CONNECTED) {
                    evaluateConnectedHealth(sampleAdvanced = sampleAdvanced, trafficDeltaBytes = trafficDelta)
                }
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) {
                    AppLog.w(TAG, "Error in trafficPollRunnable", e)
                }
            }

            trafficHandler.postDelayed(this, WATCHDOG_POLL_INTERVAL_MS)
        }
    }

    private fun shouldForceConnectedState(
        engineLevel: ConnectionStatus?,
        sampleAdvanced: Boolean,
        trafficDeltaBytes: Long
    ): Boolean {
        return engineLevel == ConnectionStatus.LEVEL_CONNECTED &&
            sampleAdvanced &&
            trafficDeltaBytes >= WATCHDOG_MIN_TRAFFIC_DELTA_BYTES
    }

    private fun shouldPublishTrafficMetrics(currentState: ConnectionState): Boolean {
        return currentState == ConnectionState.CONNECTED
    }

    private fun evaluateConnectedHealth(sampleAdvanced: Boolean, trafficDeltaBytes: Long) {
        val now = watchdogNowMs()
        if (watchdogState.connectedSinceMs == 0L) {
            watchdogState.connectedSinceMs = now
            watchdogState.lastHealthyTimestamp = now
        }

        if (sampleAdvanced && trafficDeltaBytes >= WATCHDOG_MIN_TRAFFIC_DELTA_BYTES) {
            markWatchdogHealthy(now, "traffic", trafficDeltaBytes, trafficVerified = true)
            return
        }

        if (now - watchdogState.connectedSinceMs < WATCHDOG_CONNECTED_WARMUP_MS) {
            AppLog.dThrottled(TAG, "Watchdog: warm-up active", key = "watchdog-warmup")
            return
        }

        if (watchdogState.lastRecoveryTimestamp > 0L && now - watchdogState.lastRecoveryTimestamp < WATCHDOG_RECOVERY_COOLDOWN_MS) {
            AppLog.dThrottled(TAG, "Watchdog: cooldown active", key = "watchdog-cooldown")
            return
        }

        if (watchdogProbeJob?.isActive == true) {
            AppLog.dThrottled(TAG, "Watchdog: probe already in flight", key = "watchdog-probe-in-flight")
            return
        }

        val probeTargets = resolveWatchdogProbeTargets()
        if (probeTargets.isEmpty()) {
            AppLog.w(TAG, "Watchdog: trusted probe target unavailable; treating as failed probe")
            handleConnectedProbeResult(probeSucceeded = false, trafficDeltaBytes = trafficDeltaBytes)
            return
        }

        watchdogProbeJob = serviceScope.launch(watchdogProbeDispatcher) {
            val probeSucceeded = probeTargets.any { target ->
                executeWatchdogProbe(target.host, target.port, WATCHDOG_PROBE_TIMEOUT_MS)
            }
            statusHandler.post {
                if (ConnectionStateManager.state.value != ConnectionState.CONNECTED) return@post
                handleConnectedProbeResult(probeSucceeded, trafficDeltaBytes)
            }
        }
    }

    private fun executeWatchdogProbe(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            watchdogProbe(host, port, timeoutMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Watchdog: probe failed with exception", e)
            false
        }
    }

    private fun handleConnectedProbeResult(probeSucceeded: Boolean, trafficDeltaBytes: Long) {
        val now = watchdogNowMs()
        if (probeSucceeded) {
            // Reachable, but no traffic evidence: clears the failure streak, keeps the budget.
            markWatchdogHealthy(
                now,
                "probe",
                trafficDeltaBytes,
                trafficVerified = trafficDeltaBytes >= WATCHDOG_MIN_TRAFFIC_DELTA_BYTES
            )
            return
        }

        watchdogState.consecutiveFailures += 1
        AppLog.w(
            TAG,
            "Watchdog: unhealthy trafficDelta=${trafficDeltaBytes} probe=false thresholdCount=${watchdogState.consecutiveFailures}/${WATCHDOG_FAILURE_THRESHOLD}"
        )

        if (watchdogState.consecutiveFailures < WATCHDOG_FAILURE_THRESHOLD) return

        if (watchdogState.recoveryAttempts >= WATCHDOG_MAX_RECOVERY_ATTEMPTS) {
            AppLog.e(
                TAG,
                "Watchdog: bounded recovery exhausted; entering fail-safe disconnect"
            )
            triggerWatchdogFailSafeDisconnect("attempt_limit_reached")
            return
        }

        watchdogState.degraded = true
        watchdogState.recoveryAttempts += 1
        watchdogState.lastRecoveryTimestamp = now
        AppLog.i(
            TAG,
            "Watchdog: threshold reached trafficDelta=${trafficDeltaBytes} probe=false thresholdCount=${watchdogState.consecutiveFailures}/${WATCHDOG_FAILURE_THRESHOLD} recoveryAttempt=${watchdogState.recoveryAttempts}/${WATCHDOG_MAX_RECOVERY_ATTEMPTS}"
        )

        val recoveryTarget = resolveWatchdogRecoveryTarget()
        if (recoveryTarget == null) {
            AppLog.e(TAG, "Watchdog: no recovery target available; entering fail-safe disconnect")
            triggerWatchdogFailSafeDisconnect("missing_recovery_target")
            return
        }
        val watchdogServerId = SelectedCountryStore.getCurrentServerIdIfMatchingLastStarted(applicationContext)
        if (watchdogServerId != 0) {
            try { probeQueue?.enqueue(watchdogServerId) } catch (e: Exception) { AppLog.w(TAG, "Watchdog: failed to enqueue hardprobe for serverId=$watchdogServerId", e) }
        }
        try {
            // Set before dispatch: beginChainedSwitch can drive the state change synchronously.
            watchdogRecoveryInFlight = true
            val dispatched =
                watchdogRecoveryStarter(applicationContext, recoveryTarget.config, recoveryTarget.title)
            if (!dispatched) {
                // Nothing was dispatched -- auto-switch is off, or the stop command was rejected.
                // Do not burn the budget on attempts that never happen: that ends in a fail-safe
                // disconnect three cycles later with logs claiming recoveries that did not occur.
                // Fail safe now, for the same reason a missing recovery target does: there is no
                // mechanism to recover with.
                AppLog.e(TAG, "Watchdog: recovery not dispatched; entering fail-safe disconnect")
                triggerWatchdogFailSafeDisconnect("recovery_unavailable")
                return
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Watchdog: failed to dispatch recovery", e)
            triggerWatchdogFailSafeDisconnect("recovery_dispatch_failed")
        }
    }

    /**
     * @param trafficVerified true only when real traffic was observed. A successful TCP probe means
     *   the peer is reachable, which clears the failure streak -- but it is NOT evidence that the
     *   tunnel carries data, so it must not refill the recovery budget. Otherwise a tunnel that
     *   answers probes while passing nothing would reset the bound on every cycle and recover
     *   forever, which is the exact case the budget exists to stop.
     */
    private fun markWatchdogHealthy(
        nowMs: Long,
        source: String,
        trafficDeltaBytes: Long,
        trafficVerified: Boolean
    ) {
        val hadRecoveryState = watchdogState.degraded || watchdogState.recoveryAttempts > 0 || watchdogState.consecutiveFailures > 0
        watchdogState.consecutiveFailures = 0
        watchdogState.degraded = false
        if (trafficVerified) {
            // The recovery chain genuinely succeeded: the budget is spent and refilled.
            watchdogRecoveryInFlight = false
            watchdogState.recoveryAttempts = 0
        }
        watchdogState.lastHealthyTimestamp = nowMs
        watchdogState.lastRecoveryTimestamp = 0L
        AppLog.iThrottled(
            TAG,
            "Watchdog: healthy source=${source} trafficDelta=${trafficDeltaBytes} recovered=${hadRecoveryState} reconnectAfterRestore=${hadRecoveryState}",
            key = "watchdog-healthy-${source}-${hadRecoveryState}"
        )
    }

    private fun resetHealthWatchdog(nowMs: Long = 0L) {
        watchdogProbeJob?.cancel()
        watchdogProbeJob = null
        watchdogState = if (nowMs > 0L) {
            HealthWatchdogState(
                connectedSinceMs = nowMs,
                lastHealthyTimestamp = nowMs
            )
        } else {
            HealthWatchdogState()
        }
    }

    private fun resolveWatchdogProbeTargets(): List<WatchdogProbeTarget> {
        val targets = mutableListOf<WatchdogProbeTarget>()
        resolveActiveTunnelProbeTarget()?.let { targets += it }

        val candidates = listOfNotNull(
            runCatching { ApiConstants.primaryRetrofitBaseUrl() }.getOrNull(),
            ApiConstants.FALLBACK_SERVERS_URL
        )
        targets += candidates
            .mapNotNull { resolveWatchdogProbeTarget(it) }
        return targets.distinctBy { "${it.host}:${it.port}" }
    }

    private fun resolveActiveTunnelProbeTarget(): WatchdogProbeTarget? {
        val lastStarted = runCatching { SelectedCountryStore.getLastStartedConfig(applicationContext) }.getOrNull()
        parseRemoteEndpointFromConfig(lastStarted?.config)?.let { return it }

        val tunnelIp = lastStarted?.ip
            ?: runCatching { SelectedCountryStore.currentServer(applicationContext)?.ip }.getOrNull()
        val host = tunnelIp?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return WatchdogProbeTarget(host = host, port = WATCHDOG_FALLBACK_HTTPS_PORT)
    }

    private fun parseRemoteEndpointFromConfig(config: String?): WatchdogProbeTarget? {
        if (config.isNullOrBlank()) return null
        val remoteLine = config
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotBlank() &&
                    !line.startsWith("#") &&
                    !line.startsWith(";") &&
                    line.startsWith("remote") &&
                    line.getOrNull("remote".length)?.isWhitespace() == true
            }
            ?: return null

        val parts = remoteLine.split(Regex("\\s+"))
        if (parts.size < 2) return null

        val host = parts[1].trim().removePrefix("[").removeSuffix("]")
        if (host.isBlank()) return null

        val port = parts.getOrNull(2)?.toIntOrNull()?.takeIf { it > 0 } ?: WATCHDOG_DEFAULT_OPENVPN_PORT
        return WatchdogProbeTarget(host = host, port = port)
    }

    private fun resolveWatchdogProbeTarget(rawUrl: String): WatchdogProbeTarget? {
        val uri = runCatching { URI(rawUrl) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = if (uri.port > 0) uri.port else WATCHDOG_FALLBACK_HTTPS_PORT
        return WatchdogProbeTarget(host = host, port = port)
    }

    private fun resolveWatchdogRecoveryTarget(): WatchdogRecoveryTarget? {
        val selectedCountry = runCatching { SelectedCountryStore.getSelectedCountry(applicationContext) }.getOrNull()
        val lastStarted = runCatching { SelectedCountryStore.getLastStartedConfig(applicationContext) }.getOrNull()
        if (!lastStarted?.config.isNullOrBlank()) {
            return WatchdogRecoveryTarget(
                config = lastStarted!!.config!!,
                title = lastStarted.country ?: selectedCountry
            )
        }

        val lastSuccessfulConfig = runCatching {
            SelectedCountryStore.getLastSuccessfulConfigForSelected(applicationContext)
        }.getOrNull()
        return if (!lastSuccessfulConfig.isNullOrBlank()) {
            WatchdogRecoveryTarget(lastSuccessfulConfig, selectedCountry)
        } else {
            null
        }
    }

    private fun triggerWatchdogFailSafeDisconnect(reason: String) {
        AppLog.e(TAG, "Watchdog: fail-safe disconnect reason=${reason}")
        // The recovery chain is over either way; do not carry the count into whatever comes next.
        watchdogRecoveryInFlight = false
        watchdogState.recoveryAttempts = 0
        startUserStopTeardown("watchdog_fail_safe", forceReset = true)
    }

    private fun performReachabilityProbe(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    override fun setConnectedVPN(uuid: String) { /* not used */ }

    override fun newLog(logItem: de.blinkt.openvpn.core.LogItem?) {
        if (logItem == null) return
        try {
            val msg = logItem.getString(this)
            val sanitized = redactMessage(msg)
            when (logItem.logLevel) {
                VpnStatus.LogLevel.ERROR -> AppLog.e(TAG, sanitized)
                VpnStatus.LogLevel.WARNING -> AppLog.w(TAG, sanitized)
                VpnStatus.LogLevel.INFO -> AppLog.iThrottled(TAG, sanitized, key = buildLogThrottleKey("ovpn-info", sanitized))
                VpnStatus.LogLevel.VERBOSE -> AppLog.dThrottled(TAG, sanitized, key = buildLogThrottleKey("ovpn-verbose", sanitized))
                else -> AppLog.dThrottled(TAG, sanitized, key = buildLogThrottleKey("ovpn-default", sanitized))
            }
        } catch (e: Exception) { AppLog.w(TAG, "Failed to format OpenVPN log item", e) }
    }

    private fun redactMessage(message: String): String {
        return hexRegex.replace(
            ipv4Regex.replace(
                urlRegex.replace(message, "<url>"),
                "<ip>"
            ),
            "<hex>"
        )
    }

    private fun buildLogThrottleKey(prefix: String, message: String): String {
        val normalized = numberRegex.replace(
            hexRegex.replace(
                ipv4Regex.replace(message.lowercase(), "<ip>"),
                "<hex>"
            ),
            "#"
        )
            .replace(Regex("\\s+"), " ")
            .trim()
        val suffix = if (normalized.length > MAX_THROTTLE_KEY_LENGTH) {
            normalized.take(MAX_THROTTLE_KEY_LENGTH)
        } else {
            normalized
        }
        return "$prefix:$suffix"
    }

    private fun trySyncStatusSnapshot(): Boolean {
        val binder = statusBinder ?: return false
        val snapshot = try {
            binder.lastStatusSnapshot
        } catch (e: RemoteException) {
            AppLog.w(TAG, "Failed to read status snapshot", e)
            statusBinder = null
            boundToStatus = false
            scheduleStatusRebind()
            null
        } ?: return false
        updateStatusSource(StatusSource.AIDL, "AIDL snapshot")
        applyStatusSnapshot(snapshot)
        return true
    }

    private fun applyStatusSnapshot(snapshot: StatusSnapshot) {
        val level = snapshot.level ?: return
        val now = watchdogNowMs()
        val nowElapsedRealtimeMs = elapsedRealtimeMs()
        val ts = snapshot.timestampMs
        // Applied unconditionally to every level -- see staleSnapshotMaxAgeMs's declaration
        // comment above for why round 14 removed the per-level allowlist that used to gate this
        // block.
        if (ts > 0L) {
            // Round 15 fix (Codex P2, comment 3735628745): MainActivityCore.onStart() reattaches
            // to an already-running engine via ACTION_SYNC_STATUS, NOT ACTION_START -- e.g. after
            // the Activity or this Service's process was recreated while the underlying engine
            // connection attempt was still genuinely in progress. currentAttemptStartMs is only
            // ever set inside ACTION_START handling, so a service instance that comes up via
            // ACTION_SYNC_STATUS leaves it at its default 0L (unknown) even though a real attempt
            // exists. Left alone, that forces every snapshot through the age-only fallback below,
            // and the one cached snapshot available for a long-stuck attempt inevitably exceeds
            // staleSnapshotMaxAgeMs, getting rejected on every poll/rebind forever -- resurrecting
            // the exact "stuck on Connecting..." bug this PR fixes, via this lifecycle path
            // instead of ACTION_START's. Fix: the first snapshot an unknown-start instance
            // observes that carries a genuinely active engine level (i.e. NOT one of
            // STOP_TERMINAL_LEVELS, which mean the engine is idle/never started) backfills
            // currentAttemptStartMs to that snapshot's OWN timestamp -- the earliest evidence
            // this instance has of the current attempt. The backfilled value may be later than
            // when the attempt actually started; that is fine and intentional, since it only
            // needs to serve as a baseline for the predates-check below. This snapshot and every
            // later one then compare against a known baseline instead of an unknown 0L, exactly
            // like the ACTION_START path, so a genuinely older/unrelated snapshot delivered
            // afterwards is still correctly rejected by the existing predates-check. Trusting the
            // very first observed snapshot unconditionally is safe here because
            // trySyncStatusSnapshot() reads the AIDL binder's single lastStatusSnapshot -- there
            // is no second, independently-tracked "different past attempt" data point for an
            // unknown-start instance to compare it against; it IS the freshest truth the engine
            // itself has to offer.
            // Round 19 fix (Codex P2, comment 3737217807): classify terminal-ness for this
            // backfill decision using the NORMALIZED level, not the raw `level` read at the top
            // of this function. The raw level and the accompanying `state`/detail string can
            // update on slightly different cadences (the same phenomenon
            // ConnectionStateManager.normalizeEngineLevel's own doc comment describes, and the
            // same class of bug round 14 already fixed for ServerAutoSwitcher's consumption of
            // this data). A recreated controller reattaching via ACTION_SYNC_STATUS can observe a
            // first snapshot whose raw level is still a lagging LEVEL_NONETWORK while its state
            // already reads "CONNECTED" -- i.e. genuinely healthy. Classifying that snapshot as
            // terminal on the raw level alone skips the backfill below, leaving
            // currentAttemptStartMs at 0L, which routes it into the age-only fallback further
            // down -- and that fallback then wrongly rejects a healthy reattachment snapshot as
            // stale purely because it is older than staleSnapshotMaxAgeMs. Normalizing first
            // ensures a raw-lagging-but-actually-connected snapshot is correctly seen as active
            // (not terminal), so the backfill runs and syncEngineState() -- where normalization
            // would otherwise happen -- actually gets to execute.
            val normalizedLevelForBackfill = ConnectionStateManager.normalizeEngineLevel(level, snapshot.state)
            if (currentAttemptStartMs == 0L && normalizedLevelForBackfill !in STOP_TERMINAL_LEVELS) {
                currentAttemptStartMs = ts
                // Estimate what elapsedRealtimeMs() was back when this snapshot's own timestamp
                // (ts) was captured, by subtracting its age (now - ts) from the elapsed-realtime
                // reading taken at THIS backfill moment. Keeps the pairing with
                // currentAttemptStartMs (=ts) consistent with the ACTION_START path, where both
                // fields are captured together at the same instant.
                currentAttemptStartElapsedRealtimeMs = nowElapsedRealtimeMs - (now - ts)
            }
            val ageMs = now - ts
            // A snapshot's absolute age alone cannot tell apart two very different situations:
            // (a) it is a leftover reading from a PAST, different connection attempt (round 8's
            // scenario -- e.g. a cached NONETWORK snapshot the status service never refreshed
            // after a new attempt began) and should be rejected; vs (b) it IS the status of the
            // CURRENT, still-ongoing attempt, just old because that attempt has genuinely been
            // stuck the whole time (round 9's scenario -- e.g. the status service rebinds while
            // push callbacks are stalled and this snapshot is the only data available). Rejecting
            // case (b) starves ServerAutoSwitcher of its only signal and resurrects the original
            // indefinite "stuck on Connecting..." bug through this rebind/poll path. Distinguish
            // them by comparing the snapshot's own timestamp against when the CURRENT attempt
            // started: only a snapshot that predates the current attempt is case (a). When
            // currentAttemptStartMs is unknown (0L, e.g. never went through ACTION_START), the
            // predates-check cannot be evaluated at all, so this falls back to the pre-existing,
            // purely age-based check (ageMs > staleSnapshotMaxAgeMs) -- this keeps every caller
            // that does not track attempt identity exactly as before. See PR #126 round 9 (Codex
            // P2, comment 3733934640).
            //
            // Round 10 fix (Codex P2, comment 3734081106): the "predates" check above must be
            // evaluated INDEPENDENTLY of the absolute-age gate below, not nested inside it. A
            // snapshot's absolute age alone does not prove it belongs to the current attempt --
            // the status service can re-deliver the SAME cached snapshot from a just-replaced
            // attempt on a routine poll shortly after the new attempt starts (e.g. the new
            // attempt begins ~5s after the old snapshot was captured, then a poll ~2s later
            // redelivers that same old snapshot). Its absolute age is then still under
            // staleSnapshotMaxAgeMs purely because little wall-clock time has passed, even
            // though its timestamp is known to predate currentAttemptStartMs. Nesting the
            // predates-check inside `ageMs > staleSnapshotMaxAgeMs` let that case slip through
            // uncaught, since the outer age gate never fired. The actual priority, per the
            // if/else below: when currentAttemptStartMs is known, that is the ONLY test applied
            // -- a snapshot predating the current attempt is always rejected regardless of age,
            // and a snapshot that does NOT predate it (i.e. belongs to the current, still-stuck
            // attempt) is always accepted regardless of age, per round 9. Only when
            // currentAttemptStartMs is unknown does the pre-existing age-based check
            // (ageMs > staleSnapshotMaxAgeMs) apply instead.
            val currentAttemptStartKnown = currentAttemptStartMs > 0L
            val knownToPredateCurrentAttempt = currentAttemptStartKnown && ts < currentAttemptStartMs
            // Round 16 fix (Codex P2, comment 3735937824): the predates-check above compares two
            // wall-clock readings (ts, currentAttemptStartMs) taken via watchdogNowMs() /
            // System.currentTimeMillis() -- including on the engine side, since
            // StatusSnapshot.timestampMs is produced by OpenVPNStatusService using
            // System.currentTimeMillis() too, outside this app's control. If the device wall
            // clock is corrected BACKWARD at any point during the current attempt's lifetime
            // (e.g. automatic NTP sync, a user/system clock change), every snapshot delivered
            // after the correction reads earlier than currentAttemptStartMs (captured before the
            // correction, under the old/higher clock), so EVERY subsequent snapshot looks like it
            // predates the attempt and gets rejected -- until wall-clock time naturally advances
            // back past the stale currentAttemptStartMs value. That silently defeats the whole
            // stale-push auto-switch mechanism via a clock-jump vector, distinct from the
            // lifecycle-path/level-enumeration vectors rounds 9-15 closed.
            //
            // SystemClock.elapsedRealtime() is monotonic and immune to wall-clock corrections.
            // Pairing it with currentAttemptStartMs (see currentAttemptStartElapsedRealtimeMs's
            // declaration) lets us detect this: if LESS wall-clock time appears to have passed
            // since the attempt started (now - currentAttemptStartMs) than the REAL, monotonic
            // time that has actually passed (nowElapsedRealtimeMs -
            // currentAttemptStartElapsedRealtimeMs), the wall clock must have moved backward by
            // approximately that difference at some point during this attempt -- direct evidence
            // of a clock artifact, not a genuinely old/unrelated snapshot. When the snapshot's own
            // predates-gap is fully covered by that estimated jump size (plus a small slack for
            // read skew between the separate clock calls), it is trusted as genuine current-attempt
            // data delivered after the correction, not leftover data from a truly past attempt.
            //
            // Residual limitation, stated honestly rather than overclaimed: this only explains a
            // predates-gap up to the size of the DETECTED jump. An arbitrarily long-running
            // attempt combined with an arbitrarily large backward jump can, in principle, still
            // mask a genuinely-stale leftover snapshot whose gap happens to fall within the
            // detected jump size -- an inherent limit of reasoning about wall-clock data with no
            // independent monotonic timestamp of its own. currentAttemptStartElapsedRealtimeMs is
            // left at its default 0L by any path that sets currentAttemptStartMs without it (e.g.
            // pre-round-16 reflection-based unit tests), so this safety net never activates for
            // those, preserving the exact pre-round-16 behavior.
            // Round 17 fix (Codex P2, comment 3736234637): the jump-size waiver above (round 16)
            // only compared the snapshot's predates-gap against the estimated jump size, which
            // wrongly waives rejection for a genuinely stale, small-gap prior-attempt snapshot
            // whenever ANY large backward clock jump has happened during the current attempt's
            // lifetime -- even one causally unrelated to that particular stale snapshot (e.g. a
            // cached LEVEL_CONNECTED from 2s before the attempt started trivially satisfies a
            // 2000ms gap <= a 30000ms unrelated jump). Discriminator: a genuine post-jump
            // current-attempt snapshot's own ts is captured AFTER the jump, on the same corrected
            // (lower) wall-clock scale as `now`, so ts can never be materially greater than now. A
            // genuinely-stale snapshot captured BEFORE the jump uses the old/higher clock scale
            // (same as currentAttemptStartMs), so once `now` is read post-jump, that stale ts ends
            // up materially AHEAD of now. Requiring ts <= now + clockJumpSlackMs (slack covers read
            // skew between the separate clock calls) rejects the stale case even though its raw
            // predates-gap alone looked "explainable" by the jump size.
            val predatesExplainedByBackwardClockJump = knownToPredateCurrentAttempt &&
                currentAttemptStartElapsedRealtimeMs > 0L &&
                ts <= now + clockJumpSlackMs &&
                run {
                    val wallClockDeltaSinceStartMs = now - currentAttemptStartMs
                    val realElapsedSinceStartMs =
                        nowElapsedRealtimeMs - currentAttemptStartElapsedRealtimeMs
                    val estimatedJumpMs = realElapsedSinceStartMs - wallClockDeltaSinceStartMs
                    val predatesGapMs = currentAttemptStartMs - ts
                    estimatedJumpMs > clockJumpMinDetectableMs &&
                        predatesGapMs <= estimatedJumpMs + clockJumpSlackMs
                }
            val shouldRejectAsStale = if (currentAttemptStartKnown) {
                knownToPredateCurrentAttempt && !predatesExplainedByBackwardClockJump
            } else {
                ageMs > staleSnapshotMaxAgeMs
            }
            if (shouldRejectAsStale) {
                if (now - lastLiveStatusMs <= liveStatusGraceMs) {
                    AppLog.w(TAG, "Skipping stale snapshot (live updates present) level=$level age=${ageMs}ms")
                    return
                }
                AppLog.w(TAG, "Skipping stale snapshot level=$level age=${ageMs}ms count=${staleSnapshotCount + 1}")
                staleSnapshotCount += 1
                if (staleSnapshotCount >= 3 && now - lastLiveStatusMs > staleSnapshotMaxAgeMs) {
                    forceRebindStatusService("stale snapshots age=${ageMs}ms")
                }
                return
            }
        }
        staleSnapshotCount = 0
        lastStatusSnapshotMs = if (ts > 0L) ts else now
        logEngineStateChange("AIDL", level, snapshot.state)
        // isAidlFresh() checks three things: boundToStatus is true, lastLiveStatusMs > 0 (a live
        // push has actually arrived at least once), and that push happened within
        // aidlFreshWindowMs. This is NOT strictly equivalent to `now - lastLiveStatusMs >
        // aidlFreshWindowMs` alone: boundToStatus can be false here (e.g. the status binder just
        // died on another thread, racing with this snapshot read) and lastLiveStatusMs can still
        // be 0 if no live push has ever landed, both of which make isAidlFresh() false, i.e.
        // livePushStale true, for reasons other than staleness of an existing timestamp.
        val livePushStale = !isAidlFresh()
        syncEngineState(level, snapshot.state, allowAutoSwitch = livePushStale)
        onOneShotInitialStateSynced("AIDL snapshot")
        if (level == ConnectionStatus.LEVEL_CONNECTED) {
            if (snapshot.connectedSinceMs > 0L) {
                ConnectionStateManager.syncConnectionStartTime(snapshot.connectedSinceMs)
            }
            persistLastSuccessfulConfig()
            tryRestoreTrafficSnapshot()
        }
    }

    private fun forceRebindStatusService(reason: String) {
        AppLog.w(TAG, "Forcing status rebind: $reason")
        statusHandler.removeCallbacks(statusRebindRunnable)
        if (boundToStatus) {
            try { statusBinder?.unregisterStatusCallback(statusCallbacks) } catch (_: Exception) {}
            try { unbindService(statusConnection) } catch (_: Exception) {}
        }
        boundToStatus = false
        statusBinder = null
        updateStatusSource(StatusSource.VPN_STATUS, "force rebind ($reason)")
        scheduleStatusRebind()
    }

    private fun logEngineLevel(level: ConnectionStatus, detail: String?) {
        val now = System.currentTimeMillis()
        val detailChanged = detail != lastEngineDetail
        val levelChanged = level != lastEngineLevel
        if (levelChanged || detailChanged || now - lastEngineLevelLogMs > 5_000L) {
            AppLog.i(TAG, "Engine level=${level} detail=${detail ?: "<none>"} source=${statusSource ?: StatusSource.VPN_STATUS}")
            lastEngineLevel = level
            lastEngineDetail = detail
            lastEngineLevelLogMs = now
        }
    }

    private fun syncEngineState(level: ConnectionStatus, detail: String?, allowAutoSwitch: Boolean) {
        logEngineLevel(level, detail)
        // Normalize ONCE, up front, and forward the SAME result to every consumer that derives
        // an "effective" engine level from (level, detail). ConnectionStateManager.updateFromEngine
        // normalizes internally (state=="CONNECTED" wins over a still-transitional raw level), but
        // until round 14 that normalization only happened AFTER dispatchAutoSwitcherOnEngineLevel()
        // below had already been called with the raw, un-normalized level -- so ServerAutoSwitcher
        // and ConnectionStateManager could observe two DIFFERENT effective levels for the exact
        // same snapshot. A raw connecting-family level could start a needless switch timer on an
        // already-healthy connection, and a raw LEVEL_NONETWORK could trigger an immediate switch
        // away from a connection the app itself is about to (or already does) recognize as
        // connected. See PR #126 review thread (round 14, Codex P1, comment 3735319517).
        val normalizedLevel = ConnectionStateManager.normalizeEngineLevel(level, detail)
        // LEVEL_NOTCONNECTED / LEVEL_NONETWORK: the engine is idle.
        // We must NOT exit the FGS notification in two situations:
        // 1. Chained auto-switch (reconnectingHint=true): the engine is intentionally stopped
        //    before the next server start — dropping the notification here reopens the AMS
        //    FGS-obligation race (RemoteServiceException crash, 2026-06-25). R8-4/QG4-5: this is
        //    not a 5-second timer expiring -- bringDownServiceLocked() fires immediately (3-11ms
        //    observed on-device) once the obligation goes unsatisfied.
        // 2. User-initiated rapid reconnect (userInitiatedStart=true): the user tapped Connect
        //    while a stale LEVEL_NOTCONNECTED from the previous session may still be in-flight
        //    on the binder thread; dropping the FGS notification here removes the safety net
        //    started by ACTION_START and reopens the same immediate-obligation race window.
        // ACTION_STOP and the ACTION_SYNC_STATUS handler both call exitControllerForeground()
        // explicitly, so those paths are unaffected by this guard.
        val idleLevel = level == ConnectionStatus.LEVEL_NOTCONNECTED || level == ConnectionStatus.LEVEL_NONETWORK
        val reconnectPending = idleLevel && (ConnectionStateManager.reconnectingHint.value || userInitiatedStart)
        if (controllerForegroundActive
            && level != ConnectionStatus.LEVEL_START
            && level != ConnectionStatus.UNKNOWN_LEVEL
            && !reconnectPending) {
            exitControllerForeground()
        }
        // Clear userInitiatedStart when the engine reports a successful connection, or a
        // terminal failure, via the AIDL path. updateState() (the VPN_STATUS path) clears it in
        // the equivalent cases, but when the status service is fresh (isAidlFresh()=true),
        // updateState() returns early and never reaches that code — syncEngineState() (called
        // from the AIDL callback path, updateStateString) is then the only place that can clear
        // it. Without this clear, userInitiatedStart stays true after a failed user-initiated
        // connect (e.g. auto-switch disabled, no network), leaving the FGS guard's
        // reconnectPending stuck and the "VPN connecting" notification undismissable.
        //
        // NOTE: intentionally NOT followed by an immediate exitControllerForeground() for this
        // callback (tried in rounds 7-8, reverted in round 10): a stale LEVEL_NOTCONNECTED from
        // a PREVIOUS session can legitimately arrive here while a NEW user-initiated start is
        // still in flight (userInitiatedStart=true, reconnectingHint=false) — indistinguishable
        // from a genuine terminal failure of the current attempt without a start-generation
        // token. Exiting foreground in that case reopens the exact FGS crash window the
        // reconnectPending guard exists to prevent. Accepting the narrower, lower-severity
        // gap instead: a single terminal-failure callback with no follow-up idle callback may
        // leave the "VPN connecting" notification stuck until the next engine callback.
        if (level == ConnectionStatus.LEVEL_CONNECTED || level in AUTO_SWITCH_LEVELS) {
            userInitiatedStart = false
        }
        if (maybeStartStaleStopReconciliation(level, "AIDL")) return
        maybeClearStaleStopIntentOnIdleLevel(level, "AIDL")
        if (shouldIgnoreLevelAfterUserStop(level)) return
        if (allowAutoSwitch) {
            dispatchAutoSwitcherOnEngineLevel(normalizedLevel)
        }
        ConnectionStateManager.updateFromEngine(normalizedLevel, detail)
        handleEngineLevelForStop(level, "AIDL")
    }

    // Shared Handler token tagging every deferred dispatch posted by
    // dispatchAutoSwitcherOnEngineLevel() below, so teardown paths (startUserStopTeardown(),
    // onDestroy()) can cancel ALL of them in one statusHandler.removeCallbacksAndMessages(token)
    // call before they run. A single `Runnable?` field (round 5's first attempt) only remembers
    // the MOST RECENTLY posted runnable: if the AIDL binder thread posts more than one deferred
    // dispatch before the main looper drains its queue (e.g. rapid engine-level changes), each
    // new post overwrites the field and orphans the previous runnable -- teardown could then
    // cancel only the last one, leaving earlier ones queued with no reference left to cancel
    // them. A shared token avoids that: every posted Runnable is tagged with the same token
    // object, and removeCallbacksAndMessages(token) removes the whole family regardless of how
    // many are queued, with no mutable reference to read cross-thread (and therefore no
    // @Volatile question either). See PR #126 review thread (round 6, Codex P2 + Copilot,
    // follow-up to the CONNECTING-preservation fix below).
    private val autoSwitchDispatchToken = Any()

    // R14-2 (fix-cycle 14, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-14.md):
    // dedicated token for the ENGINE_RECONNECT_DISPATCH_BUFFER_MS deferred dispatch (the
    // ACTION_START isReconnect==true branch below). Every other deferred action in this class is
    // cancellable -- 9 named runnable fields swept in onDestroy()/the stop sites, plus
    // autoSwitchDispatchToken above -- but the fix-cycle-13 dispatch was originally posted as a
    // bare, untagged Runnable with no cancellation site anywhere, which is why its own
    // userInitiatedStop/serviceDestroyed/connectionAttemptGeneration guard had no defence in depth
    // (R14-1: the preserveReconnect ACTION_STOP branch defeated all three checks). Tagging with
    // this token and sweeping it at the same three sites as autoSwitchDispatchToken (onDestroy(),
    // the preserveReconnect stop branch, startUserStopTeardown()) restores that second line of
    // defence, matching this class's established convention.
    private val reconnectEngineDispatchToken = Any()

    // syncEngineState() is reachable both from the AIDL binder-thread callback
    // (updateStateString) and from the main thread (applyStatusSnapshot, via
    // trySyncStatusSnapshot's onServiceConnected/trafficPollRunnable poll path). Before the
    // stale-push auto-switch fix, applyStatusSnapshot() always passed allowAutoSwitch=false, so
    // this call site was reachable from the binder thread only. Now both paths can reach it, and
    // ServerAutoSwitcher's internal timer state (runnable/timerActive/seconds/timerLevel) is
    // guarded only by non-atomic check-then-act sequences that assume a single (main-looper)
    // caller. Route every invocation through the existing main-looper statusHandler when not
    // already on the main thread, so binder-thread and main-thread callers are serialized onto
    // the same queue -- exactly what ServerAutoSwitcher's own internal timer Runnable already
    // relies on. The fast path preserves the previously synchronous behavior for the
    // applyStatusSnapshot main-thread caller.
    private fun dispatchAutoSwitcherOnEngineLevel(level: ConnectionStatus) {
        // Monotonic destroyed gate, checked at the enqueue point rather than swept after the
        // fact: round 6's removeCallbacksAndMessages(autoSwitchDispatchToken) sweep in onDestroy()
        // only clears dispatches queued BEFORE the sweep runs. An in-flight binder callback that
        // had already started executing updateStateString()/syncEngineState() before teardown
        // began, but had not yet reached this function, could still call postAtTime() AFTER the
        // sweep -- enqueuing a dispatch the sweep never saw and has no way to catch. Checking
        // serviceDestroyed here, at the moment this specific call actually tries to enqueue,
        // closes that gap: it does not matter whether the call started before or after teardown
        // began, only whether the service is destroyed right now. userInitiatedStop is NOT a
        // substitute for this check during a system-driven onDestroy() (e.g. task removal), where
        // userInitiatedStop stays false because the user never asked to disconnect. See PR #126
        // review thread (round 7, Codex P2).
        if (serviceDestroyed) return
        // PR #127 review round 3 (Codex P1, thread 3792922991): while a reconnect engine-dispatch
        // buffer is pending for the CURRENT generation, no new engine process has been asked to
        // start yet (that is exactly what the buffer defers), so any level received right now is
        // necessarily a late/stray delivery from the just-stopped previous engine. Forwarding it to
        // ServerAutoSwitcher would let it re-trigger requestSwitchNow(), whose preserveReconnect
        // stop then cancels the still-pending deferred dispatch -- skipping the selected server
        // without ever trying it. See reconnectDispatchPendingGeneration's declaration comment.
        if (reconnectDispatchPendingGeneration == connectionAttemptGeneration.get()) {
            AppLog.i(TAG, "Ignoring AIDL level=$level while reconnect engine-dispatch buffer is pending (stale from just-stopped engine)")
            return
        }
        // Capture whether ConnectionStateManager.state was CONNECTING synchronously, right now
        // -- before returning to syncEngineState(), which calls ConnectionStateManager
        // .updateFromEngine(level, detail) immediately afterward on the CALLING thread (the AIDL
        // binder thread when allowAutoSwitch=true). When this dispatch has to be deferred to the
        // main looper below (non-main caller), updateFromEngine() runs synchronously first and
        // can already flip CONNECTING -> DISCONNECTED for terminal levels (LEVEL_AUTH_FAILED /
        // LEVEL_NONETWORK) before the deferred onEngineLevel() call actually executes. If
        // onEngineLevel() re-read ConnectionStateManager.state at that later point, it would see
        // DISCONNECTED and (with no auto-switch timer running yet on a fresh connection attempt)
        // silently skip the immediate switch it must perform. Passing the pre-mutation snapshot
        // through preserves the original ordering guarantee regardless of when the deferred
        // block actually runs. See PR #126 review thread (P1 regression from the round-2 fix).
        val wasConnectingAtDispatch = try {
            ConnectionStateManager.state.value == ConnectionState.CONNECTING
        } catch (_: Exception) {
            false
        }
        // Snapshot the attempt generation valid RIGHT NOW, at the moment this level was
        // received and this dispatch is being prepared -- see connectionAttemptGeneration's
        // declaration comment for the stop-then-restart race this closes (round 12, Codex P2,
        // comment 3734663965). If a fresh ACTION_START bumps the live counter before the
        // runnable below actually executes, the mismatch proves a newer attempt has begun since
        // this dispatch was queued.
        val dispatchedForGeneration = connectionAttemptGeneration.get()
        val invoke = Runnable {
            // Defensive re-check: even if teardown's removeCallbacksAndMessages() raced with this
            // runnable already being pulled off the main-looper queue, don't act on it once the
            // user has stopped the VPN in the meantime, or once the service itself has been
            // destroyed (system-driven onDestroy(), where userInitiatedStop stays false).
            if (userInitiatedStop || serviceDestroyed) return@Runnable
            // Re-validated at the last possible moment, right before touching ServerAutoSwitcher:
            // userInitiatedStop is NOT a reliable signal for the stop-then-restart race a fresh
            // ACTION_START clears it back to false as part of starting the new attempt, even
            // though this dispatch was queued for a now-superseded attempt. A generation mismatch
            // means exactly that happened, so skip unconditionally regardless of the flag above.
            if (connectionAttemptGeneration.get() != dispatchedForGeneration) return@Runnable
            // R19-1 (fix-cycle 20, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-
            // gate-10.md): re-evaluate the SAME suppression predicate already checked at :2777,
            // but here, at execution time, instead of trusting the capture-time read. The
            // capture-time check reads (reconnectDispatchPendingGeneration, connectionAttemptGeneration)
            // as a pair, and no single-field fix closes that: even after R20-1 made
            // connectionAttemptGeneration an AtomicInteger (fix-cycle 21, docs/qa-evidence/86cb35fbt-
            // vpn-foreground-service-crash-review-20.md), a level captured strictly between
            // onStartCommand()'s `connectionAttemptGeneration.incrementAndGet()` bump and its
            // `reconnectDispatchPendingGeneration = connectionAttemptGeneration.get()` arm still
            // observes a torn (marker=stale, generation=G) snapshot and evades suppression -- the
            // two fields are updated by two separate statements, so no amount of per-field
            // atomicity makes reading them together atomic. Cycles 16-19 each relocated WHERE the
            // marker is armed or cleared; this re-check instead moves WHEN the pair is read.
            // CORRECTED (R20-2, fix-cycle 21): the previous version of this comment claimed this
            // Runnable "executes on the SAME thread that owns every writer of both fields,
            // including finishStopFlowConfirmed()" -- that is false. finishStopFlowConfirmed() is
            // reached from the AIDL binder thread (updateStateString -> syncEngineState ->
            // handleEngineLevelForStop), not main, and is one of connectionAttemptGeneration's
            // three writers. What IS actually true, and is what makes this re-check coherent:
            // (a) reconnectDispatchPendingGeneration's only writers -- ACTION_START and
            // clearMarkerIfOwn() -- are both main-thread-only (see its declaration comment), and
            // this Runnable always executes on statusHandler's main looper, so reading the marker
            // here needs no extra synchronization beyond its existing @Volatile visibility; and
            // (b) connectionAttemptGeneration.get() always returns the true, fully-visible live
            // value regardless of which thread last incremented it, because AtomicInteger makes
            // every increment atomic -- unlike the old plain `+= 1` Int, no writer (including the
            // binder-thread one) can lose an update or leave a stale value visible here. Together,
            // by the time this line runs, if onStartCommand() has since armed the marker to match
            // the live generation, this catches it even though :2777 could not.
            if (reconnectDispatchPendingGeneration == connectionAttemptGeneration.get()) {
                AppLog.i(TAG, "Ignoring AIDL level=$level at dispatch time; reconnect engine-dispatch buffer armed for the current generation after capture (stray from just-stopped engine, R19-1)")
                return@Runnable
            }
            try {
                ServerAutoSwitcher.onEngineLevel(applicationContext, level, "AIDL", wasConnectingAtDispatch)
            } catch (e: Exception) {
                AppLog.w(TAG, "Failed to notify auto-switcher from AIDL", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invoke.run()
        } else {
            // Tag with the shared token (instead of a plain post()) so teardown can cancel this
            // dispatch -- and any other deferred dispatch still queued alongside it -- in one
            // removeCallbacksAndMessages(autoSwitchDispatchToken) call.
            statusHandler.postAtTime(invoke, autoSwitchDispatchToken, SystemClock.uptimeMillis())
        }
    }

    private fun shouldIgnoreLevelAfterUserStop(level: ConnectionStatus): Boolean {
        if (!ignoreConnectedUntilNotConnected) return false
        return when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                AppLog.d(TAG, "Ignoring stale LEVEL_CONNECTED after user stop")
                true
            }
            ConnectionStatus.LEVEL_VPNPAUSED -> {
                ignoreConnectedUntilNotConnected = false
                AppLog.d(TAG, "Cleared stale CONNECTED guard on level=$level and ignored stale paused callback")
                true
            }
            ConnectionStatus.LEVEL_NOTCONNECTED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_AUTH_FAILED,
            ConnectionStatus.UNKNOWN_LEVEL -> {
                ignoreConnectedUntilNotConnected = false
                AppLog.d(TAG, "Cleared stale CONNECTED guard on level=$level")
                false
            }
            else -> false
        }
    }

    private fun persistLastSuccessfulConfig() {
        try {
            val last = SelectedCountryStore.getLastStartedConfig(applicationContext)
            val cfg = last?.config
            val country = last?.country
            val ip = last?.ip
            if (!cfg.isNullOrBlank()) {
                SelectedCountryStore.saveLastSuccessfulConfig(
                    ctx = applicationContext,
                    country = country,
                    config = cfg,
                    ip = ip,
                    alignIndex = false
                )
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to save last successful config from status", e)
        }
    }
}
