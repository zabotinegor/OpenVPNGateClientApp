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
    private var originalStarter: ((android.content.Context, String, String?, Boolean) -> Unit)? = null
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

    @Test
    fun idleToleranceWaitsBeforeStartingTimer() {
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.UNKNOWN_LEVEL, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(null, ServerAutoSwitcher.remainingSeconds.value)

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(2, ServerAutoSwitcher.remainingSeconds.value)
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

