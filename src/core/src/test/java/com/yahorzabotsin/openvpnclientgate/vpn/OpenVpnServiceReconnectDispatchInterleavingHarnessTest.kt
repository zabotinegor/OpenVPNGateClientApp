package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import android.os.Looper
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IStatusCallbacks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import timber.log.Timber
import java.time.Duration

/**
 * Interleaving-driven harness for the reconnect-dispatch suppression protocol. Every other test in
 * this package pins ONE guard at ONE site, synthesizing the race either by reflection or by
 * re-entering at a single hooked log line. None of them drives more than one genuine interleaving
 * shape per test, and none asserts the ONE composite invariant that actually matters across the
 * whole suppression protocol: **no engine start for a superseded attempt, and no auto-switch
 * reaction while a dispatch is pending for the current generation.**
 *
 * This harness drives the REAL production entry points --
 * [OpenVpnService.onStartCommand] (fresh `ACTION_START`, reconnect `ACTION_START`, and the
 * `preserveReconnect ACTION_STOP` branch), the private `finishStopFlowConfirmed()`, and
 * `dispatchAutoSwitcherOnEngineLevel()` (reached via the real AIDL `statusCallbacks` stub) --
 * against a small MATRIX of interleavings rather than one hand-picked race, and checks the
 * composite invariant identically after every one of them.
 *
 * ## Technique: real re-entrancy, not simulated concurrency
 *
 * Robolectric's main looper is single-threaded, so genuine interleaving is produced the same way
 * [OpenVpnServiceReconnectEngineDispatchTest]'s `strayLevelArrivingAtEngineDispatch_...` and
 * `reconnectRetryRacingBinderThreadStopConfirmation_...` tests already do: a [Timber.Tree] is
 * planted that intercepts a specific, real, mid-flow production log line and, the FIRST time it
 * fires, re-enters the service with a second real entry point call -- either synchronously
 * (reentrant on the same thread, for actions that are themselves main-thread-only in production:
 * a second `onStartCommand()`) or from a genuine background `java.lang.Thread` (for
 * `finishStopFlowConfirmed()`, which is reachable in production from the AIDL binder thread).
 * Every injected action is a real call to a real production method; nothing is faked except WHEN
 * it happens relative to the baseline flow.
 *
 * ## The interleaving matrix
 *
 * Two interleave points (both real, unmodified production log lines already present before this
 * story):
 * - **AT_ARM** -- the "Session attempt" log, which fires after [ReconnectDispatchGuard.beginNewAttempt]
 *   and [ReconnectDispatchGuard.armPending] have both run for the baseline reconnect attempt, but
 *   BEFORE its deferred engine-dispatch Runnable has been scheduled.
 * - **AT_DISPATCH** -- the "Requested engine start" log, which fires inside `startIcsOpenVpn()`
 *   while the baseline attempt's deferred Runnable is executing, AFTER its execution-time
 *   re-check has passed but BEFORE `clearMarkerIfOwn()` runs -- the marker is still armed for the
 *   live generation at this exact point.
 *
 * Four injected actions, each a real production entry point:
 * - **CONFIRM_STOP** -- a genuine background thread invokes the private `finishStopFlowConfirmed()`
 *   directly (mirroring `reconnectRetryRacingBinderThreadStopConfirmation_...`'s exact technique).
 * - **STRAY_AIDL_LEVEL** -- a real AIDL terminal level via the real `statusCallbacks` stub,
 *   reaching `dispatchAutoSwitcherOnEngineLevel()`.
 * - **SUPERSEDING_START** -- a second, reentrant `onStartCommand()` call with a fresh reconnect
 *   `ACTION_START`.
 * - **PRESERVE_RECONNECT_STOP** -- a reentrant `onStartCommand()` call with `ACTION_STOP` +
 *   `preserveReconnect=true` -- the exact stop `ServerAutoSwitcher`'s own retry machinery issues.
 *
 * Not every point x action pair is meaningful: at AT_DISPATCH, the hook fires AS PART OF the
 * "Requested engine start" log statement itself, so an engine start has, by construction, ALREADY
 * happened by the time any action is injected there -- "no engine start" is vacuously false for
 * that combination and would not be testing anything. AT_DISPATCH is therefore paired only with
 * STRAY_AIDL_LEVEL, which tests the OTHER half of the composite invariant (no auto-switch reaction
 * while the marker is still armed) instead. AT_ARM is paired with all four actions. This gives 5
 * scenarios total -- small enough to enumerate EXHAUSTIVELY rather than sample randomly: the
 * reachable state space here (2 meaningful interleave points x up to 4 actions each) is small by
 * construction, since every point and action is anchored to a real, named, already-audited
 * production site rather than an arbitrary instruction offset.
 *
 * ## Counting dispatches, and why not from ShadowLog
 *
 * This harness is the only test in the package that asserts an EXACT engine-dispatch count rather
 * than a boolean "did it start"; that makes it the only one sensitive to how many times a single
 * production log call lands in the log buffer.
 *
 * `AppLog` routes through Timber whenever any tree is planted, and Timber delivers each call to
 * EVERY planted tree. Trees that forward to `android.util.Log` (`AppDebugTree` does, and
 * `CoreApp.initLogging()` plants one) therefore each add their own [ShadowLog] entry for the SAME
 * production call. Planting is JVM-global and outlives the class that did it, so the number of
 * ShadowLog entries per production log call is not something this test can control: it depends on
 * which other classes happened to share the test JVM. Counting `"Requested engine start"` out of
 * ShadowLog consequently reported TWO dispatches for one -- intermittently, only in runs where such
 * a class ran first, which is why the full suite could fail while this class in isolation passed.
 *
 * Observations are therefore taken inside the scenario's own planted tree ([ScenarioLog]), which
 * Timber invokes exactly once per production log call no matter what else is planted. Two further
 * measures keep each scenario's observations its own, since all five share one JVM, one main Looper
 * and one log buffer, and CONFIRM_STOP deliberately spawns a real background thread that logs:
 *
 * - **Attribution, not merely windowing.** Each scenario connects with a scenario-unique display
 *   title, which production threads through to `profile.mName` and therefore into the
 *   `Requested engine start (profile=...)` line itself, so a start caused by a different scenario
 *   is not counted here even if it lands inside this scenario's window.
 * - **A quiesced boundary.** The shared main Looper is drained at the START of each scenario,
 *   before its window opens, so residual pending work from a prior scenario executes while that
 *   prior scenario still owns the window.
 *
 * Production itself schedules nothing on the engine-start path from any injected action:
 * `startIcsOpenVpn()` has exactly two call sites, both inside `onStartCommand()`'s `ACTION_START`
 * branch, so a confirmed stop or a stray AIDL level can only ever SUPPRESS a dispatch, never cause
 * a later one. That is what rules out a genuine production double-dispatch as the explanation for
 * the observed count of 2.
 *
 * ## Falsifiability
 *
 * The mutation run that reintroduces a historical defect variant and confirms this harness's
 * composite invariant assertion actually fails is recorded with this change's implementation
 * evidence; this file alone does not re-run that mutation (mutating shipped code from within a
 * permanent test file would leave the mutation applied on every subsequent run).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [27])
class OpenVpnServiceReconnectDispatchInterleavingHarnessTest {

    private val appContext = RuntimeEnvironment.getApplication()
    private val logTag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ":" + "OpenVpnService"
    private val engineStartLog = "Requested engine start"
    private val sessionAttemptLog = "Session attempt"
    private val suppressionLogFragment = "reconnect engine-dispatch buffer"

    /**
     * One scenario's record of the production log lines it caused.
     *
     * Observations are taken from the scenario's own planted [Timber.Tree], NOT from [ShadowLog]:
     * Timber delivers each production log call to each planted tree exactly once, whereas the
     * number of ShadowLog entries a single call produces depends on how many OTHER trees happen to
     * be planted and forwarding to `android.util.Log` -- which a test cannot control, because
     * planting is JVM-global and other classes (and `CoreApp.initLogging()` itself) plant trees
     * that forward. See this class's KDoc, "Scenario isolation".
     *
     * Recorded from both the main thread and CONFIRM_STOP's real background thread, hence
     * synchronized.
     */
    private inner class ScenarioLog {
        private val messages = java.util.Collections.synchronizedList(mutableListOf<String>())

        fun record(message: String) {
            messages.add(message)
        }

        /**
         * Engine starts attributable to ONE scenario: production stamps the connection's display
         * title into the engine-start line as the profile name, so scoping the count to this
         * scenario's own title also rules out a start caused by any other scenario.
         */
        fun engineStartCount(scenarioTitle: String): Int = synchronized(messages) {
            messages.count { it.contains(engineStartLog) && it.contains("profile=$scenarioTitle") }
        }

        fun hasSuppressionLog(): Boolean = synchronized(messages) {
            messages.any { it.contains(suppressionLogFragment) }
        }
    }

    private fun installServers() {
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf2")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
    }

    @Before
    fun setUp() {
        ShadowLog.clear()
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        installServers()
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
        ServerAutoSwitcher.resetForTest()
    }

    /**
     * [title] is the connection's display title. Production passes it straight through to the
     * started profile's name and therefore into the engine-start log line, so giving each scenario
     * its own title is what makes [engineStartCount] scenario-attributable. It is a display string
     * only -- nothing in the server-index or suppression logic reads it.
     */
    private fun reconnectStartIntent(title: String): Intent {
        return Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraAutoSwitchKey(appContext), true)
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), title)
        }
    }

    private fun preserveReconnectStopIntent() = Intent(appContext, OpenVpnService::class.java).apply {
        putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
        putExtra(VpnManager.extraPreserveReconnectKey(appContext), true)
    }

    private enum class InterleavePoint {
        AT_ARM,
        AT_DISPATCH
    }

    private enum class InjectedAction {
        CONFIRM_STOP,
        STRAY_AIDL_LEVEL,
        SUPERSEDING_START,
        PRESERVE_RECONNECT_STOP
    }

    /**
     * The exhaustive matrix, minus the vacuous AT_DISPATCH x {CONFIRM_STOP, SUPERSEDING_START,
     * PRESERVE_RECONNECT_STOP} combinations explained in this class's KDoc.
     */
    @Test
    fun interleavingMatrix_realEntryPoints_compositeInvariantHoldsForEveryScenario() {
        val scenarios = listOf(
            InterleavePoint.AT_ARM to InjectedAction.CONFIRM_STOP,
            InterleavePoint.AT_ARM to InjectedAction.STRAY_AIDL_LEVEL,
            InterleavePoint.AT_ARM to InjectedAction.SUPERSEDING_START,
            InterleavePoint.AT_ARM to InjectedAction.PRESERVE_RECONNECT_STOP,
            InterleavePoint.AT_DISPATCH to InjectedAction.STRAY_AIDL_LEVEL
        )

        scenarios.forEach { (point, action) -> runScenario(point, action) }
    }

    /**
     * Regression test for the harness's own dispatch counting, not for the guard.
     *
     * Reproduces the exact condition that made the matrix above fail intermittently in CI while
     * passing when this class ran alone: another class in the same JVM leaves a Timber tree planted
     * that forwards to `android.util.Log` (`CoreApp.initLogging()` plants an `AppDebugTree`, which
     * does exactly that, and never uproots it). Every production log call then produces TWO
     * ShadowLog entries, so a ShadowLog-based count reported two engine dispatches for the one this
     * scenario actually issues.
     *
     * Planting such a tree here must not change the outcome, because the count is now taken inside
     * this scenario's own tree, which Timber invokes once per production log call regardless of how
     * many other trees are planted. Against a ShadowLog-based counter this test fails with the same
     * "must still reach the engine exactly once expected:<1> but was:<2>" that CI reported.
     */
    @Test
    fun compositeInvariantHolds_evenWithAForeignLogForwardingTimberTreePlanted() {
        val foreignForwardingTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                android.util.Log.println(priority, tag ?: "", message)
            }
        }
        Timber.plant(foreignForwardingTree)
        try {
            runScenario(InterleavePoint.AT_ARM, InjectedAction.STRAY_AIDL_LEVEL)
        } finally {
            Timber.uproot(foreignForwardingTree)
        }
    }

    private fun runScenario(point: InterleavePoint, action: InjectedAction) {
        val label = "[$point x $action]"
        val scenarioTitle = "harness-$point-$action"
        // Quiesce the SHARED main Looper BEFORE this scenario's log window opens, so any work a
        // previous scenario left queued runs and logs while that scenario still owns the window.
        // The previous scenario's own cleanup cannot guarantee this by itself: destroying its
        // service instance sweeps only the Handler tokens that instance knows about. Generous
        // relative to the 500ms engine-dispatch buffer, which bounds everything on this path.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_000))
        ShadowLog.clear()
        ServerAutoSwitcher.resetForTest()
        installServers()

        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        val finishStopFlowConfirmedMethod = OpenVpnService::class.java.getDeclaredMethod(
            "finishStopFlowConfirmed", ConnectionStatus::class.java, String::class.java
        ).apply { isAccessible = true }

        val hookFragment = when (point) {
            InterleavePoint.AT_ARM -> sessionAttemptLog
            InterleavePoint.AT_DISPATCH -> engineStartLog
        }

        val scenarioLog = ScenarioLog()
        var injected = false
        val probeTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                // Record here rather than forwarding to android.util.Log and counting ShadowLog
                // afterwards: Timber calls this method exactly once per production log call, which
                // is the property the assertions below need. See [ScenarioLog].
                if (tag == logTag) scenarioLog.record(message)
                // Both production log lines used as interleave points carry the connection's
                // display title, so requiring THIS scenario's title as well pins the hook to this
                // scenario's own flow -- a same-shaped line emitted by a straggling thread from an
                // earlier scenario cannot fire the injection at the wrong moment.
                if (injected || !message.contains(hookFragment) || !message.contains(scenarioTitle)) return
                injected = true
                when (action) {
                    InjectedAction.CONFIRM_STOP -> {
                        // Models a genuine AIDL binder-thread stop confirmation landing mid-flow.
                        // finishStopFlowConfirmed()'s own `if (!userInitiatedStop) return` guard
                        // must be satisfied first, exactly as it would be by a real prior stop
                        // dispatch -- see reconnectRetryRacingBinderThreadStopConfirmation_... in
                        // OpenVpnServiceReconnectEngineDispatchTest.kt for the identical technique.
                        ReflectionHelpers.setField(service, "userInitiatedStop", true)
                        val thread = Thread {
                            finishStopFlowConfirmedMethod.invoke(
                                service, ConnectionStatus.LEVEL_NONETWORK, "harness-$point-$action"
                            )
                        }
                        thread.isDaemon = true
                        thread.start()
                        // Join with a generous bound, but never let this scenario move on while the
                        // thread is still alive: under full-suite Gradle load (GC pauses, JIT
                        // warmup, contended CPU), a bare 5s join without a hard wait-out was
                        // observed to let this real background thread straggle into the NEXT
                        // scenario's ShadowLog.clear() window and log its own "Requested engine
                        // start" there, corrupting that scenario's engine-start count. Interrupting
                        // does not help either -- the invoked method doesn't poll for interruption.
                        // Block until it genuinely finishes (bounded at 30s total) rather than risk
                        // cross-scenario contamination; fail loudly if it somehow never does.
                        var waitedMs = 5_000L
                        thread.join(waitedMs)
                        while (thread.isAlive && waitedMs < 30_000L) {
                            thread.join(1_000)
                            waitedMs += 1_000
                        }
                        assertFalse(
                            "$label CONFIRM_STOP's background thread must finish before the " +
                                "scenario proceeds -- a still-alive thread would leak a stray " +
                                "engine-start log into a later scenario",
                            thread.isAlive
                        )
                    }
                    InjectedAction.STRAY_AIDL_LEVEL -> {
                        callbacks.updateStateString("AUTH_FAILED", null, 0, ConnectionStatus.LEVEL_AUTH_FAILED, null)
                    }
                    InjectedAction.SUPERSEDING_START -> {
                        // Shares this scenario's title prefix: both starts belong to THIS scenario,
                        // so both must be visible to engineStartCount(scenarioTitle).
                        service.onStartCommand(reconnectStartIntent("$scenarioTitle-superseding"), 0, 99)
                    }
                    InjectedAction.PRESERVE_RECONNECT_STOP -> {
                        service.onStartCommand(preserveReconnectStopIntent(), 0, 50)
                    }
                }
            }
        }
        Timber.plant(probeTree)
        try {
            service.onStartCommand(reconnectStartIntent(scenarioTitle), 0, 2)

            // AT_ARM's hook ("Session attempt") fires synchronously inside onStartCommand() above;
            // AT_DISPATCH's hook ("Requested engine start") fires only once the baseline attempt's
            // deferred Runnable executes, which requires advancing the main looper's virtual clock
            // past ENGINE_RECONNECT_DISPATCH_BUFFER_MS first. Advance well past both the baseline
            // attempt's buffer AND any superseding attempt's own buffer (each 500ms) BEFORE
            // asserting the hook fired.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

            assertTrue("$label precondition: the interleave point must have fired", injected)

            when (action) {
                InjectedAction.CONFIRM_STOP -> {
                    // Composite invariant, clause 1: a confirmed stop landing mid-flow bumps the
                    // generation without arming a new marker, so the baseline attempt's deferred
                    // dispatch must recognize itself as superseded and never reach the engine.
                    assertEquals(
                        "$label a confirmed stop (finishStopFlowConfirmed(), real background " +
                            "thread) landing mid-flow must supersede the in-flight reconnect " +
                            "attempt -- no engine start may occur for the superseded attempt",
                        0,
                        scenarioLog.engineStartCount(scenarioTitle)
                    )
                }
                InjectedAction.PRESERVE_RECONNECT_STOP -> {
                    // Same clause-1 shape as CONFIRM_STOP, but via the OTHER real bump site that
                    // does not arm a new marker: the preserveReconnect ACTION_STOP branch.
                    assertEquals(
                        "$label a preserveReconnect ACTION_STOP (the exact stop " +
                            "ServerAutoSwitcher's own retry machinery issues) landing mid-flow " +
                            "must supersede the in-flight reconnect attempt -- no engine start " +
                            "may occur for the superseded attempt",
                        0,
                        scenarioLog.engineStartCount(scenarioTitle)
                    )
                }
                InjectedAction.SUPERSEDING_START -> {
                    // Clause 1, the other direction: a NEWER attempt must win. Exactly one engine
                    // start may occur (the newer attempt's own) -- if the stale dispatch were not
                    // recognized as superseded, two engine starts would fire (one stale, one
                    // fresh), which is precisely the "started for a superseded attempt" defect
                    // shape this whole subsystem exists to prevent.
                    assertEquals(
                        "$label exactly one engine start may occur once a newer reconnect attempt " +
                            "supersedes an in-flight one -- the stale (superseded) dispatch must " +
                            "be skipped, never both",
                        1,
                        scenarioLog.engineStartCount(scenarioTitle)
                    )
                }
                InjectedAction.STRAY_AIDL_LEVEL -> {
                    // Composite invariant, clause 2: while a dispatch is pending for the current
                    // generation, a stray level reaching dispatchAutoSwitcherOnEngineLevel() must
                    // be suppressed (mechanism-anchored: the suppression log line must fire) and
                    // must never reach ServerAutoSwitcher (outcome-anchored: the stored server
                    // position must not move). The baseline attempt itself is NOT superseded by
                    // this action, so it must still complete normally afterward (clause 1's
                    // complementary check: exactly one engine start, for the live attempt).
                    assertTrue(
                        "$label a stray AIDL level landing while a reconnect engine-dispatch " +
                            "buffer is pending for the current generation must be suppressed " +
                            "before reaching ServerAutoSwitcher",
                        scenarioLog.hasSuppressionLog()
                    )
                    assertEquals(
                        "$label no auto-switch reaction may occur while a dispatch is pending -- " +
                            "the stored server position must not advance",
                        1 to 2,
                        SelectedCountryStore.getCurrentPosition(appContext)
                    )
                    assertEquals(
                        "$label the baseline attempt is not itself superseded by a suppressed " +
                            "stray level and must still reach the engine exactly once",
                        1,
                        scenarioLog.engineStartCount(scenarioTitle)
                    )
                }
            }
        } finally {
            Timber.uproot(probeTree)
            // All 5 scenarios run inside ONE @Test method against Robolectric's single shared main
            // Looper, so a service instance's own background timers (watchdog poll, status rebind,
            // etc.) left running past this scenario's own idleFor() window would otherwise still be
            // sitting on that shared Looper's queue and could fire during a LATER scenario's own
            // idleFor() call -- a genuine cross-scenario leak, not a defect in the guard under test.
            // Destroying the instance here (as Android would between real service lifecycles) sweeps
            // its own Handler-posted callbacks via onDestroy(), keeping every scenario isolated.
            runCatching { controller.destroy() }
        }
    }
}
