package com.yahorzabotsin.openvpnclientgate.vpn

import android.os.Looper
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.servers.probe.ProbeRequestQueue
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLog
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest = org.robolectric.annotation.Config.NONE)
class ServerAutoSwitcherTest {
    private val appContext = RuntimeEnvironment.getApplication()
    private val logTag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ":" + "ServerAutoSwitcher"
    private val source = "VPN_STATUS"
    private var originalStarter: ((android.content.Context, String, String?, Boolean) -> Boolean)? = null
    private var originalStopper: ((android.content.Context) -> Unit)? = null
    private data class Call(val ctx: android.content.Context, val cfg: String, val title: String?, val reconnect: Boolean)
    private val calls = mutableListOf<Call>()
    private var stopCalls = 0

    @Before
    fun setUp() {
        ConnectionStateManager.setReconnectingHint(false)
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        ServerAutoSwitcher.setNoReplyThresholdForTest(2)
        ServerAutoSwitcher.setRepliedThresholdForTest(2)
        UserSettingsStore.saveStatusStallTimeoutSeconds(appContext, 2)
        originalStarter = ServerAutoSwitcher.starter
        ServerAutoSwitcher.starter = { ctx, config, title, reconnect -> calls.add(Call(ctx, config, title, reconnect)) }
        originalStopper = ServerAutoSwitcher.stopper
        ServerAutoSwitcher.stopper = { _ -> stopCalls += 1 }
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf2")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        calls.clear()
        stopCalls = 0
    }

    @After
    fun tearDown() {
        originalStarter?.let { ServerAutoSwitcher.starter = it }
        originalStopper?.let { ServerAutoSwitcher.stopper = it }
        ServerAutoSwitcher.resetNoReplyThreshold()
        ServerAutoSwitcher.resetRepliedThreshold()
        ServerAutoSwitcher.setProbeRequestQueueForTest(null)
        ServerAutoSwitcher.v2HydrationCallback = null
        ServerAutoSwitcher.resetForTest()
    }

    // beginChainedSwitch reports whether a switch was actually begun. The watchdog relies on this
    // to avoid consuming a recovery attempt on a dispatch that never happened, so every path that
    // aborts internally must return false rather than looking like success.

    @Test
    fun beginChainedSwitch_returnsFalseWhenAutoSwitchDisabled() {
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, false)

        assertFalse(
            "a skipped switch is not a begun switch",
            ServerAutoSwitcher.beginChainedSwitch(appContext, "client\n", "RU")
        )
    }

    @Test
    fun beginChainedSwitch_returnsFalseWhenStopDispatchRejected() {
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        // VpnManager.startControllerService catches IllegalStateException from startService and
        // returns false -- the background-start restriction case.
        val rejectingContext = object : android.content.ContextWrapper(appContext) {
            override fun startService(service: android.content.Intent?): android.content.ComponentName? =
                throw IllegalStateException("background start not allowed")
        }

        assertFalse(
            "a rejected stop dispatch aborts the switch, so it must not report success",
            ServerAutoSwitcher.beginChainedSwitch(rejectingContext, "client\n", "RU")
        )
    }

    @Test
    fun switchesAfterThresholdUsingChainedStopStart() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        // Cross threshold (configured to 2s in setUp). This requests a stop and
        // arms a chained start pending NOTCONNECTED.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // No start should be triggered yet until NOTCONNECTED is observed.
        assertEquals(0, calls.size)

        // Engine reports teardown state; chained start should fire shortly after.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        // Give a bit more than the internal delay (350ms)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, calls.size)
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf2", current?.config)
    }

    // R19-3 (fix-cycle 20, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-gate-10.md):
    // QG9-3 (fix-cycle 19) added !cfg.isNullOrBlank() hardening at both retry-commit read sites
    // (this test exercises the NOTCONNECTED-observed branch) plus a blank-rejecting write at
    // requestSwitchNow()'s pendingConfig assignment -- but shipped with zero test coverage; gate-10
    // verified by mutation that all three edits survive reversion with a green suite. Proves at
    // least one of those guards actually rejects a blank config end to end: server 2 (the "next"
    // server nextServerCircular() will select) has a blank config, so starting the engine with an
    // empty profile must never happen.
    @Test
    fun retryCommit_doesNotStartNextServerWithBlankConfig() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        // Cross threshold (configured to 2s in setUp) -- requests a stop and arms a chained start
        // to server 2, whose config is blank.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // Engine reports teardown complete; the retry-commit guard must reject the blank pending
        // config here instead of starting the engine with an empty profile.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "A blank config on the server selected by the switch must never reach starter() -- " +
                "QG9-3's !cfg.isNullOrBlank() hardening (both the requestSwitchNow() source " +
                "assignment and the retry-commit read guards) exists precisely to prevent this",
            0,
            calls.size
        )
    }

    // The blank-config fall-through above leaves the NOTCONNECTED level to reach the non-timeout
    // `else` branch in onEngineLevel(), where resetCycle = !shouldKeepCycle -- and shouldKeepCycle
    // reads reconnectingHint, which requestSwitchNow() had just set true for the (aborted) switch.
    // Pre-fix, cancel(resetCycle = false) never touched the hint, latching reconnectingHint=true with
    // no retry in flight -- which keeps OpenVpnService's reconnectPending guard satisfied and the
    // controller foreground-service notification retained indefinitely. Fixed by explicitly clearing
    // the hint in the blank-config fall-through branch itself.
    @Test
    fun blankConfigFallThrough_clearsReconnectingHintWithNoRetryInFlight() {
        val blankConfigServers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", blankConfigServers)
        SelectedCountryStore.resetIndex(appContext)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertTrue(
            "precondition: requestSwitchNow() must have set the hint true for the (blank-config) switch",
            ConnectionStateManager.reconnectingHint.value
        )

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals("no retry should ever have been dispatched", 0, calls.size)
        assertFalse(
            "reconnectingHint must not remain latched true once the blank-config fall-through " +
                "settles with no retry in flight -- otherwise the controller FGS notification is " +
                "retained with nothing pending to justify it",
            ConnectionStateManager.reconnectingHint.value
        )
    }

    @Test
    fun startsTimerForServerRepliedAndSwitches() {
        // Trigger timer on SERVER_REPLIED
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED, source)
        // After 1s, remaining should be 4 (replied threshold is 5s with settings=2)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(4, ServerAutoSwitcher.remainingSeconds.value)
        // Cross threshold (5s) -> should request stop and arm chained start
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        // No immediate start until NOTCONNECTED
        assertEquals(0, calls.size)
        // Now report NOTCONNECTED and allow delayed start
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, calls.size)
        assertEquals(true, calls.first().reconnect)
    }

    @Test
    fun cancelsOnStateChange() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        // Cancel before crossing the (test) threshold of 2 seconds
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_START, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(0, calls.size)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf1", current?.config)
    }

    @Test
    fun authFailedStartsChainedSwitchImmediately() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        assertEquals(2, ServerAutoSwitcher.remainingSeconds.value)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_AUTH_FAILED, source)
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)
        assertEquals(0, calls.size)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertEquals(1, calls.size)
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
    }

    // PR #126 round 13 (Codex P1, comment 3734974189): the poll loop can re-deliver the SAME
    // cached terminal snapshot (e.g. LEVEL_NONETWORK) on every ~2s poll cycle without it ever
    // going stale, because applyStatusSnapshot() restores lastStatusSnapshotMs to the snapshot's
    // OWN timestamp, not "now". Before the fix, a duplicate dispatch of an already-in-progress
    // immediate-switch level fell through to the generic timeoutLevels/else block and hit
    // `else -> cancel(...)`, silently cancelling the switch the FIRST dispatch had already
    // correctly begun. Verify the duplicate is a no-op and the original switch still completes.
    @Test
    fun duplicateImmediateSwitchDispatchDoesNotCancelInProgressSwitch() {
        // Get an active timer running so the first LEVEL_NONETWORK dispatch takes the
        // immediate-switch fast path (timerActive || isConnecting).
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)

        // First dispatch: triggers requestSwitchNow() -> waitingStopForRetry=true, pending
        // config armed, engine stop requested. No start yet until NOTCONNECTED is observed.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NONETWORK, "AIDL")
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)
        assertEquals(0, calls.size)

        // Duplicate dispatch of the IDENTICAL level while the switch is still in progress
        // (waitingStopForRetry still true). Must be a no-op: the pending switch must survive.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NONETWORK, "AIDL")

        // The switch armed by the FIRST dispatch must still complete normally.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "duplicate dispatch must not cancel the switch already in progress",
            1,
            calls.size
        )
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
    }

    // Bug 86cb35fbt, fix-cycle 6 (manual QA B23, docs/qa-evidence/86cb35fbt-vpn-foreground-
    // service-crash-qa-2.md "Secondary finding" section): a stale/re-delivered engine level
    // (e.g. a spurious LEVEL_CONNECTED flash from a Service instance racing an unrelated stop
    // path) arriving WHILE waitingStopForRetry is true used to fall through to the generic
    // `else -> cancel(...)` branch, silently discarding the pending retry -- with almost no log
    // trace, since cancel()'s only log line requires timerActive/seconds, both already reset to
    // false/0 during this wait window. The real, later LEVEL_NOTCONNECTED confirmation then had
    // no pending retry left to act on, so the promised switch to the next server was silently
    // dropped. Verify the spurious level is ignored and the real NOTCONNECTED still completes
    // the retry.
    @Test
    fun staleLevelDuringStopForRetryDoesNotSilentlyDropPendingRetry() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        // Cross threshold (2s) -> requests stop, arms waitingStopForRetry with pendingConfig=conf2.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // Spurious/stale level (e.g. a stray AIDL snapshot) arrives before the real NOTCONNECTED.
        // Before the fix this reached the unconditional else-branch cancel(...), wiping the
        // pending retry.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, "AIDL")
        assertEquals(
            "a stale level while waiting for the stop-before-retry confirmation must not start a switch itself",
            0,
            calls.size
        )

        // The real NOTCONNECTED confirmation must still resolve the pending retry.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "the pending retry must not be silently dropped by a stale intervening level",
            1,
            calls.size
        )
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
    }

    // Bug 86cb35fbt, fix-cycle 6 (manual QA B24, same evidence file): a stale/re-delivered
    // timeoutLevels level (e.g. a re-delivered LEVEL_CONNECTING_NO_SERVER_REPLY_YET snapshot)
    // arriving WHILE waitingStopForRetry is true used to reach the timeoutLevels branch and
    // start(...) a brand-new competing timer, since timerActive is false during this wait
    // window. Verify no competing timer is started and the real NOTCONNECTED confirmation still
    // drives exactly one retry.
    @Test
    fun staleTimeoutLevelDuringStopForRetryDoesNotStartCompetingTimer() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)

        // Stale re-delivered timeoutLevels snapshot while waiting for the stop-before-retry
        // confirmation. Before the fix this would call start(...), reporting a fresh
        // remainingSeconds value and racing the pending retry.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, "AIDL")
        assertEquals(
            "a stale timeoutLevels snapshot must not start a competing timer while waiting for stop-before-retry confirmation",
            null,
            ServerAutoSwitcher.remainingSeconds.value
        )

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "exactly one retry must fire, driven only by the real NOTCONNECTED confirmation",
            1,
            calls.size
        )
        assertEquals("conf2", calls.first().cfg)
        assertEquals(true, calls.first().reconnect)
    }

    // Fix-cycle 7 review (docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-7.md,
    // R7-1/R7-4): the cycle-6 guard above stops ServerAutoSwitcher from silently dropping the
    // pending retry, but ServerAutoSwitcher is not the only consumer of an engine level --
    // OpenVpnService.syncEngineState() also forwards every level to
    // ConnectionStateManager.updateFromEngine(), unconditionally. Before the R7-1 fix, the same
    // stale LEVEL_CONNECTED this guard ignores still reached updateFromEngine() and cleared
    // reconnectingHint / flipped state CONNECTING -> CONNECTED, so by the time this retry's
    // ACTION_START fired, ConnectionStateManager read state=DISCONNECTED / hint=false -- exactly
    // the condition that defeats OpenVpnService's stopAfterOneShotSyncConfirmedRunnable,
    // VpnManager.stopControllerIfIdle, and syncEngineState's reconnectPending FGS guard (see the
    // review file's Verification section, PROBE detail, for the exact assertion this test
    // inverts: REVIEW-PROBE calls=1 stateAtStart=DISCONNECTED hintAtStart=false / expected
    // CONNECTING but was DISCONNECTED). This test interleaves updateFromEngine() after each
    // onEngineLevel() call, matching OpenVpnService.syncEngineState()'s real production ordering,
    // and asserts the invariant holds AT THE MOMENT starter() is invoked -- the only place R7-1 is
    // observable. Falsifiability: reverting the two ConnectionStateManager re-assertion calls
    // added at both retry-commit sites in ServerAutoSwitcher.kt must make this fail with
    // expected:<CONNECTING> but was:<DISCONNECTED>.
    @Test
    fun staleLevelDuringStopForRetry_reconnectInvariantHoldsAtRetryDispatch() {
        var stateAtDispatch: ConnectionState? = null
        var hintAtDispatch: Boolean? = null
        ServerAutoSwitcher.starter = { ctx, config, title, reconnect ->
            stateAtDispatch = ConnectionStateManager.state.value
            hintAtDispatch = ConnectionStateManager.reconnectingHint.value
            calls.add(Call(ctx, config, title, reconnect))
        }
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, null)
        // Cross threshold (2s) -> requests stop, arms waitingStopForRetry with pendingConfig=conf2.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // Spurious/stale LEVEL_CONNECTED, interleaved with updateFromEngine() exactly as
        // OpenVpnService.syncEngineState() does in production -- this is what corrupts
        // ConnectionStateManager's state/hint if the R7-1 fix is absent or reverted.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, "AIDL")
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_CONNECTED, null)
        assertEquals(
            "precondition: ServerAutoSwitcher itself must still ignore the stale level (cycle 6)",
            0,
            calls.size
        )

        // The real NOTCONNECTED confirmation resolves the pending retry and fires starter().
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, calls.size)
        assertEquals(
            "the retry's ACTION_START must be dispatched with the reconnect invariant intact " +
                "(state != DISCONNECTED), otherwise it defeats OpenVpnService's " +
                "stopAfterOneShotSyncConfirmedRunnable/stopControllerIfIdle/reconnectPending FGS " +
                "guards (review-7 R7-1)",
            ConnectionState.CONNECTING,
            stateAtDispatch
        )
        assertEquals(
            "reconnectingHint must also be re-asserted at retry-commit time",
            true,
            hintAtDispatch
        )
    }

    @Test
    fun noAlternativeServersDoesNotSwitch() {
        val single = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", single)
        SelectedCountryStore.resetIndex(appContext)
        ShadowLog.clear()

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

        assertEquals(0, calls.size)
        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf1", current?.config)

        val hadFullCycleLog = ShadowLog.getLogs().any { it.tag == logTag && it.msg.contains("completed full server cycle") }
        assertEquals(true, hadFullCycleLog)
        assertEquals(1, stopCalls)
    }

    @Test
    fun fullCycleRestoresStartIndex() {
        UserSettingsStore.saveStatusStallTimeoutSeconds(appContext, 1)
        ServerAutoSwitcher.setNoReplyThresholdForTest(1)
        ServerAutoSwitcher.setRepliedThresholdForTest(1)
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf1"),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf2"),
            Server(3, "n3", "c3", Country("RU"), 0, SignalStrength.STRONG, "ip", 0, 0, 0, 0, 0, 0, "", "", "", "conf3")
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.setCurrentIndex(appContext, 1)
        calls.clear()
        stopCalls = 0
        ShadowLog.clear()

        // 1) Switch to conf3
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        // 2) Switch to conf1
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        // 3) Full cycle completes -> stop and restore start index
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1100))

        val current = SelectedCountryStore.currentServer(appContext)
        assertEquals("conf2", current?.config)
        assertEquals(true, calls.isNotEmpty())
        assertEquals(1, stopCalls)
    }

    // Regression test for QG4-2(a) (fix-cycle 8, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-gate-4.md): the retry-commit dispatch used to be posted as an anonymous, untracked
    // handler.postDelayed lambda that cancel() could not reference, let alone remove. A user
    // Disconnect landing inside the START_AFTER_STOP_DELAY_MS (350ms) window used to leave that
    // lambda armed, so the app auto-reconnected ~350ms after an explicit Disconnect despite
    // cancelForUserStop() having already run (ACTION_START unconditionally clears
    // userInitiatedStop, so nothing downstream re-blocked it either). See
    // OpenVpnServiceNotificationTest's finishStopFlowConfirmed_abortsStopSelf_... tests for the
    // crash-adjacent half of this same finding (QG4-2(b)).
    // Falsifiability: this must fail if the shared retryStartRunnable tracking field is reverted
    // back to an untracked postDelayed lambda.
    @Test
    fun cancelForUserStop_withinRetryDelayWindow_preventsOrphanedReconnect() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // Real NOTCONNECTED confirmation arms the retry-commit dispatch for
        // START_AFTER_STOP_DELAY_MS (350ms) from here.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)

        // A user Disconnect lands inside the 350ms window.
        ServerAutoSwitcher.cancelForUserStop()

        // Advance well past the retry-commit delay.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "cancelForUserStop() landing inside the retry-commit delay window must prevent the " +
                "previously-untracked posted lambda from firing an orphaned reconnect after an " +
                "explicit user Disconnect",
            0,
            calls.size
        )
    }

    // Sibling coverage: the STOP_RETRY_TIMEOUT_MS fallback path (no NOTCONNECTED ever observed)
    // posts its own retry-commit dispatch through the same shared retryStartRunnable field --
    // verify cancel() reaches that one too.
    @Test
    fun cancelForUserStop_withinRetryDelayWindow_afterTimeoutPath_preventsOrphanedReconnect() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // No NOTCONNECTED ever arrives; the STOP_RETRY_TIMEOUT_MS (5s) fallback commits the retry
        // and arms the same 350ms retry-commit dispatch.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        // A user Disconnect lands inside the resulting 350ms window.
        ServerAutoSwitcher.cancelForUserStop()

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "cancelForUserStop() must also prevent the timeout-path retry-commit dispatch from firing",
            0,
            calls.size
        )
    }

    // R12-1 (fix-cycle 12, docs/qa-evidence/86cb35fbt-vpn-foreground-service-crash-review-12.md):
    // review-12 proved rollBackFailedRetryDispatch() itself was never executed by any test --
    // deleting both `if (!starter(...)) rollBackFailedRetryDispatch()` call sites (leaving a bare
    // `starter(...)`) still left the scoped vpn suite 244/244 green, because every starter test
    // double in this suite returns true. These two tests drive a starter failure through each of
    // the two independently-edited retry-commit sites (NOTCONNECTED-observed and
    // stop-retry-timeout) and assert the rollback actually lands: ConnectionStateManager must
    // return to DISCONNECTED with reconnectingHint=false rather than being stranded on the
    // CONNECTING re-assertion R7-1 makes just ahead of the dispatch. Falsifiability: each must
    // fail (state stays CONNECTING) if its site's `if (!starter(...)) rollBackFailedRetryDispatch()`
    // is reverted to a bare `starter(...)`.

    @Test
    fun retryCommitDispatchFailure_notConnectedPath_rollsBackToDisconnected() {
        ServerAutoSwitcher.starter = { _, _, _, _ -> false }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // Real NOTCONNECTED confirmation arms the retry-commit dispatch (350ms from here) and
        // re-asserts CONNECTING/reconnectingHint=true (R7-1) just ahead of it.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        assertEquals(
            "precondition: the NOTCONNECTED-observed retry-commit site must re-assert CONNECTING " +
                "before dispatching",
            ConnectionState.CONNECTING,
            ConnectionStateManager.state.value
        )
        assertTrue(
            "precondition: reconnectingHint must be re-asserted before the retry-commit dispatch",
            ConnectionStateManager.reconnectingHint.value
        )
        assertTrue(
            "precondition: the timed switch that led here must have set cycleStartIndex (via " +
                "beginChainedSwitch), or the F11 assertion below proves nothing",
            ServerAutoSwitcher.cycleStartIndexForTest() != null
        )

        // Advance past START_AFTER_STOP_DELAY_MS (350ms): the retry-commit dispatch fires, starter
        // returns false, and rollBackFailedRetryDispatch() must run.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "a failed retry-commit dispatch on the NOTCONNECTED-observed path must roll " +
                "ConnectionStateManager back to DISCONNECTED, not strand it on CONNECTING with no " +
                "ACTION_START ever delivered to move it off",
            ConnectionState.DISCONNECTED,
            ConnectionStateManager.state.value
        )
        assertFalse(
            "reconnectingHint must be cleared by the rollback",
            ConnectionStateManager.reconnectingHint.value
        )
        // F11 (docs/qa-evidence/release-review-2.md): a failed retry-commit dispatch must also
        // reset the switch cycle so a stale cycleStartIndex cannot make the next auto-switch
        // cycle's wrap detection give up early.
        assertEquals(
            "rollBackFailedRetryDispatch() must reset cycleStartIndex so the next auto-switch " +
                "cycle does not inherit a stale wrap-detection start index from this aborted attempt",
            null,
            ServerAutoSwitcher.cycleStartIndexForTest()
        )
    }

    @Test
    fun retryCommitDispatchFailure_timeoutPath_rollsBackToDisconnected() {
        ServerAutoSwitcher.starter = { _, _, _, _ -> false }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // No NOTCONNECTED ever arrives; the STOP_RETRY_TIMEOUT_MS (5s) fallback commits the retry,
        // re-asserting CONNECTING/reconnectingHint=true (R7-1) just ahead of its own dispatch.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        assertEquals(
            "precondition: the stop-retry-timeout site must re-assert CONNECTING before dispatching",
            ConnectionState.CONNECTING,
            ConnectionStateManager.state.value
        )
        assertTrue(
            "precondition: reconnectingHint must be re-asserted before the retry-commit dispatch",
            ConnectionStateManager.reconnectingHint.value
        )
        assertTrue(
            "precondition: the timed switch that led here must have set cycleStartIndex (via " +
                "beginChainedSwitch), or the F11 assertion below proves nothing",
            ServerAutoSwitcher.cycleStartIndexForTest() != null
        )

        // Advance past START_AFTER_STOP_DELAY_MS (350ms): the retry-commit dispatch fires, starter
        // returns false, and rollBackFailedRetryDispatch() must run.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "a failed retry-commit dispatch on the stop-retry-timeout path must roll " +
                "ConnectionStateManager back to DISCONNECTED, not strand it on CONNECTING with no " +
                "ACTION_START ever delivered to move it off",
            ConnectionState.DISCONNECTED,
            ConnectionStateManager.state.value
        )
        assertFalse(
            "reconnectingHint must be cleared by the rollback",
            ConnectionStateManager.reconnectingHint.value
        )
        // F11 (docs/qa-evidence/release-review-2.md): a failed retry-commit dispatch must also
        // reset the switch cycle so a stale cycleStartIndex cannot make the next auto-switch
        // cycle's wrap detection give up early.
        assertEquals(
            "rollBackFailedRetryDispatch() must reset cycleStartIndex so the next auto-switch " +
                "cycle does not inherit a stale wrap-detection start index from this aborted attempt",
            null,
            ServerAutoSwitcher.cycleStartIndexForTest()
        )
    }

    // Regression tests for R9-1 (fix-cycle 9, docs/qa-evidence/86cb35fbt-vpn-foreground-service-
    // crash-review-9.md): QG4-2's fix above made retryStartRunnable trackable so cancel() could
    // remove it for a genuine cancelForUserStop() -- but onEngineLevel()'s own generic
    // `else -> cancel(...)` branch is ALSO reached by any stray/re-delivered engine level landing
    // inside the same START_AFTER_STOP_DELAY_MS (350ms) retry-commit window, since
    // waitingStopForRetry is already cleared by the time the window opens and no longer shields it
    // (see the cycle-6 stale-level guard just above in onEngineLevel(), and retryCommitInFlight's
    // declaration comment in ServerAutoSwitcher.kt). Before the R9-1 fix, either stray level below
    // silently discarded the pending retry -- no VPN process, no pending reconnect, UI stuck on
    // "Connecting" forever, the exact symptom fixed once already in e8fa60e (bug 86cb21563).
    // Falsifiability: these must fail (retry does not fire, calls.size stays 0) if
    // retryCommitInFlight is removed or its onEngineLevel() check is removed, while the two
    // cancelForUserStop_withinRetryDelayWindow_* tests above must keep passing (a genuine user
    // Disconnect must still cancel the retry).

    @Test
    fun strayDuplicateNotConnectedInRetryCommitWindow_doesNotCancelPendingRetry() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // Real NOTCONNECTED confirmation arms the retry-commit dispatch for
        // START_AFTER_STOP_DELAY_MS (350ms) from here; waitingStopForRetry is cleared immediately.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)

        // A stray duplicate NOTCONNECTED lands inside the 350ms window -- e.g. the engine emitting
        // LEVEL_NOTCONNECTED twice back-to-back for EXITING then NOPROCESS during the same
        // teardown. This is not a user Disconnect; the pending retry must survive it.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "a stray duplicate NOTCONNECTED landing inside the retry-commit window must not " +
                "cancel the pending retry -- doing so leaves the app stuck on Connecting with no " +
                "VPN process and no pending reconnect",
            1,
            calls.size
        )
        assertEquals(true, calls.first().reconnect)
    }

    @Test
    fun strayConnectedInRetryCommitWindow_doesNotCancelPendingRetry() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)

        // A stray/stale LEVEL_CONNECTED lands inside the 350ms window -- e.g. a re-delivered cached
        // terminal snapshot from the poll loop. This is not a user Disconnect; the pending retry
        // must survive it.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, source)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "a stray LEVEL_CONNECTED landing inside the retry-commit window must not cancel the " +
                "pending retry -- doing so leaves the app stuck on Connecting with no VPN process " +
                "and no pending reconnect",
            1,
            calls.size
        )
        assertEquals(true, calls.first().reconnect)
    }

    // Sibling coverage: the same stray-level protection must also apply to the retry armed by the
    // STOP_RETRY_TIMEOUT_MS fallback path (no NOTCONNECTED ever observed), not just the
    // NOTCONNECTED-observed path above.
    @Test
    fun strayConnectedInRetryCommitWindow_afterTimeoutPath_doesNotCancelPendingRetry() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(0, calls.size)

        // No NOTCONNECTED ever arrives; the STOP_RETRY_TIMEOUT_MS (5s) fallback commits the retry
        // and arms the same 350ms retry-commit dispatch.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, source)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(
            "a stray LEVEL_CONNECTED landing inside the timeout-path retry-commit window must not " +
                "cancel the pending retry",
            1,
            calls.size
        )
        assertEquals(true, calls.first().reconnect)
    }

    @Test
    fun stopRetryTimeoutStartsNextServerWithoutNotConnected() {
        ShadowLog.clear()

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // No NOTCONNECTED is emitted; timeout should still trigger a start.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, calls.size)
        assertEquals(true, calls.first().reconnect)
    }

    // Fix-cycle 7 review R7-1/R7-4: the same reconnect-invariant re-assertion is required at the
    // SECOND retry-commit site -- the STOP_RETRY_TIMEOUT_MS fallback runnable, reached when the
    // real NOTCONNECTED confirmation never arrives at all within the 5s window. Mirrors
    // staleLevelDuringStopForRetry_reconnectInvariantHoldsAtRetryDispatch above but drives the
    // stale-level corruption via a stale LEVEL_CONNECTED with no NOTCONNECTED follow-up, letting
    // the timeout path itself fire the retry.
    @Test
    fun stopRetryTimeout_reconnectInvariantHoldsAtRetryDispatch() {
        var stateAtDispatch: ConnectionState? = null
        var hintAtDispatch: Boolean? = null
        ServerAutoSwitcher.starter = { ctx, config, title, reconnect ->
            stateAtDispatch = ConnectionStateManager.state.value
            hintAtDispatch = ConnectionStateManager.reconnectingHint.value
            calls.add(Call(ctx, config, title, reconnect))
        }
        ShadowLog.clear()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, null)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        // Stale LEVEL_CONNECTED corrupts ConnectionStateManager exactly as in the sibling test --
        // but no NOTCONNECTED ever follows, so only the STOP_RETRY_TIMEOUT_MS fallback resolves it.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, "AIDL")
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_CONNECTED, null)

        // No NOTCONNECTED is emitted; the 5s STOP_RETRY_TIMEOUT_MS fallback must still trigger a
        // start, with the invariant re-asserted.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, calls.size)
        assertEquals(
            "the timeout-path retry's ACTION_START must also be dispatched with the reconnect " +
                "invariant intact (review-7 R7-1)",
            ConnectionState.CONNECTING,
            stateAtDispatch
        )
        assertEquals(true, hintAtDispatch)
    }

    @Test
    fun idleToleranceWaitsBeforeStartingTimer() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.UNKNOWN_LEVEL, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, ServerAutoSwitcher.remainingSeconds.value)
    }

    // R7-3 (fix-cycle 7 review): beginChainedSwitch() must cancel any idle-tolerance runnable
    // armed just before it runs. requestSwitchNow()'s equivalent transition into
    // waitingStopForRetry=true goes through cancel(resetCycle=false), which already cancels idle
    // tolerance as a side effect; beginChainedSwitch() did not, so an idleToleranceRunnable armed
    // within UNKNOWN_PAUSED_GRACE_MS before a beginChainedSwitch() call (its production callers:
    // OpenVpnService's VPN_STATUS auto-switch path and the watchdog-recovery starter) could still
    // fire mid-window via start(appContext, level) called directly -- not through onEngineLevel()
    // -- bypassing the waitingStopForRetry guard and starting a competing timer (B24's mechanism
    // through a different door).
    @Test
    fun beginChainedSwitch_cancelsPendingIdleTolerance() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.UNKNOWN_LEVEL, source)
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)

        val begun = ServerAutoSwitcher.beginChainedSwitch(appContext, "client\n", "RU")
        assertTrue(begun)

        // Advance well past UNKNOWN_PAUSED_GRACE_MS (3s) but under STOP_RETRY_TIMEOUT_MS (5s). If
        // idle tolerance were not cancelled, it would fire start(appContext, UNKNOWN_LEVEL) here,
        // directly bypassing onEngineLevel()'s waitingStopForRetry guard, and remainingSeconds
        // would become non-null.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))

        assertEquals(
            "an idle-tolerance runnable armed before beginChainedSwitch() must not survive to " +
                "start a competing timer mid-window",
            null,
            ServerAutoSwitcher.remainingSeconds.value
        )
    }

    // US-12 AC-2: DEFAULT_V2 hydration gap — hydration callback is triggered and probe code is reachable.
    // Note: when the server store is empty (total==0), getCurrentServerIdIfMatchingLastStarted also
    // returns 0 (no current server to match), so the probe guard (failingServerId != 0) correctly
    // prevents spurious enqueues. This test verifies that: (a) the hydration path is entered,
    // (b) the probe guard works (no enqueue for id=0), and (c) no crash occurs.
    @Test
    fun defaultV2HydrationGap_hydrationPathEnteredAndNoProbeForEmptyStore() {
        // Set ServerSource to DEFAULT_V2 so the hydration path is taken
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        try {
            // Empty server list triggers the DEFAULT_V2 hydration path (total==0)
            SelectedCountryStore.saveSelection(appContext, "RU", emptyList())

            val fakeQueue = object : ProbeRequestQueue {
                val enqueuedIds = mutableListOf<Int>()
                override fun enqueue(serverId: Int) { enqueuedIds.add(serverId) }
            }
            ServerAutoSwitcher.setProbeRequestQueueForTest(fakeQueue)

            // Wire a v2HydrationCallback that records the call but does NOT invoke onDone
            var hydrationCallbackInvoked = false
            ServerAutoSwitcher.v2HydrationCallback = { _, _ ->
                hydrationCallbackInvoked = true
            }

            // Start the timer and let it expire — will hit the DEFAULT_V2 hydration path
            ConnectionStateManager.setReconnectingHint(false)
            ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
            ConnectionStateManager.updateState(ConnectionState.CONNECTING)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

            assertTrue("Hydration callback must be invoked when store is empty and source=DEFAULT_V2", hydrationCallbackInvoked)
            // When total==0, currentServer() is null → failingServerId==0 → probe guard prevents enqueue
            assertTrue("No probe enqueued when failingServerId=0 (empty store)", fakeQueue.enqueuedIds.isEmpty())
        } finally {
            UserSettingsStore.saveServerSource(appContext, ServerSource.VPNGATE)
        }
    }

}

