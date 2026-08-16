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
import java.time.Duration

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
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [27])
class OpenVpnServiceReconnectEngineDispatchTest {

    private val appContext = RuntimeEnvironment.getApplication()
    private val logTag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ":" + "OpenVpnService"
    private val engineStartLog = "Requested engine start"

    private fun hasEngineStartLog(): Boolean =
        ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains(engineStartLog) }

    @Before
    fun setUp() {
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
        // Hygiene for strayAidlLevelDuringBufferWindow_doesNotSkipSelectedServer, which is the
        // only test in this file that touches the ServerAutoSwitcher singleton's static state.
        // Harmless no-op for every other test in this class.
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
    }
}
