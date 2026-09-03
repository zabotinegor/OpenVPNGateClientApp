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
import java.util.concurrent.atomic.AtomicInteger

// Fix-cycle 13 (86cb35fbt, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-qa-4.md
// section 2): manual QA round 4 device-reproduced a genuine, previously-undiscovered
// RemoteServiceException$ForegroundServiceDidNotStartInTimeException in the ENGINE's OWN
// de.blinkt.openvpn.core.OpenVPNService (a different class/process/manifest from this
// controller), caused by rapid ACTION_STOP -> ACTION_START churn during an auto-switch retry
// chain racing airplane-mode-style network flapping. The fix delays only the engine-facing
// dispatch (startIcsOpenVpn(), which reaches VPNLaunchHelper.startOpenVpn() ->
// Context.startForegroundService() against the engine's own service) for auto-switch reconnect
// starts (isReconnect == true) by ENGINE_RECONNECT_DISPATCH_BUFFER_MS, giving the engine's own
// async teardown of the PREVIOUS session more real time to land before the next FGS obligation
// is armed. A fresh user-initiated Connect tap (isReconnect == false) has no preceding stop to
// race and must remain dispatched immediately, unchanged.
//
// "Requested engine start (profile=...)" is logged synchronously, once, at the tail of
// startIcsOpenVpn() -- the only production log line downstream of the delay point under test. Its
// presence/absence at specific points on the Robolectric virtual clock is what each test below
// observes.
//
// Falsifiability: 3 of the 4 original tests (reconnectStart_doesNotDispatchToEngineSynchronously,
// reconnectStart_dispatchesToEngineAfterBuffer, userDisconnectDuringBufferWindow_
// suppressesDeferredEngineDispatch) must fail if OpenVpnService.kt's ACTION_START handler is
// reverted to unconditionally call startIcsOpenVpn(config, title) synchronously (removing the
// `if (isReconnect) { ... postDelayed ... } else { startIcsOpenVpn(...) }` branch and the
// ENGINE_RECONNECT_DISPATCH_BUFFER_MS constant it uses). freshUserStart_dispatchesToEngineImmediately
// is the one exception: it covers the untouched isReconnect==false synchronous path and is
// expected to keep passing on that revert -- it exists to prove the reconnect-only buffer adds no
// latency to a fresh Connect tap, not to detect the revert itself. (R14-5: this comment previously
// overclaimed "every test in this file must fail if reverted"; corrected per fix-cycle 14.)
//
// Fix-cycle 14 (R14-1/R14-2, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-14.md):
// preserveReconnectStop_duringBufferWindow_suppressesDeferredEngineDispatch below is the
// acceptance test for R14-1 -- it must fail at commit dbc8583 (before the fix, which adds
// `connectionAttemptGeneration += 1` to the ACTION_STOP preserveReconnect branch) and pass after.
// reconnectStart_supersededByNewerReconnectStart_dispatchesOnlyOnce covers the generation-
// supersession branch (R14-5(ii)).
//
// Fix-cycle 16 (PR #127 review round 3, Codex P1, thread 3792922991, OpenVpnService.kt:1199):
// strayAidlLevelDuringBufferWindow_doesNotSkipSelectedServer below is the acceptance test for
// that finding. R14-1/R14-2 only guard the deferred dispatch's OWN Runnable against a genuine
// stop/newer-attempt; neither closes the separate gap where a late AIDL terminal level
// (LEVEL_AUTH_FAILED/LEVEL_NONETWORK) from the just-stopped engine is forwarded to
// ServerAutoSwitcher during the buffer window, re-triggering requestSwitchNow() and cancelling
// the deferred dispatch via its own preserveReconnect stop -- skipping the selected server
// without ever trying it. The fix suppresses such levels at dispatchAutoSwitcherOnEngineLevel()
// via reconnectDispatchPendingGeneration, before they ever reach ServerAutoSwitcher. This test
// must fail if that suppression check is reverted and pass with it in place.
//
// Fix-cycle 17 (R16-1, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-16.md):
// review-16 proved with a falsifying probe that reconnectDispatchPendingGeneration's clear site
// was unconditional, so a superseded (earlier) buffer's Runnable could wipe a still-pending newer
// buffer's marker out from under it, reopening the fix-cycle-16 suppression window.
// overlappingReconnectBuffers_earlierBufferResolutionDoesNotClearNewerBuffersGuard below is the
// acceptance test for that finding; it must fail if the clear site's
// `if (reconnectDispatchPendingGeneration == dispatchGeneration)` guard is reverted to an
// unconditional clear, and pass with the guard in place.
//
// Fix-cycle 18 (R5-1, PR #127 review round 5, thread 3793613337, on the deferred reconnect
// engine-dispatch Runnable's clearMarkerIfOwn()-then-startIcsOpenVpn() ordering):
// R16-1 fixed WHICH generation the clear applies to, but not WHEN it happened relative to the
// engine dispatch it guards. The deferred Runnable used to clear reconnectDispatchPendingGeneration
// as its very first statement, then only afterwards call startIcsOpenVpn() -- leaving a genuine
// cross-thread window (the marker is read directly, without a handler hop, on the AIDL binder
// thread inside dispatchAutoSwitcherOnEngineLevel()) where a late binder callback landing between
// the clear and the dispatch call would observe -1, evade the stale-level suppression guard, and
// cancel/skip the very server this Runnable was in the middle of starting.
// strayLevelArrivingAtEngineDispatch_doesNotSkipJustDispatchedServer below is the acceptance test
// for that finding: it hooks the "Requested engine start" log line -- the synchronous last
// statement inside startIcsOpenVpn() -- to re-enter with a stray AIDL terminal level at exactly
// that point, re-entering at the point in the call stack a binder callback would have observed
// (Robolectric runs this test's main looper as the calling thread, so the re-entry is
// synchronous, not a real cross-thread callback -- but it lands at the same point in the call
// stack the pre-fix gap would have exposed to one). It must fail (selected server's position
// advances to the next server) if the clear is reverted to precede startIcsOpenVpn(), and pass
// (position stays on the originally selected server) with the clear deferred to after the
// dispatch call.
//
// Fix-cycle 19 (R18-1/QG9-1/QG9-2, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-9.md):
// review-18 and gate-9 reproduced a SIBLING pre-arm window, this time on the reconnect
// ACTION_START branch's own NORMAL path rather than inside the deferred Runnable: the generation
// counter used to be bumped several statements (SharedPreferences reads/write, an AppLog.i call)
// before reconnectDispatchPendingGeneration was armed, so a stray AIDL level landing in that gap
// evaded suppression entirely. strayLevelDuringPreArmWindow_doesNotSkipSelectedServer below is
// the acceptance test: it hooks the "Session attempt" log line -- squarely inside that gap on the
// pre-fix code path -- and must fail (position advances) if the arm is moved back down past that
// log line, passing only when the arm happens immediately after the blank-config early return.
// gate-9 also proved BY MUTATION that the naive one-line remediation (arming the marker
// immediately after the generation bump, i.e. BEFORE the blank-config early return) introduces a
// new, reachable, permanently-latching defect: a blank-config reconnect bumps the generation,
// arms the marker to match it, then returns before the deferred Runnable that would ever clear
// it is posted, permanently suppressing every subsequent AIDL level.
// reconnectStartWithBlankConfig_doesNotLatchDispatchMarkerToNewGeneration below is the
// falsifying regression test for that: it must fail if the arm is moved to precede the
// blank-config early return, and pass with the arm placed after it (this fix's actual placement).
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [27])
class OpenVpnServiceReconnectEngineDispatchTest {

    private val appContext = RuntimeEnvironment.getApplication()
    private val logTag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ":" + "OpenVpnService"
    private val engineStartLog = "Requested engine start"
    // The capture-time suppression log emitted by dispatchAutoSwitcherOnEngineLevel() when
    // ReconnectDispatchGuard.isBufferPendingForCurrentGeneration() is true (see
    // OpenVpnService.kt's "Ignoring AIDL level=... while reconnect engine-dispatch buffer is
    // pending" line). Asserting this log line fired is MECHANISM-anchored -- it can only be true
    // if the guard's own suppression branch executed -- unlike hasEngineStartLog() alone, which is
    // OUTCOME-anchored and stays vacuously true even when the guard never ran, as long as nothing
    // downstream happened to cancel the deferred dispatch for an unrelated reason.
    private val reconnectDispatchSuppressionLog = "while reconnect engine-dispatch buffer is pending"

    private fun hasEngineStartLog(): Boolean =
        ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains(engineStartLog) }

    private fun hasReconnectDispatchSuppressionLog(): Boolean =
        ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains(reconnectDispatchSuppressionLog) }

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
        // Hygiene run after every test in this class. Several tests here exercise the real
        // ServerAutoSwitcher singleton's static state -- either directly, when a stray level is
        // deliberately let through to prove a guard is NOT suppressing it (e.g.
        // reconnectStartWithBlankConfig_doesNotLatchDispatchMarkerToNewGeneration), or the level
        // reaches it as an unguarded baseline the test is specifically about (fix-cycle 19,
        // gate-9: more than one test now touches these statics, unlike when this comment
        // originally named a single test). Harmless no-op for every test that never reaches it.
        ServerAutoSwitcher.resetForTest()
    }

    private fun reconnectStartIntent(startId: Int = 2) = Intent(appContext, OpenVpnService::class.java).apply {
        putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
        putExtra(VpnManager.extraAutoSwitchKey(appContext), true)
        putExtra(VpnManager.extraConfigKey(appContext), "client\n")
        putExtra(VpnManager.extraTitleKey(appContext), "RU")
    }

    private fun freshStartIntent() = Intent(appContext, OpenVpnService::class.java).apply {
        putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
        putExtra(VpnManager.extraConfigKey(appContext), "client\n")
        putExtra(VpnManager.extraTitleKey(appContext), "RU")
    }

    @Test
    fun reconnectStart_doesNotDispatchToEngineSynchronously() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        service.onStartCommand(reconnectStartIntent(), 0, 2)

        assertFalse(
            "An auto-switch reconnect ACTION_START must not reach the engine (VPNLaunchHelper -> " +
                "Context.startForegroundService() against the engine's own service) synchronously " +
                "within the same onStartCommand() call -- see ENGINE_RECONNECT_DISPATCH_BUFFER_MS's " +
                "declaration comment for the FGS-obligation race this defers past",
            hasEngineStartLog()
        )
    }

    @Test
    fun reconnectStart_dispatchesToEngineAfterBuffer() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        service.onStartCommand(reconnectStartIntent(), 0, 2)
        assertFalse("precondition: no synchronous dispatch", hasEngineStartLog())

        // ENGINE_RECONNECT_DISPATCH_BUFFER_MS is 500ms; advance comfortably past it.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertTrue(
            "The deferred reconnect dispatch must still reach the engine once the buffer has " +
                "elapsed -- this is a delay, not a drop",
            hasEngineStartLog()
        )
    }

    @Test
    fun freshUserStart_dispatchesToEngineImmediately() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        service.onStartCommand(freshStartIntent(), 0, 1)

        assertTrue(
            "A fresh user-initiated Connect tap (isReconnect == false) has no preceding stop to " +
                "race and must be dispatched to the engine immediately, exactly as before this fix " +
                "-- the reconnect-only buffer must not add latency to this path",
            hasEngineStartLog()
        )
    }

    @Test
    fun userDisconnectDuringBufferWindow_suppressesDeferredEngineDispatch() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        service.onStartCommand(reconnectStartIntent(), 0, 2)
        assertFalse("precondition: no synchronous dispatch", hasEngineStartLog())

        // A genuine user Disconnect lands inside the buffer window.
        val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
        }
        service.onStartCommand(stopIntent, 0, 3)

        // Advance well past the buffer.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertFalse(
            "A genuine user Disconnect landing inside the reconnect-dispatch buffer window must " +
                "suppress the deferred engine dispatch entirely (userInitiatedStop guard), not " +
                "merely delay it -- otherwise the app would reconnect to the engine moments after " +
                "an explicit Disconnect",
            hasEngineStartLog()
        )
    }

    // R14-1 acceptance test (fix-cycle 14, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-
    // review-14.md): the auto-switch retry stop path is ACTION_STOP with preserveReconnect=true --
    // the ONLY stop ServerAutoSwitcher's retry machinery ever issues (VpnManager.stopVpn(
    // preserveReconnectHint = true) at ServerAutoSwitcher.kt:293 and :509). Before the fix, that
    // branch set userInitiatedStop=false and never bumped connectionAttemptGeneration, so the
    // deferred dispatch's guard (userInitiatedStop || serviceDestroyed || generation mismatch)
    // caught nothing and the engine start fired anyway. This test must FAIL at commit dbc8583
    // (before `connectionAttemptGeneration += 1` was added to that branch) and PASS after.
    @Test
    fun preserveReconnectStop_duringBufferWindow_suppressesDeferredEngineDispatch() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        service.onStartCommand(reconnectStartIntent(), 0, 2)
        assertFalse("precondition: no synchronous dispatch", hasEngineStartLog())

        // The auto-switch retry stop: ACTION_STOP with preserveReconnect=true, exactly what
        // ServerAutoSwitcher issues via VpnManager.stopVpn(preserveReconnectHint = true) when an
        // engine level or stall-timer fire lands mid-retry -- as opposed to a plain user Disconnect
        // (already covered by userDisconnectDuringBufferWindow_suppressesDeferredEngineDispatch).
        val preserveReconnectStopIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
            putExtra(VpnManager.extraPreserveReconnectKey(appContext), true)
        }
        service.onStartCommand(preserveReconnectStopIntent, 0, 3)

        // Advance well past the buffer.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertFalse(
            "The auto-switch retry stop (ACTION_STOP, preserveReconnect=true) landing inside the " +
                "reconnect-dispatch buffer window must suppress the deferred engine dispatch, just " +
                "like a plain user Disconnect does -- otherwise the deferred dispatch fires mid-stop " +
                "with a superseded config and re-arms a fresh engine FGS obligation, structurally " +
                "recreating the crash ENGINE_RECONNECT_DISPATCH_BUFFER_MS exists to widen (R14-1)",
            hasEngineStartLog()
        )
    }

    // R14-5(ii): the connectionAttemptGeneration supersession branch (lines 1165-1168 at the time
    // of review round 14) was asserted by the declaration comment but exercised by no test. Two
    // reconnect starts issued back-to-back within the same buffer window -- the second start
    // (e.g. a fresh auto-switch retry to a different server) must supersede the first, so exactly
    // one engine start fires, not two and not zero.
    @Test
    fun reconnectStart_supersededByNewerReconnectStart_dispatchesOnlyOnce() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        service.onStartCommand(reconnectStartIntent(startId = 2), 0, 2)
        service.onStartCommand(reconnectStartIntent(startId = 3), 0, 3)
        assertFalse("precondition: no synchronous dispatch", hasEngineStartLog())

        // Advance well past the buffer.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        val engineStartCount = ShadowLog.getLogs().count { it.tag == logTag && it.msg.contains(engineStartLog) }
        org.junit.Assert.assertEquals(
            "Exactly one engine start must fire when a second reconnect ACTION_START supersedes " +
                "the first inside the same buffer window -- the superseded (first) dispatch must be " +
                "skipped via the connectionAttemptGeneration mismatch guard, not fire a second, " +
                "stale engine start alongside the current one",
            1,
            engineStartCount
        )
    }

    // PR #127 review round 3 (Codex P1, thread 3792922991, OpenVpnService.kt:1199): acceptance
    // test for the finding described in this file's header comment above (fix-cycle 16).
    //
    // Exercises the REAL production chain end to end, not a mocked stand-in: ServerAutoSwitcher's
    // default `starter`/`stopper` are left untouched (still real VpnManager calls), and
    // requestSwitchNow()'s own preserveReconnect stop dispatch (VpnManager.stopVpn(appContext,
    // preserveReconnectHint = true), issued directly, not through the overridable `stopper` field)
    // is manually drained from the shadow Application's started-service queue and re-delivered
    // into THIS SAME service instance's onStartCommand() -- exactly what Android's singleton
    // service dispatch does in production, and the step that actually exercises R14-1's
    // generation-bump / R14-2's token-sweep machinery. Without this forwarding step the bug's
    // downstream effect could never be observed under Robolectric (context.startService() does not
    // auto-invoke onStartCommand on a live instance), which would make the test pass vacuously
    // regardless of whether the fix under test is present.
    @Test
    fun strayAidlLevelDuringBufferWindow_doesNotSkipSelectedServer() {
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf2")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)

        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        // Drain any started-service intents queued by setup above (e.g. the store save path),
        // so the drain loop below only picks up what ServerAutoSwitcher itself dispatches.
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()

        // The auto-switch reconnect ACTION_START whose engine-facing dispatch is deferred by
        // ENGINE_RECONNECT_DISPATCH_BUFFER_MS -- this is "the selected server" the finding says
        // gets skipped.
        service.onStartCommand(reconnectStartIntent(), 0, 2)
        assertFalse("precondition: no synchronous dispatch", hasEngineStartLog())

        // A late/stray AIDL LEVEL_AUTH_FAILED from the just-stopped (previous) engine lands mid-
        // buffer -- the new engine process for this attempt has not been asked to start yet, so
        // this level can only be a residual delivery from the old session (this codebase's own
        // comments document this exact re-delivery hazard class repeatedly: R9-1, R7-1's callers,
        // PR #126 round 13 comment 3734974189).
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        callbacks.updateStateString("AUTH_FAILED", null, 0, ConnectionStatus.LEVEL_AUTH_FAILED, null)

        // Forward whatever ServerAutoSwitcher's requestSwitchNow() may have dispatched via
        // VpnManager.stopVpn(preserveReconnectHint = true) back into this same service instance --
        // see the class-level comment above for why this step is required for falsifiability.
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        var nextId = 4
        var forwarded = shadowApp.nextStartedService
        while (forwarded != null) {
            service.onStartCommand(forwarded, 0, nextId++)
            forwarded = shadowApp.nextStartedService
        }

        // Advance well past the buffer.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

        assertTrue(
            "A stray/late AIDL terminal level (LEVEL_AUTH_FAILED) from the just-stopped engine, " +
                "landing during the reconnect engine-dispatch buffer window, must not be forwarded " +
                "to ServerAutoSwitcher -- doing so re-triggers requestSwitchNow(), whose " +
                "preserveReconnect stop bumps connectionAttemptGeneration and sweeps " +
                "reconnectEngineDispatchToken, cancelling the still-pending deferred dispatch and " +
                "skipping the originally selected server without ever trying it",
            hasEngineStartLog()
        )
        // Mechanism-anchored, not just outcome-anchored. A mutation probe proved the assertion
        // above alone can go vacuously green -- it stays true even with the guard reverted AND
        // ServerAutoSwitcher.onEngineLevel() stubbed inert, since the deferred dispatch is then
        // never cancelled for an unrelated reason. Asserting the
        // suppression log line directly pins that the guard's own suppression branch executed,
        // which is false in both halves of that compound mutation.
        assertTrue(
            "The suppression guard itself must have logged that it dropped the stray level -- " +
                "proving the guard's own branch executed, not merely that the engine start survived " +
                "for some unrelated reason",
            hasReconnectDispatchSuppressionLog()
        )
    }

    // R16-1 acceptance test (fix-cycle 17, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-review-16.md): review-16 proved with a falsifying probe that the deferred Runnable's
    // unconditional `reconnectDispatchPendingGeneration = -1` clear is wrong when TWO reconnect
    // buffers overlap. Sequence: buffer A (generation G, due T+500) is superseded by buffer B
    // (generation G+1, due T+700) before A's Runnable runs. At T+500, A's Runnable still executes
    // (it is not swept -- only the token-tagged dispatch itself would be swept, and neither
    // ACTION_START nor this scenario sweeps it) and, pre-fix, clears the marker to -1 even though
    // it currently holds G+1 (buffer B's own, still-pending marker) -- reopening
    // dispatchAutoSwitcherOnEngineLevel()'s stray-level suppression window for buffer B until
    // T+700. A stray AIDL level landing in that reopened window then reaches ServerAutoSwitcher,
    // whose requestSwitchNow() issues a preserveReconnect stop that cancels buffer B's still-
    // pending deferred dispatch -- skipping the selected server without ever trying it, the exact
    // defect this whole guard exists to close. This test must FAIL (no engine start log, because
    // buffer B gets cancelled) if the `if (reconnectDispatchPendingGeneration == dispatchGeneration)`
    // guard at the top of the deferred Runnable is reverted to an unconditional clear, and PASS
    // with the guard in place.
    @Test
    fun overlappingReconnectBuffers_earlierBufferResolutionDoesNotClearNewerBuffersGuard() {
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf2")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)

        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        Shadows.shadowOf(RuntimeEnvironment.getApplication()).clearStartedServices()

        // Buffer A: reconnect ACTION_START at t=0, due at t=500, generation G.
        service.onStartCommand(reconnectStartIntent(startId = 2), 0, 2)
        assertFalse("precondition: no synchronous dispatch", hasEngineStartLog())

        // Buffer B: a newer reconnect ACTION_START at t=200, due at t=700, generation G+1 --
        // supersedes buffer A (see reconnectStart_supersededByNewerReconnectStart_dispatchesOnlyOnce)
        // but buffer A's own deferred Runnable remains scheduled and will still run at t=500.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        service.onStartCommand(reconnectStartIntent(startId = 3), 0, 3)

        // Advance to just past buffer A's expiry (t=550) but well before buffer B's (t=700).
        // Buffer A's Runnable now runs and takes the "newer attempt has begun" skip branch
        // (connectionAttemptGeneration == G+1 != dispatchGeneration == G) -- but R16-1 is about
        // the unconditional clear that used to precede that check, not the check itself.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        assertFalse(
            "neither buffer should have produced an engine start yet at t=550",
            hasEngineStartLog()
        )

        // A late/stray AIDL LEVEL_AUTH_FAILED from the original (pre-buffer-A) engine lands in the
        // gap between buffer A's expiry and buffer B's -- exactly the window R16-1 identifies. If
        // reconnectDispatchPendingGeneration was wiped by buffer A's Runnable, this level is no
        // longer suppressed and reaches ServerAutoSwitcher.
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        callbacks.updateStateString("AUTH_FAILED", null, 0, ConnectionStatus.LEVEL_AUTH_FAILED, null)

        // Forward whatever ServerAutoSwitcher's requestSwitchNow() may have dispatched back into
        // this same service instance -- see strayAidlLevelDuringBufferWindow_doesNotSkipSelectedServer
        // above for why this forwarding step is required for falsifiability under Robolectric.
        val shadowApp = Shadows.shadowOf(RuntimeEnvironment.getApplication())
        var nextId = 4
        var forwarded = shadowApp.nextStartedService
        while (forwarded != null) {
            service.onStartCommand(forwarded, 0, nextId++)
            forwarded = shadowApp.nextStartedService
        }

        // Advance well past buffer B's expiry (t=950 total, comfortably clear of t=700).
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))

        assertTrue(
            "A stray AIDL level landing in the gap between an earlier, superseded reconnect " +
                "buffer's expiry and a later, still-pending buffer's expiry must not be able to " +
                "cancel the later buffer -- reconnectDispatchPendingGeneration must only be cleared " +
                "by the Runnable that owns that generation's marker (R16-1), not unconditionally by " +
                "whichever Runnable happens to resolve first",
            hasEngineStartLog()
        )
        // Mechanism-anchored companion assertion -- see
        // strayAidlLevelDuringBufferWindow_doesNotSkipSelectedServer above for why the outcome-only
        // assertion above can go vacuously green under the mutation probe (the ownership-scoped
        // clear reverted AND ServerAutoSwitcher.onEngineLevel() stubbed inert simultaneously).
        assertTrue(
            "The suppression guard itself must have logged that it dropped the stray level while " +
                "buffer B's marker was still live, proving the guard's own branch executed for " +
                "buffer B's generation -- not merely that the engine start survived for some " +
                "unrelated reason",
            hasReconnectDispatchSuppressionLog()
        )
    }

    // R5-1 acceptance test (fix-cycle 18, PR #127 review round 5, thread 3793613337,
    // OpenVpnService.kt:1243): see this file's header comment for the finding and mechanism.
    //
    // The gap under test is INSIDE the deferred Runnable's own synchronous execution -- between
    // clearing reconnectDispatchPendingGeneration and calling startIcsOpenVpn() -- which cannot be
    // reproduced by scheduling two Handler messages at different times (Robolectric runs one
    // Runnable to completion before the next is even considered). Instead this test plants a
    // Timber.Tree that intercepts the "Requested engine start" log line -- the synchronous LAST
    // statement inside startIcsOpenVpn(), still inside the deferred Runnable's own call stack --
    // and re-enters with a stray AIDL LEVEL_AUTH_FAILED from that exact point. Because
    // dispatchAutoSwitcherOnEngineLevel() is invoked here while Looper.myLooper() ==
    // Looper.getMainLooper() (Robolectric's paused main looper thread IS the test thread), it runs
    // its guard check and, if unsuppressed, the entire ServerAutoSwitcher.requestSwitchNow() chain
    // synchronously and in-line -- including SelectedCountryStore.nextServerCircular(), which
    // mutates the stored index immediately, with no dependency on forwarding queued started-service
    // intents. Checking the stored position after the hook fires is therefore a direct, exact probe
    // of what reconnectDispatchPendingGeneration held at the moment startIcsOpenVpn() dispatched:
    // pre-fix, the marker was already -1 (cleared before the dispatch call), so the stray level
    // slips through and immediately advances the store to server 2 -- the "newly selected server"
    // (server 1, already mid-dispatch to the engine) gets skipped without ever completing. Post-fix,
    // the marker still equals dispatchGeneration at that exact point, so the level is suppressed and
    // the store stays on server 1.
    @Test
    fun strayLevelArrivingAtEngineDispatch_doesNotSkipJustDispatchedServer() {
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf2")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)

        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")

        var reentered = false
        val probeTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                // Forward to android.util.Log so ShadowLog-based helpers (hasEngineStartLog())
                // keep working exactly as in every other test in this file -- planting a tree
                // routes AppLog through Timber instead of AppLog's own Log.println fallback
                // (see AppLog.log()'s `Timber.forest().isEmpty()` branch), so without this
                // forward the log capture used elsewhere in this class would go dark.
                android.util.Log.println(priority, tag ?: "", message)
                if (!reentered && message.contains(engineStartLog)) {
                    reentered = true
                    // The stray/late AIDL terminal level from the just-stopped (previous) engine,
                    // re-entering at the exact point where startIcsOpenVpn() is synchronously
                    // logging that it just dispatched server 1 to the engine -- the pre-fix gap
                    // between the marker clear and this call.
                    callbacks.updateStateString("AUTH_FAILED", null, 0, ConnectionStatus.LEVEL_AUTH_FAILED, null)
                }
            }
        }
        Timber.plant(probeTree)
        try {
            service.onStartCommand(reconnectStartIntent(), 0, 2)
            assertFalse("precondition: no synchronous dispatch", hasEngineStartLog())

            // ENGINE_RECONNECT_DISPATCH_BUFFER_MS is 500ms; advance comfortably past it so the
            // deferred Runnable fires and the probe tree re-enters mid-dispatch.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

            assertTrue(
                "precondition: the deferred dispatch for server 1 must still have reached the " +
                    "engine (the probe re-enters FROM that log line, so its absence means the " +
                    "test itself is broken, not that the fix is working)",
                hasEngineStartLog()
            )
            assertTrue("precondition: the probe must have re-entered", reentered)

            val position = SelectedCountryStore.getCurrentPosition(appContext)
            org.junit.Assert.assertEquals(
                "A stray AIDL terminal level landing between the pending-generation marker's " +
                    "clear and the engine dispatch call it is meant to guard must not be able to " +
                    "advance past the server that dispatch just started -- the store must stay on " +
                    "server 1 (position 1/2), not skip to server 2, otherwise the newly selected " +
                    "server is stopped and skipped moments after it was asked to start (R5-1)",
                1 to 2,
                position
            )
        } finally {
            Timber.uproot(probeTree)
        }
    }

    // R18-1 acceptance test (fix-cycle 19, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-gate-9.md, QG9-1): see this file's header comment for the finding. Uses the same
    // log-hook re-entry technique as strayLevelArrivingAtEngineDispatch_doesNotSkipJustDispatchedServer
    // above, but hooked to the "Session attempt" log line instead of "Requested engine start" --
    // that line sits squarely inside the pre-arm window on the pre-fix code path (the marker used
    // to stay armed only once the deferred Runnable's own postAtTime() branch was reached, far
    // below this log line).
    //
    // Fixture note: the two candidate servers are given DISTINCT ips ("ip1"/"ip2"), matching
    // review-18's stated intent to avoid an identical-ip fixture artifact. In practice this
    // fixture change alone does NOT make the position observable at the end of onStartCommand()
    // either way -- verified empirically while building this test: SelectedCountryStore
    // .saveLastStartedConfig(), which this same onStartCommand() call reaches right after the
    // hook fires, calls SelectedCountryStore.ensureIndexForConfig() again using THIS attempt's
    // ORIGINAL (pre-switch) config/ip, and that unconditionally re-locates and restores the
    // ORIGINAL server's own index whether the match lands via config, config+ip, or ip-only --
    // identical ips are not what makes it ambiguous, since the original server's own ip always
    // uniquely identifies IT specifically. That is why this test reads position synchronously at
    // injection time (see positionImmediatelyAfterInjection below) rather than after
    // onStartCommand() returns. Distinct ips are kept anyway because they are the
    // production-realistic fixture (real candidate servers never share an ip).
    //
    // ConnectionStateManager is pre-set to reconnectingHint=true/CONNECTING to mirror what
    // ServerAutoSwitcher itself re-asserts immediately before dispatching a retry ACTION_START
    // (see the waitingStopForRetry/LEVEL_NOTCONNECTED branch's declaration comment in
    // ServerAutoSwitcher.kt). Without this, dispatchAutoSwitcherOnEngineLevel()'s
    // wasConnectingAtDispatch capture would read ConnectionStateManager.state synchronously at the
    // hook point -- BEFORE this same onStartCommand() call's own
    // ConnectionStateManager.updateState(CONNECTING), which runs later, after the "Session
    // attempt" log -- so ServerAutoSwitcher's immediate-switch fast path would never fire and the
    // test would be vacuous regardless of whether the fix under test is present.
    @Test
    fun strayLevelDuringPreArmWindow_doesNotSkipSelectedServer() {
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2", 0, 0, 0, 0, 0, 0, "", "", "", "conf2")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)

        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")

        // Model an in-flight reconnect chain: a previous attempt already had the engine
        // CONNECTING and the reconnectingHint set -- see this test's class-level doc comment
        // above for why this is required for falsifiability.
        ConnectionStateManager.setReconnectingHint(true)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        var reentered = false
        // Captured synchronously, inside the hook, immediately after the injected level has been
        // fully processed -- NOT re-read after onStartCommand() fully returns. This same
        // onStartCommand() call continues on, after the hook returns, to
        // SelectedCountryStore.saveLastStartedConfig(applicationContext, title, config, targetIp)
        // -- and that call's own internal ensureIndexForConfig() re-sync, using THIS attempt's
        // ORIGINAL (pre-switch) config/ip, unconditionally re-locates and restores index 0
        // whenever a real skip happened, regardless of whether the fixture's server ips are
        // distinct or identical (confirmed empirically while building this test: the "matched by
        // ip"/"matched by config" fallback in SelectedCountryStore.ensureIndexForConfig()
        // unambiguously finds the ORIGINAL server by its own unchanged config/ip either way,
        // since those values never became stale relative to THAT server, only relative to the
        // store's current index). A position read taken later would therefore silently mask a
        // real skip. This is exactly the "after-inject" reading review-18's own probe used as its
        // load-bearing evidence (see gate-9 evidence: "REVIEW_PROBE after-inject pos=(2, 2)"),
        // documented there as more reliable than the probe's own final position read.
        var positionImmediatelyAfterInjection: Pair<Int, Int>? = null
        val probeTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                // Forward to android.util.Log so ShadowLog-based helpers keep working exactly as
                // in every other test in this file -- see strayLevelArrivingAtEngineDispatch_
                // doesNotSkipJustDispatchedServer above for why this forward is required once a
                // Tree is planted.
                android.util.Log.println(priority, tag ?: "", message)
                if (!reentered && message.contains("Session attempt")) {
                    reentered = true
                    // The stray/late AIDL terminal level from the just-stopped (previous) engine,
                    // re-entering at the exact point where the ACTION_START handler is
                    // synchronously logging the session attempt -- inside the pre-arm window
                    // R18-1 identified, between connectionAttemptGeneration's bump and
                    // reconnectDispatchPendingGeneration's arm.
                    callbacks.updateStateString("AUTH_FAILED", null, 0, ConnectionStatus.LEVEL_AUTH_FAILED, null)
                    positionImmediatelyAfterInjection = SelectedCountryStore.getCurrentPosition(appContext)
                }
            }
        }
        Timber.plant(probeTree)
        try {
            service.onStartCommand(reconnectStartIntent(), 0, 2)

            assertTrue("precondition: the probe must have re-entered", reentered)

            org.junit.Assert.assertEquals(
                "A stray AIDL terminal level landing in the pre-arm window between " +
                    "connectionAttemptGeneration's bump and reconnectDispatchPendingGeneration's " +
                    "arm must not be able to advance past the originally selected server -- the " +
                    "store must stay on server 1 (position 1/2), not skip to server 2, immediately " +
                    "after the stray level is processed, otherwise the newly selected server is " +
                    "stopped and skipped before it was ever asked to start (R18-1)",
                1 to 2,
                positionImmediatelyAfterInjection
            )
        } finally {
            Timber.uproot(probeTree)
        }
    }

    // Latch regression test (fix-cycle 19, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-gate-9.md, QG9-2): gate-9 proved by mutation that review-18's own verbatim
    // remediation for R18-1 -- arming reconnectDispatchPendingGeneration immediately after the
    // connectionAttemptGeneration bump, i.e. BEFORE the blank-config early return -- introduces a
    // NEW, reachable, permanently-latching defect: a blank-config reconnect ACTION_START bumps
    // the generation, arms the marker to match it, then returns before the deferred Runnable that
    // would ever clear the marker is posted (that Runnable is only reached past the early
    // return). The marker then stays equal to the live generation forever, and
    // dispatchAutoSwitcherOnEngineLevel()'s suppression check silently discards every subsequent
    // AIDL level until some later ACTION_START bumps the generation again. This test proves the
    // fix's ACTUAL placement (arm after the early return) does not have that problem: a
    // blank-config reconnect must leave the marker untouched, so a subsequent AIDL level is not
    // suppressed.
    @Test
    fun reconnectStartWithBlankConfig_doesNotLatchDispatchMarkerToNewGeneration() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")

        // suppressEngineState defaults true and is only flipped to false as a side effect of a
        // non-blank-config ACTION_START reaching that statement. Set it explicitly so the final
        // "Ignoring AIDL level" absence check below cannot be vacuously satisfied for an
        // unrelated reason.
        ReflectionHelpers.setField(service, "suppressEngineState", false)

        // A valid reconnect start first, so its deferred dispatch resolves and clears the marker
        // back to -1 -- establishing the same "no buffer pending" baseline a genuine blank-config
        // reconnect would be dispatched from in production, rather than starting from this test's
        // own artificial zero state.
        service.onStartCommand(reconnectStartIntent(), 0, 2)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
        assertTrue(
            "precondition: the valid reconnect's own deferred dispatch must have reached the " +
                "engine and cleared its marker",
            hasEngineStartLog()
        )

        // The blank-config reconnect start: bumps connectionAttemptGeneration, then must hit the
        // "No config to start" early return before ever reaching the deferred dispatch's
        // postAtTime() branch.
        val blankConfigReconnectIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraAutoSwitchKey(appContext), true)
            putExtra(VpnManager.extraConfigKey(appContext), "")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(blankConfigReconnectIntent, 0, 3)

        ShadowLog.clear()
        callbacks.updateStateString("AUTH_FAILED", null, 0, ConnectionStatus.LEVEL_AUTH_FAILED, null)

        val suppressionLogPresent = ShadowLog.getLogs().any {
            it.tag == logTag && it.msg.contains("Ignoring AIDL level")
        }
        assertFalse(
            "A blank-config reconnect ACTION_START must not latch " +
                "reconnectDispatchPendingGeneration to the freshly bumped " +
                "connectionAttemptGeneration -- doing so would permanently suppress every " +
                "subsequent AIDL level, since no deferred Runnable is ever posted on the " +
                "blank-config early-return path to clear it. This is exactly the reachable " +
                "defect gate-9 proved review-18's verbatim remediation would introduce (QG9-2)",
            suppressionLogPresent
        )
    }

    // R19-1 acceptance test (fix-cycle 20, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-gate-10.md): gate-10 found the :1208-:1226 gap has NO interceptable seam -- the only
    // log line in that stretch (AppLog.e("No config to start")) sits on the blank-config path,
    // which returns before ever arming the marker, so the log-hook re-entry technique used by
    // strayLevelArrivingAtEngineDispatch_doesNotSkipJustDispatchedServer and
    // strayLevelDuringPreArmWindow_doesNotSkipSelectedServer above cannot reproduce this window.
    // This is therefore a guard-isolating test (precedent: commit 86eb087, "Add guard-isolating
    // regression tests for the fix-cycle-4 auto-switch guard", fix-cycle 5) that drives the fixed
    // guard's logic directly rather than attempting to time a real race into an unhookable gap:
    //   1. Model onStartCommand()'s :1208 statement having already run (connectionAttemptGeneration
    //      bumped to a live value) but its :1226 statement not yet
    //      (reconnectDispatchPendingGeneration still at its "no buffer pending" default) --
    //      exactly the torn (marker=stale, generation=G) pair the gap exposes.
    //   2. Deliver a stray AIDL level from a REAL background thread (not the test's own
    //      main-looper thread), so dispatchAutoSwitcherOnEngineLevel() takes its postAtTime()
    //      deferred branch -- the capture-time :2777 check reads the torn pair and does NOT
    //      suppress, exactly like the real binder thread would inside the gap.
    //   3. While that dispatch sits queued on the (Robolectric-paused) main looper, arm the
    //      marker to the live generation -- modeling :1226 completing on the main thread while
    //      the stray dispatch is still in flight, queued.
    //   4. Drain the main looper. The fixed execution-time re-check (next to :2816) must catch
    //      what the capture-time check missed; without it, ServerAutoSwitcher.onEngineLevel() is
    //      reached and its immediate-switch fast path fires (state is CONNECTING).
    // Falsifiability: this test must FAIL (starter() invoked) if the guard added next to :2816 in
    // this fix cycle is removed, and PASS with it in place.
    @Test
    fun executionTimeGuard_suppressesStrayLevelUnsuppressedAtCaptureTimeButArmedBeforeExecution() {
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2", 0, 0, 0, 0, 0, 0, "", "", "", "conf2")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)

        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ServerAutoSwitcher.resetForTest()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        val originalStarter = ServerAutoSwitcher.starter
        val originalStopper = ServerAutoSwitcher.stopper
        val startCalls = mutableListOf<String>()
        ServerAutoSwitcher.starter = { _, config, _, _ -> startCalls.add(config) }
        ServerAutoSwitcher.stopper = { _ -> true }

        // Invoke dispatchAutoSwitcherOnEngineLevel() directly, reflectively, rather than going
        // through the real callbacks.updateStateString()/syncEngineState() entry point: this test
        // is deliberately guard-isolating (per the gate's testability note), and syncEngineState()
        // has several OTHER early-return guards (maybeStartStaleStopReconciliation,
        // shouldIgnoreLevelAfterUserStop) whose own state this test does not set up and does not
        // want to depend on -- the target under test is dispatchAutoSwitcherOnEngineLevel()'s own
        // capture-time/execution-time predicate pair, nothing upstream of it.
        val dispatchMethod = OpenVpnService::class.java.getDeclaredMethod(
            "dispatchAutoSwitcherOnEngineLevel", ConnectionStatus::class.java
        )
        dispatchMethod.isAccessible = true

        // Step 1: model the state right after the generation bump but before the marker arm --
        // generation already bumped to a live value, marker still at its "no buffer pending"
        // default. The generation counter is an AtomicInteger, not a plain Int field, so it
        // cannot be overwritten via ReflectionHelpers.setField -- fetch the existing AtomicInteger
        // instance and mutate it in place instead. Both fields live on the extracted
        // ReconnectDispatchGuard, reached via one extra reflection hop.
        val guard = ReflectionHelpers.getField<ReconnectDispatchGuard>(service, "reconnectDispatchGuard")
        ReflectionHelpers.getField<AtomicInteger>(guard, "attemptGeneration").set(7)
        ReflectionHelpers.setField(guard, "pendingGeneration", -1)

        try {
            // Step 2: deliver the stray level from a genuine background thread so the dispatch is
            // deferred via postAtTime(), not run synchronously in-line the way every other test in
            // this file (calling from the test's own main-looper thread) does.
            val thread = Thread {
                dispatchMethod.invoke(service, ConnectionStatus.LEVEL_AUTH_FAILED)
            }
            thread.isDaemon = true
            thread.start()
            thread.join(5_000)
            if (thread.isAlive) thread.interrupt()
            assertFalse("background thread did not finish within timeout", thread.isAlive)

            // Step 3: onStartCommand()'s own (main) thread reaches the marker-arm statement and
            // arms the marker to the SAME generation the just-captured, still-queued dispatch used.
            ReflectionHelpers.setField(guard, "pendingGeneration", 7)

            // Step 4: drain the main looper so the deferred Runnable runs. If the stray level is
            // NOT suppressed, this only reaches ServerAutoSwitcher.onEngineLevel()'s
            // shouldSwitchImmediately branch, which ARMS a two-phase switch (waitingStopForRetry
            // = true, pendingConfig = "conf2", an engine stop requested) -- it does not invoke
            // starter() synchronously, exactly like requestSwitchNow() never does in production
            // (see switchesAfterThresholdUsingChainedStopStart in ServerAutoSwitcherTest.kt for
            // the same two-phase shape).
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            // Confirm engine teardown, exactly as that same two-phase pattern requires: this is
            // what actually invokes starter() -- if, and only if, the stray level above reached
            // ServerAutoSwitcher and armed a pending retry. The retry-commit dispatch itself is
            // posted with a further START_AFTER_STOP_DELAY_MS delay (see
            // switchesAfterThresholdUsingChainedStopStart in ServerAutoSwitcherTest.kt, which
            // advances 500ms for the same reason), so idleFor -- not a plain idle() -- is required
            // to actually run it.
            ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, "AIDL")
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

            assertTrue(
                "The execution-time re-check added next to the :2816 generation guard (R19-1) " +
                    "must suppress a stray AIDL level whose capture-time :2777 check missed it " +
                    "because the (marker, generation) pair was torn across onStartCommand()'s " +
                    "connectionAttemptGeneration bump (:1208) and reconnectDispatchPendingGeneration " +
                    "arm (:1226) -- without it, ServerAutoSwitcher.onEngineLevel() is reached, its " +
                    "immediate-switch fast path (state was CONNECTING) arms a pending retry to " +
                    "server 2, and the subsequent NOTCONNECTED confirmation completes it, skipping " +
                    "the newly selected server without ever trying it",
                startCalls.isEmpty()
            )
        } finally {
            ServerAutoSwitcher.starter = originalStarter
            ServerAutoSwitcher.stopper = originalStopper
            ServerAutoSwitcher.resetForTest()
        }
    }

    // R20-1 acceptance test (fix-cycle 21, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-review-20.md): review-20 found connectionAttemptGeneration was a plain @Volatile Int
    // mutated with a non-atomic `+= 1` read-modify-write from THREE writer sites -- ACTION_START
    // (:1232), the preserveReconnect ACTION_STOP branch (:1385), and finishStopFlowConfirmed()
    // (:874). The third is reached via the AIDL binder-thread callback updateStateString ->
    // syncEngineState -> handleEngineLevelForStop -- a genuinely different thread from the other
    // two (both main, via onStartCommand()). A concurrent binder-thread finishStopFlowConfirmed()
    // and main-thread ACTION_START/ACTION_STOP bump could both read the same value and both write
    // value+1, losing an increment. A lost increment leaves the live generation one lower than it
    // should be, letting a dispatch captured under an already-superseded attempt match the live
    // generation at every guard built on this counter (:1226/:1250, :1280/:1342, :2802,
    // :2829/:2841, :2871) and reach ServerAutoSwitcher -- the same skip-without-trying end-state
    // as every other variant in this defect family (R7-1/R9-1/R18-1/R19-1).
    //
    // This test drives the REAL connectionAttemptGeneration field on a real OpenVpnService
    // instance -- fetched by reflection, the exact AtomicInteger object every one of the three
    // production writer sites calls incrementAndGet() on -- with a high volume of concurrent
    // increments from many real threads (not the test's own main-looper thread, and not
    // serialized through any handler), and asserts the final count is exactly the number of
    // increments issued: the classic proof that a non-atomic `+= 1` loses updates under
    // contention and AtomicInteger.incrementAndGet() does not.
    //
    // Falsifiability: reverting connectionAttemptGeneration's declaration back to
    // `@Volatile private var connectionAttemptGeneration: Int = 0` makes
    // `ReflectionHelpers.getField<AtomicInteger>(...)` throw a ClassCastException at runtime,
    // failing this test immediately -- confirmed by mutation (review-21, M-1): reverting the
    // production file to its pre-R20-1 content (declaration AND all three call sites back to
    // `Int`/`+= 1`) and re-running this exact test produces exactly that ClassCastException at
    // this test's own getField<AtomicInteger>(...) line, NOT a below-expected count.
    // CORRECTED (R21-3, fix-cycle 22, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-
    // review-21.md): the previous version of this comment claimed the opposite -- that a full
    // revert reproduces "a genuine lost-update failure, not a ClassCastException". Review-21's
    // mutation testing (M-1) disproved that claim empirically: a full revert throws
    // ClassCastException (3 failures), never a below-expected count, because reverting the
    // declaration's type is exactly what breaks this test's own reflective field lookup before
    // its concurrency assertion ever runs. This test therefore pins only the field's TYPE
    // (AtomicInteger vs plain Int), not the atomicity of any specific production writer in
    // isolation -- see reconnectRetryRacingBinderThreadStopConfirmation_doesNotStartWithAStaleDispatchGeneration
    // below for a test that drives a real production writer (finishStopFlowConfirmed()) from a
    // genuine background thread and is provably sensitive to a single writer's atomicity, closing
    // the gap review-21 (R21-2) found in this test (mutation M-2: reverting ONLY the binder-thread
    // writer at finishStopFlowConfirmed() to `set(get() + 1)` left this test fully green, because
    // this test's own threads bypass every production writer and increment the field directly).
    @Test
    fun connectionAttemptGeneration_concurrentBumpsFromMultipleThreads_loseNoIncrement() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val guard = ReflectionHelpers.getField<ReconnectDispatchGuard>(service, "reconnectDispatchGuard")
        val generation = ReflectionHelpers.getField<AtomicInteger>(guard, "attemptGeneration")
        generation.set(0)

        val threadCount = 64
        val incrementsPerThread = 500
        val expectedTotal = threadCount * incrementsPerThread

        val threads = (1..threadCount).map {
            Thread {
                repeat(incrementsPerThread) {
                    // Mirrors exactly what each of the three production writer sites now does --
                    // see connectionAttemptGeneration's declaration comment (R20-1).
                    generation.incrementAndGet()
                }
            }
        }
        threads.forEach { it.isDaemon = true }
        threads.forEach { it.start() }
        threads.forEach { it.join(15_000) }
        val stillAlive = threads.count { it.isAlive }
        assertTrue("$stillAlive of $threadCount increment threads did not finish within timeout", stillAlive == 0)

        org.junit.Assert.assertEquals(
            "connectionAttemptGeneration must lose no increments under concurrent load from " +
                "multiple threads -- this is what makes it safe for finishStopFlowConfirmed() " +
                "(AIDL binder thread) to race ACTION_START / the preserveReconnect ACTION_STOP " +
                "branch (both main thread) without the live generation falling behind, which is " +
                "what let a superseded dispatch's captured generation match the live value and " +
                "evade every guard built on this counter (R20-1)",
            expectedTotal,
            generation.get()
        )
    }

    // R21-1 acceptance test (fix-cycle 22, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-review-21.md): review-21 found that R20-1's fix (connectionAttemptGeneration ->
    // AtomicInteger) made the counter itself atomic but left its COMPOUND use non-atomic --
    // onStartCommand()'s reconnect branch bumps the counter once (incrementAndGet(), whose result
    // used to be discarded), then read it back TWICE independently via two separate .get() calls:
    // once to arm reconnectDispatchPendingGeneration (the marker), once to capture the local
    // dispatchGeneration used by the deferred engine-dispatch Runnable. A binder-thread
    // finishStopFlowConfirmed() bump landing between those two reads was observed by only the
    // SECOND one, desynchronizing the marker (stale) from dispatchGeneration (live) for what must
    // be treated as the SAME attempt -- disarming both the :2802 R18-1 capture-time guard and the
    // :2871 R19-1 execution-time guard at once (both compare the marker against a fresh live
    // read, and the marker no longer matched what this attempt's own dispatchGeneration was).
    //
    // Unlike connectionAttemptGeneration_concurrentBumpsFromMultipleThreads_loseNoIncrement above
    // (which increments the raw field from its OWN threads, bypassing every production writer --
    // see that test's corrected KDoc, R21-2/R21-3), this test drives a REAL production writer for
    // real: a genuine, reflective invocation of the private finishStopFlowConfirmed() method --
    // the exact function the AIDL binder thread reaches in production via updateStateString ->
    // syncEngineState -> handleEngineLevelForStop -- on a genuine background java.lang.Thread,
    // racing a real ACTION_START (isReconnect=true) running through service.onStartCommand() on
    // this test's own main-looper thread. userInitiatedStop is set true immediately before
    // starting that thread, to satisfy finishStopFlowConfirmed()'s own `if (!userInitiatedStop)
    // return` guard -- modeling review-21's interleaving table step 2 (the binder thread having
    // already validated that guard) before ACTION_START's own :1172 statement clears the flag on
    // the main thread moments later. Thread.join() is used only to pin WHERE in onStartCommand()'s
    // execution the bump lands -- strictly between the marker arm and the dispatchGeneration
    // capture -- not to fake the bump itself; the increment is performed for real, on a real
    // second thread, by the real production method. The re-entry point is the "Session attempt"
    // log line, which sits in exactly that window on the current (R18-1-fixed) code path -- see
    // strayLevelDuringPreArmWindow_doesNotSkipSelectedServer above, which independently confirms
    // this same log line now falls after the marker arm, not before it.
    //
    // Falsifiability: this test must FAIL (the engine (re)starts for this attempt, and the marker
    // leaks at a stale generation instead of clearing to -1) if OpenVpnService.kt's fix is
    // reverted to the pre-fix double-.get() pattern (dispatchGeneration re-read independently of
    // the marker's own .get(), instead of both being derived from one captured local), and PASS
    // with the R21-1 fix in place. Verified by mutation at fix-cycle-22 implementation time: the
    // revert was applied, this test was confirmed to fail with exactly the predicted symptom
    // (hasEngineStartLog() true, marker left at a stale generation instead of -1), and the fix was
    // restored and reconfirmed green -- see the implementation report for the exact evidence.
    @Test
    fun reconnectRetryRacingBinderThreadStopConfirmation_doesNotStartWithAStaleDispatchGeneration() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        val finishStopFlowConfirmedMethod = OpenVpnService::class.java.getDeclaredMethod(
            "finishStopFlowConfirmed", ConnectionStatus::class.java, String::class.java
        )
        finishStopFlowConfirmedMethod.isAccessible = true

        var reentered = false
        val probeTree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                // Forward to android.util.Log so ShadowLog-based helpers (hasEngineStartLog())
                // keep working exactly as in every other test in this file that plants a Tree.
                android.util.Log.println(priority, tag ?: "", message)
                if (!reentered && message.contains("Session attempt")) {
                    reentered = true
                    // Satisfy finishStopFlowConfirmed()'s own `if (!userInitiatedStop) return`
                    // guard right before racing it -- see this test's class-level comment above
                    // for why this models step 2 of review-21's interleaving table rather than
                    // bypassing the guard.
                    ReflectionHelpers.setField(service, "userInitiatedStop", true)
                    val thread = Thread {
                        finishStopFlowConfirmedMethod.invoke(
                            service, ConnectionStatus.LEVEL_NONETWORK, "test-binder-race"
                        )
                    }
                    thread.isDaemon = true
                    thread.start()
                    thread.join(5_000)
                    if (thread.isAlive) thread.interrupt()
                }
            }
        }
        Timber.plant(probeTree)
        try {
            service.onStartCommand(reconnectStartIntent(), 0, 2)

            assertTrue("precondition: the probe must have re-entered", reentered)

            val guard = ReflectionHelpers.getField<ReconnectDispatchGuard>(service, "reconnectDispatchGuard")
            val markerAtCaptureTime = ReflectionHelpers.getField<Int>(guard, "pendingGeneration")
            val liveGenerationAfterRace = ReflectionHelpers.getField<AtomicInteger>(
                guard, "attemptGeneration"
            ).get()
            org.junit.Assert.assertEquals(
                "precondition: finishStopFlowConfirmed() must have bumped the live counter to " +
                    "exactly one past this attempt's own marker, or the race this test models " +
                    "against the marker-arm/dispatchGeneration-capture window did not occur",
                markerAtCaptureTime + 1,
                liveGenerationAfterRace
            )

            // Advance well past the buffer so the deferred Runnable resolves.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))

            assertFalse(
                "A confirmed stop (finishStopFlowConfirmed(), landing on a genuine background " +
                    "thread strictly between reconnectDispatchPendingGeneration's arm and this " +
                    "attempt's own dispatchGeneration capture) must be recognized as superseding " +
                    "this reconnect attempt, so the deferred engine dispatch must be skipped, not " +
                    "started. R21-1 (fix-cycle 22): before the fix, dispatchGeneration was " +
                    "re-read via a SECOND, independent .get() call after this race, silently " +
                    "picking up the binder-thread bump -- making it equal the (also re-read) live " +
                    "counter at the deferred Runnable's execution-time check and incorrectly " +
                    "letting the start proceed",
                hasEngineStartLog()
            )

            val markerAfter = ReflectionHelpers.getField<Int>(guard, "pendingGeneration")
            org.junit.Assert.assertEquals(
                "reconnectDispatchPendingGeneration must be cleared back to -1, not left latched " +
                    "at a stale generation, once the newer-attempt guard recognizes the concurrent " +
                    "bump and skips the start -- clearMarkerIfOwn() only clears when marker == " +
                    "dispatchGeneration, which now holds because both are the SAME frozen " +
                    "attemptGeneration local rather than two independent live reads (R21-1)",
                -1,
                markerAfter
            )
        } finally {
            Timber.uproot(probeTree)
        }
    }
}
