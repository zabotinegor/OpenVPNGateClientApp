package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import android.os.Looper
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * ClickUp 12487v2bg7u: the reconnect cycle must follow server identity across mid-cycle refreshes,
 * keep same-IP entries distinct, and keep the persisted index (UI counter) on the candidate that is
 * actually started.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ServerAutoSwitcherCycleIdentityTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val source = "VPN_STATUS"
    private val started = mutableListOf<String>()
    private var stopCalls = 0
    private var originalStarter: ((Context, String, String?, Boolean) -> Boolean)? = null
    private var originalStopper: ((Context) -> Boolean)? = null

    @Before
    fun setUp() {
        ConnectionStateManager.setReconnectingHint(false)
        UserSettingsStore.saveAutoSwitchWithinCountry(ctx, true)
        UserSettingsStore.saveStatusStallTimeoutSeconds(ctx, 1)
        ServerAutoSwitcher.setNoReplyThresholdForTest(1)
        ServerAutoSwitcher.setRepliedThresholdForTest(1)
        originalStarter = ServerAutoSwitcher.starter
        originalStopper = ServerAutoSwitcher.stopper
        ServerAutoSwitcher.starter = { _, config, _, _ -> started.add(config); true }
        ServerAutoSwitcher.stopper = { _ -> stopCalls += 1; true }
        started.clear()
        stopCalls = 0
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.clearStopFailure()
        ServerAutoSwitcher.resetForTest()
    }

    @After
    fun tearDown() {
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        ConnectionStateManager.clearStopFailure()
        originalStarter?.let { ServerAutoSwitcher.starter = it }
        originalStopper?.let { ServerAutoSwitcher.stopper = it }
        ServerAutoSwitcher.resetNoReplyThreshold()
        ServerAutoSwitcher.resetRepliedThreshold()
        ServerAutoSwitcher.resetForTest()
    }

    private fun srv(id: Int, config: String, ip: String = "9.9.9.9") = Server(
        lineIndex = 1, name = "n-$config", city = "city-$config", country = Country("RU"), ping = 1,
        signalStrength = SignalStrength.STRONG, ip = ip, score = 1, speed = 1, numVpnSessions = 1,
        uptime = 1, totalUsers = 1, totalTraffic = 1, logType = "", operator = "", message = "",
        configData = config, id = id
    )

    private fun select(vararg servers: Server, index: Int = 0, country: String = "RU") {
        SelectedCountryStore.saveSelection(ctx, country, servers.toList())
        SelectedCountryStore.setCurrentIndex(ctx, index)
    }

    private fun idle(ms: Long) = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    /** The in-flight attempt stalls: timer fires, stop is dispatched, switch to the next candidate is armed. */
    private fun stallCurrentAttempt() {
        ServerAutoSwitcher.onEngineLevel(ctx, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        idle(1100)
    }

    /** Engine confirms the stop; the retry commit (ACTION_START dispatch) fires. */
    private fun confirmStopAndCommit() {
        ServerAutoSwitcher.onEngineLevel(ctx, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        idle(500)
    }

    private fun failAndSwitch() {
        stallCurrentAttempt()
        confirmStopAndCommit()
    }

    private fun currentConfig() = SelectedCountryStore.currentServer(ctx)?.config

    @Test
    fun sameIpEntries_areAllAttemptedOnceAndCounterFollowsEachStart() {
        select(srv(0, "c1"), srv(0, "c2"), srv(0, "c3"), index = 1)

        failAndSwitch()
        assertEquals(listOf("c3"), started)
        assertEquals("counter must show the server that was started", 3 to 3, SelectedCountryStore.getCurrentPosition(ctx))

        failAndSwitch()
        assertEquals(listOf("c3", "c1"), started)
        assertEquals(1 to 3, SelectedCountryStore.getCurrentPosition(ctx))

        // Cycle exhausted: c2 (start), c3, c1 all attempted once. No retry, stop, index back on the start.
        stallCurrentAttempt()
        assertEquals(listOf("c3", "c1"), started)
        assertEquals(1, stopCalls)
        assertEquals("c2", currentConfig())
        assertNull(ServerAutoSwitcher.cycleVisitedForTest())
    }

    @Test
    fun refreshBetweenSelectionAndStart_doesNotRollBackTheCounter() {
        select(srv(1, "A"), srv(2, "B"), srv(3, "C"), srv(4, "D"), index = 2)

        // Auto-switch picks D (4/4) and waits for the stop to complete.
        stallCurrentAttempt()
        assertEquals(4 to 4, SelectedCountryStore.getCurrentPosition(ctx))

        // SSE servers-changed sync lands in that window: list reordered and grown.
        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "RU", listOf(srv(9, "N"), srv(4, "D"), srv(3, "C"), srv(2, "B"), srv(1, "A"))
        )
        confirmStopAndCommit()

        assertEquals(listOf("D"), started)
        assertEquals("D", currentConfig())
        assertEquals(2 to 5, SelectedCountryStore.getCurrentPosition(ctx))
    }

    @Test
    fun refreshDuringCycle_reorderAddAndRemove_neverRetriesAnAttemptedServer() {
        select(srv(1, "A"), srv(2, "B"), srv(3, "C"), srv(4, "D"), index = 0)

        failAndSwitch() // A failed -> B
        assertEquals(listOf("B"), started)

        // Mid-cycle refresh: C removed, E added at the head, remaining order reversed.
        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "RU", listOf(srv(5, "E"), srv(4, "D"), srv(2, "B"), srv(1, "A"))
        )
        assertEquals("B", currentConfig())

        failAndSwitch() // B failed -> must not wrap back to A
        failAndSwitch()
        stallCurrentAttempt() // exhausted

        assertEquals(listOf("B", "E", "D"), started)
        assertEquals(1, stopCalls)
        assertEquals("cycle end restores the start server by identity", "A", currentConfig())
    }

    @Test
    fun removalOfTheInFlightCandidate_continuesWithItsSuccessor() {
        select(srv(1, "A"), srv(2, "B"), srv(3, "C"), srv(4, "D"), index = 0)

        failAndSwitch() // A -> B in flight
        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "RU", listOf(srv(1, "A"), srv(3, "C"), srv(4, "D"))
        )

        failAndSwitch() // B (now gone) fails -> C, not A and not D
        failAndSwitch()
        stallCurrentAttempt()

        assertEquals(listOf("B", "C", "D"), started)
        assertEquals(1, stopCalls)
    }

    @Test
    fun serversAddedAfterEveryServerWasAttempted_areStillOfferedBeforeExhaustion() {
        select(srv(1, "A"), srv(2, "B"), index = 0)

        failAndSwitch() // A -> B; list would be exhausted on the next failure
        SelectedCountryStore.saveSelectionPreservingIndex(
            ctx, "RU", listOf(srv(1, "A"), srv(2, "B"), srv(3, "NEW"))
        )
        failAndSwitch()
        stallCurrentAttempt()

        assertEquals(listOf("B", "NEW"), started)
        assertEquals(1, stopCalls)
    }

    @Test
    fun userDisconnectMidCycle_dropsTheCycleAndNextFailureStartsAFreshOne() {
        select(srv(1, "A"), srv(2, "B"), srv(3, "C"), index = 0)
        failAndSwitch() // A -> B
        assertNotNull(ServerAutoSwitcher.cycleVisitedForTest())

        ServerAutoSwitcher.cancelForUserStop()
        assertNull(ServerAutoSwitcher.cycleVisitedForTest())

        // User reconnects manually on C; a failure there must walk the full list afresh.
        SelectedCountryStore.setCurrentIndex(ctx, 2)
        started.clear()
        failAndSwitch()
        assertEquals(listOf("A"), started)
        assertEquals(1 to 3, SelectedCountryStore.getCurrentPosition(ctx))
    }

    @Test
    fun manualStartOnSecondSameIpEntry_seedsCycleWithThatEntry() {
        select(srv(0, "c1"), srv(0, "c2"), index = 1)
        // What a manual ACTION_START does with the decoded config and the shared IP.
        SelectedCountryStore.ensureIndexForConfig(ctx, "decoded", "9.9.9.9")
        SelectedCountryStore.saveLastStartedConfig(ctx, "RU", "decoded", "9.9.9.9")

        failAndSwitch()

        assertEquals("the second entry failed, so the first is next -- not the second again", listOf("c1"), started)
        assertEquals(1 to 2, SelectedCountryStore.getCurrentPosition(ctx))
    }

    @Test
    fun fallbackSeeding_marksFailingServerAndChainedCandidateAsVisited() {
        select(srv(1, "A"), srv(2, "B"), srv(3, "C"), index = 0)
        val keys = SelectedCountryStore.currentServerKeys(ctx)

        ServerAutoSwitcher.seedCycleFromCurrentServer(ctx)
        SelectedCountryStore.nextServer(ctx) // VPN_STATUS fallback advances the index itself
        ServerAutoSwitcher.markCurrentServerAttempted(ctx)

        assertEquals(listOf(keys[0], keys[1]), ServerAutoSwitcher.cycleVisitedForTest())
    }

    @Test
    fun refreshReplacingEveryVisitedServer_doesNotSkipTheParkedUnattemptedServer() {
        select(srv(1, "A"), srv(2, "B"), srv(3, "C"), index = 0)
        failAndSwitch() // A -> B in flight, visited {A, B}

        // Pool replaced: neither A nor B exists any more; the persisted index (0) was never attempted.
        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "RU", listOf(srv(4, "C"), srv(5, "D")))

        failAndSwitch()
        failAndSwitch()
        stallCurrentAttempt()

        assertEquals("every server of the new pool is attempted exactly once", listOf("B", "D", "C"), started)
        assertEquals(1, stopCalls)
    }

    @Test
    fun newSelectionDuringCycle_discardsTheStaleCycleAndKeepsTheUsersSelection() {
        select(srv(1, "A"), srv(2, "B"), index = 0)
        failAndSwitch() // A -> B, cycle visited {A, B}

        // The user picks another country (disjoint pool) while the old cycle state is still around.
        select(srv(11, "X"), srv(12, "Y"), srv(13, "Z"), index = 0, country = "DE")
        started.clear()

        failAndSwitch()

        assertEquals("a stale cycle must not make the new country look exhausted", listOf("Y"), started)
        assertEquals("DE", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals(2 to 3, SelectedCountryStore.getCurrentPosition(ctx))
    }

    @Test
    fun backgroundSyncForSupersededCountry_doesNotTouchTheUsersSelectionOrCycle() {
        select(srv(1, "A"), srv(2, "B"), srv(3, "C"), index = 0)
        failAndSwitch() // A -> B
        select(srv(11, "X"), srv(12, "Y"), index = 1, country = "DE")

        // Late sync result for the previous country must not resurrect it.
        SelectedCountryStore.saveSelectionPreservingIndex(ctx, "RU", listOf(srv(1, "A"), srv(2, "B"), srv(3, "C")))

        assertEquals("DE", SelectedCountryStore.getSelectedCountry(ctx))
        assertEquals("Y", currentConfig())
    }

    @Test
    fun nonReconnectBehaviour_singleServerStillCompletesCycleImmediately() {
        select(srv(1, "A"), index = 0)

        stallCurrentAttempt()

        assertEquals(emptyList<String>(), started)
        assertEquals(1, stopCalls)
        assertEquals("A", currentConfig())
    }
}
