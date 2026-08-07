package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Intent
import com.yahorzabotsin.openvpnclientgate.core.logging.LogTags
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.IStatusCallbacks
import de.blinkt.openvpn.core.StatusSnapshot
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenVpnServiceStatusSyncTest {
    private val appContext = RuntimeEnvironment.getApplication()
    private val logTag = LogTags.APP + ":" + "OpenVpnService"

    @Before
    fun setUp() {
        ShadowLog.clear()
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.clearStopFailure()
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("vpn_stop_teardown", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.clearStopFailure()
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("vpn_stop_teardown", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    @Test
    fun ignoresVpnStatusWhenAidlFresh() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        ReflectionHelpers.setField(service, "boundToStatus", true)
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        callbacks.updateStateString("CONNECTED", null, 0, ConnectionStatus.LEVEL_CONNECTED, null)

        service.updateState("CONNECTING", null, 0, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, null)

        val source = ReflectionHelpers.getField<Any>(service, "statusSource")
        assertNotNull(source)
        assertEquals("AIDL", source.toString())
    }

    @Test
    fun supplementsConnectingDetailFromVpnStatusWhenAidlFresh() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", System.currentTimeMillis())

        service.updateState("TCP_CONNECT", null, 0, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, null)

        assertEquals("TCP_CONNECT", ConnectionStateManager.engineDetail.value)
    }

    @Test
    fun staleSnapshotsTriggerRebindAfterThreshold() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = System.currentTimeMillis()

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        // Round 15 fix (Codex P2, comment 3735628745): applyStatusSnapshot() now backfills
        // currentAttemptStartMs from the first active-level snapshot it observes when unknown
        // (0L), so it no longer stays unknown across these 3 identical calls the way it did
        // before this fix -- the fallback age-only check this test used to exercise via the
        // default 0L only applies before that first backfill. Setting currentAttemptStartMs
        // explicitly to a point AFTER the snapshot's own timestamp keeps this test's original
        // intent intact (a snapshot predating the current attempt, repeated 3x, must still be
        // rejected every time and trigger a forced rebind) via the known-attempt predates-check
        // instead of the now-changed unknown-start fallback.
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now)

        val snapshot = StatusSnapshot(
            "CONNECTING",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now - 20_000L,
            0L
        )

        ShadowLog.clear()

        repeat(3) {
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, snapshot)
            )
        }

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(logs.any { it.contains("Forcing status rebind") })
    }

    @Test
    fun applyStatusSnapshot_wakesAutoSwitcherWhenLivePushChannelIsStale() {
        // Regression for BUG-autoswitch-stale-push-stall (AC1): when the live AIDL push
        // callback (updateStateString) stalls beyond aidlFreshWindowMs, this snapshot-poll
        // fallback must still drive ServerAutoSwitcher so its timeout timer starts. Before
        // the fix, allowAutoSwitch was hardcoded to false here, so a stalled push channel
        // left the app stuck on "Connecting..." forever with no switch timer running.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = System.currentTimeMillis()

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // Live push channel stalled well beyond aidlFreshWindowMs (3_000L).
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 10_000L)
        ServerAutoSwitcher.resetForTest()

        val snapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )

        try {
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, snapshot)
            )

            assertNotNull(
                "ServerAutoSwitcher timeout timer must start when the live push channel is stale",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_keepsAutoSwitchSuppressedWhenLivePushChannelIsFresh() {
        // Regression risk area 1 / AC2: with a fresh live push channel (lastLiveStatusMs
        // within aidlFreshWindowMs), the poll fallback must keep passing
        // allowAutoSwitch=false, exactly as before this fix, to avoid duplicate/competing
        // switch triggers alongside the live AIDL push path.
        //
        // Code review F4: applyStatusSnapshot() recomputes "now" internally, so capturing
        // System.currentTimeMillis() in the test and asserting on a value derived from a
        // later real-clock read was flake-prone (a >3s pause between setup and the internal
        // recomputation would flip the result). watchdogNowMs is overridden with a fixed
        // value so this test no longer depends on wall-clock timing at all.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        val elapsedRealtimeValueMs = 500_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))
        // Round 17 fix (Codex P2, comment 3736234632): isAidlFresh() now measures freshness
        // purely via elapsedRealtimeMs()/lastLiveStatusElapsedRealtimeMs, not wall clock, so this
        // fresh-live-push precondition must set the monotonic pairing too, not just
        // lastLiveStatusMs, or isAidlFresh() incorrectly reads as stale.
        ReflectionHelpers.setField(service, "elapsedRealtimeMs", ({ elapsedRealtimeValueMs } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now)
        ReflectionHelpers.setField(service, "lastLiveStatusElapsedRealtimeMs", elapsedRealtimeValueMs)
        ServerAutoSwitcher.resetForTest()

        val snapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )

        try {
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, snapshot)
            )

            assertNull(
                "ServerAutoSwitcher timer must stay inactive when the live push channel is fresh",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_treatsExactlyAtFreshWindowThresholdAsFresh() {
        // Code review F4 boundary case: isAidlFresh() uses "<=" (now - lastLiveStatusMs) <=
        // aidlFreshWindowMs), so a live push exactly aidlFreshWindowMs old is still "fresh" and
        // must keep the poll fallback's allowAutoSwitch=false, same as the fresh-path test
        // above. Uses the injectable watchdogNowMs clock for a deterministic boundary value
        // instead of relying on wall-clock timing.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        val elapsedRealtimeValueMs = 500_000L
        val aidlFreshWindowMs = ReflectionHelpers.getField<Long>(service, "aidlFreshWindowMs")
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))
        // Round 17 fix (Codex P2, comment 3736234632): isAidlFresh() now measures freshness
        // purely via elapsedRealtimeMs()/lastLiveStatusElapsedRealtimeMs, not wall clock, so the
        // boundary must be expressed on the monotonic clock to keep exercising the "<=" boundary
        // this test targets.
        ReflectionHelpers.setField(service, "elapsedRealtimeMs", ({ elapsedRealtimeValueMs } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - aidlFreshWindowMs)
        ReflectionHelpers.setField(service, "lastLiveStatusElapsedRealtimeMs", elapsedRealtimeValueMs - aidlFreshWindowMs)
        ServerAutoSwitcher.resetForTest()

        val snapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )

        try {
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, snapshot)
            )

            assertNull(
                "A live push exactly aidlFreshWindowMs old must still count as fresh " +
                    "(isAidlFresh() uses <=), keeping auto-switch suppressed",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_treatsNeverPushedLiveStatusAsStale() {
        // Code review F4: covers the lastLiveStatusMs == 0L (never received a live AIDL push
        // callback) branch. isAidlFresh() requires lastLiveStatusMs > 0L, so this must be
        // treated as stale and wake the auto-switcher, same as an old-but-nonzero timestamp.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", 0L)
        ServerAutoSwitcher.resetForTest()

        val snapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )

        try {
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, snapshot)
            )

            assertNotNull(
                "A live push channel that has never reported (lastLiveStatusMs=0L) must be " +
                    "treated as stale, waking the auto-switcher",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_stalePushAllowsImmediateSwitchOnAuthFailed() {
        // Code review F2 (non-blocking): allowAutoSwitch=true from the stale-push path applies
        // to ALL levels reaching syncEngineState, not just the CONNECTING family — e.g. for
        // LEVEL_AUTH_FAILED (and LEVEL_NONETWORK) the poll path can now trigger an immediate
        // switch too, when a switch timer is already active. This is consistent with how the
        // live push path already treats these levels (see
        // ServerAutoSwitcherTest.authFailedStartsChainedSwitchImmediately) and is an
        // intentional, tested consequence of the fix — not scope creep (noted on ClickUp task
        // 86cb21563).
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = System.currentTimeMillis()

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // Live push channel stalled well beyond aidlFreshWindowMs (3_000L).
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 10_000L)
        ServerAutoSwitcher.resetForTest()

        // Prime the auto-switch timeout timer via the stale-push snapshot path first, exactly
        // as applyStatusSnapshot_wakesAutoSwitcherWhenLivePushChannelIsStale verifies above.
        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the AUTH_FAILED snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            val authFailedSnapshot = StatusSnapshot(
                "AUTH_FAILED",
                null,
                0,
                ConnectionStatus.LEVEL_AUTH_FAILED,
                now,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, authFailedSnapshot)
            )

            assertNull(
                "ServerAutoSwitcher must be invoked for LEVEL_AUTH_FAILED reached via the " +
                    "stale-push snapshot path and trigger an immediate switch, canceling the " +
                    "active timeout timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_staleNoNetworkSnapshotDoesNotTriggerImmediateSwitch() {
        // Regression for round-8 bot review (Codex P2): LEVEL_NONETWORK was missing from
        // staleSnapshotTimeoutLevels, so an OLD/STALE cached snapshot carrying LEVEL_NONETWORK
        // (e.g. a past device-offline reading the status service never refreshed) skipped the
        // existing age-check entirely and flowed straight through to
        // syncEngineState(..., allowAutoSwitch = !isAidlFresh()). Since a stalled live push
        // channel is exactly what makes isAidlFresh() false, the stale reading got trusted
        // enough to fire ServerAutoSwitcher's shouldSwitchImmediately fast path (level ==
        // LEVEL_AUTH_FAILED || (source == "AIDL" && level == LEVEL_NONETWORK)) and cancel/switch
        // a currently-fresh, unrelated CONNECTING attempt. Fixed by adding LEVEL_NONETWORK to
        // staleSnapshotTimeoutLevels so it goes through the same staleSnapshotMaxAgeMs age-check
        // that LEVEL_AUTH_FAILED already used. Against the pre-fix code this test fails: the
        // stale NONETWORK snapshot reaches syncEngineState and cancels the active timer below.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // Live push channel stalled well beyond aidlFreshWindowMs (3_000L) -- this is exactly
        // the condition (isAidlFresh()=false) that makes the stale cached level get trusted.
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 10_000L)
        ServerAutoSwitcher.resetForTest()

        // Prime the auto-switch timeout timer via a FRESH CONNECTING snapshot first, simulating
        // a currently-fresh, in-progress, unrelated connection attempt (same setup as the
        // AUTH_FAILED test above).
        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the stale NONETWORK snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            // OLD/STALE cached NONETWORK snapshot: its own payload timestamp is far older than
            // staleSnapshotMaxAgeMs (10_000L), representing a past device-offline reading the
            // status service never refreshed, arriving while push is stalled.
            val staleNoNetworkSnapshot = StatusSnapshot(
                "NONETWORK",
                null,
                0,
                ConnectionStatus.LEVEL_NONETWORK,
                now - 15_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, staleNoNetworkSnapshot)
            )

            assertNotNull(
                "A STALE cached LEVEL_NONETWORK snapshot must be rejected by the age-check and " +
                    "must NOT cancel/trigger an immediate switch on the currently-fresh " +
                    "CONNECTING attempt's active timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_freshNoNetworkSnapshotStillTriggersImmediateSwitch() {
        // Companion to the stale-rejection test above: a GENUINELY FRESH LEVEL_NONETWORK
        // snapshot (recent timestamp, real current network loss) must still correctly drive an
        // immediate switch through the stale-push poll path -- the staleSnapshotTimeoutLevels
        // age-check added above must only reject OLD stale data, not legitimate current
        // NONETWORK readings.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 10_000L)
        ServerAutoSwitcher.resetForTest()

        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the fresh NONETWORK snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            val freshNoNetworkSnapshot = StatusSnapshot(
                "NONETWORK",
                null,
                0,
                ConnectionStatus.LEVEL_NONETWORK,
                now,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, freshNoNetworkSnapshot)
            )

            assertNull(
                "A genuinely FRESH LEVEL_NONETWORK snapshot must still trigger " +
                    "ServerAutoSwitcher's immediate switch, canceling the active timeout timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_oldSnapshotFromCurrentAttemptStillDrivesAutoSwitch() {
        // Regression for round-9 bot review (Codex P2, comment 3733934640): the round-8 fix
        // (LEVEL_NONETWORK added to staleSnapshotTimeoutLevels) age-checks every old snapshot in
        // staleSnapshotTimeoutLevels uniformly, but a snapshot can be old in absolute terms while
        // still being the ONLY status data available for the CURRENT, still-ongoing connection
        // attempt -- e.g. the status service rebinds while push callbacks are stalled and the
        // engine has genuinely been stuck connecting the whole time. Rejecting that snapshot
        // starves ServerAutoSwitcher of its only signal and resurrects the original indefinite
        // "stuck on Connecting..." bug through this rebind/poll path. Fixed by comparing the
        // snapshot's own timestamp against currentAttemptStartMs (recorded on every
        // ACTION_START): a snapshot at/after the current attempt's start is trusted regardless
        // of its absolute age; only a snapshot that PREDATES the current attempt (round 8's
        // scenario) is still rejected.
        //
        // Against pre-fix code (round 8, commit c4e1d46) this test fails: the old CONNECTING
        // snapshot never reaches syncEngineState because ageMs > staleSnapshotMaxAgeMs
        // unconditionally rejected it, so ServerAutoSwitcher's timer never starts.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // Live push channel stalled well beyond aidlFreshWindowMs/liveStatusGraceMs -- exactly
        // the "push callbacks are stalled" condition from the bot comment.
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        // The current connection attempt began 12s ago -- BEFORE the snapshot's own timestamp
        // below, so the snapshot IS reporting on this still-ongoing attempt, not a past one.
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 12_000L)
        ServerAutoSwitcher.resetForTest()

        // The snapshot itself is 11s old (> staleSnapshotMaxAgeMs=10_000L) -- old enough that
        // round 8's age-check alone would reject it -- but its timestamp (now - 11_000L) is
        // AFTER currentAttemptStartMs (now - 12_000L), so it belongs to the current attempt and
        // must be trusted despite its age.
        val staleButCurrentSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now - 11_000L,
            0L
        )

        try {
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, staleButCurrentSnapshot)
            )

            assertNotNull(
                "An old snapshot that is reporting on the CURRENT, still-ongoing connection " +
                    "attempt (no newer attempt has started since its timestamp) must still " +
                    "drive ServerAutoSwitcher's timeout timer, even though it is old in " +
                    "absolute terms",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_snapshotPredatingNewerAttemptStillRejectedAsStale() {
        // Companion to the test above: locks in round 8's original fix under the new
        // attempt-aware comparison, exercised explicitly through currentAttemptStartMs rather
        // than relying on its 0L (unknown) fallback. A cached snapshot whose timestamp predates
        // the CURRENT attempt's start -- i.e. a NEWER connection attempt has begun since this
        // snapshot was captured, the exact round-8 scenario (a leftover NONETWORK reading from a
        // past attempt) -- must still be rejected by the age-check and must not cancel/trigger
        // an immediate switch on the new, unrelated attempt's active timer.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 10_000L)
        // A NEW connection attempt started 2s ago -- AFTER the stale snapshot below was
        // captured (now - 15_000L).
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 2_000L)
        ServerAutoSwitcher.resetForTest()

        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the stale NONETWORK " +
                "snapshot from a past attempt",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            val staleNoNetworkSnapshot = StatusSnapshot(
                "NONETWORK",
                null,
                0,
                ConnectionStatus.LEVEL_NONETWORK,
                now - 15_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, staleNoNetworkSnapshot)
            )

            assertNotNull(
                "A snapshot predating the current (newer) attempt must still be rejected as " +
                    "stale and must NOT cancel/trigger an immediate switch on the current " +
                    "attempt's active timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_youngSnapshotPredatingNewerAttemptStillRejectedAsStale() {
        // Regression for round-10 bot review (Codex P2, comment 3734081106): round 9's fix
        // (applyStatusSnapshot_snapshotPredatingNewerAttemptStillRejectedAsStale above) only
        // exercised a snapshot whose absolute age (15s) already exceeded staleSnapshotMaxAgeMs
        // (10s) -- i.e. the OLD age-only gate would have rejected it anyway, so that test could
        // not catch a compositional bug in how the two conditions were combined. Round 9's actual
        // code was `if (ageMs > staleSnapshotMaxAgeMs && predatesCurrentAttempt)`, which only
        // ever CONSULTS the predates-check once the absolute-age gate has already fired. This
        // test reproduces the scenario where that composition fails: an old NONETWORK snapshot
        // triggers a switch, a NEW connection attempt starts a few seconds later
        // (currentAttemptStartMs advances), and shortly after that the status service's routine
        // poll RE-DELIVERS THE SAME OLD snapshot. Its absolute age (8s) is still under
        // staleSnapshotMaxAgeMs (10s) -- so the pre-fix outer age gate never fires and the
        // predates-check is never reached -- yet its timestamp genuinely predates the new
        // attempt's start (5s ago), so it is stale data from a replaced attempt and must still be
        // rejected. Against pre-fix (round 9) code this test fails: the young-but-predating
        // snapshot is trusted and cancels/re-triggers the current attempt's active timer.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // Live push channel stalled well beyond liveStatusGraceMs (5_000L) so a wrongly-trusted
        // snapshot would reach syncEngineState's allowAutoSwitch=true path, exactly like the
        // round-9 companion test above.
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        // The NEW connection attempt started 5s ago -- AFTER the old snapshot below was
        // originally captured (now - 8_000L), so the snapshot predates this newer attempt.
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 5_000L)
        ServerAutoSwitcher.resetForTest()

        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the young-but-predating " +
                "NONETWORK snapshot from a past attempt",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            // Its absolute age is only 8s (now - (now - 8_000L)) -- UNDER staleSnapshotMaxAgeMs
            // (10s) -- but its timestamp (now - 8_000L) is still BEFORE currentAttemptStartMs
            // (now - 5_000L), i.e. it genuinely predates the current attempt despite being
            // "young" in absolute terms.
            val youngButPredatingSnapshot = StatusSnapshot(
                "NONETWORK",
                null,
                0,
                ConnectionStatus.LEVEL_NONETWORK,
                now - 8_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, youngButPredatingSnapshot)
            )

            assertNotNull(
                "A snapshot that predates the current (newer) attempt must be rejected as " +
                    "stale EVEN WHEN its absolute age is under staleSnapshotMaxAgeMs -- the " +
                    "predates-check must not depend on the age gate having already fired -- " +
                    "and must NOT cancel/trigger an immediate switch on the current attempt's " +
                    "active timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_staleNotConnectedSnapshotDoesNotCancelActiveSwitchTimer() {
        // Regression for round-11 bot review (Codex P2, comment 3734228641): LEVEL_NOTCONNECTED
        // was the third level (after LEVEL_AUTH_FAILED in round 8/9/10's original fix and
        // LEVEL_NONETWORK) missing from staleSnapshotTimeoutLevels. An OLD/STALE cached
        // NOTCONNECTED snapshot -- one predating the CURRENT connection attempt -- skipped the
        // predates/age check entirely and flowed straight through to
        // syncEngineState(..., allowAutoSwitch = !isAidlFresh()). Since a stalled live push
        // channel is exactly what makes isAidlFresh() false, the stale reading got trusted enough
        // to reach ServerAutoSwitcher.onEngineLevel, which (when not waitingStopForRetry) treats
        // any level outside its timeoutLevels set -- NOTCONNECTED included -- as "engine is now
        // idle" and cancels the active switch timer via cancel(resetCycle=...), silently
        // abandoning a currently-fresh, unrelated CONNECTING attempt. Fixed by adding
        // LEVEL_NOTCONNECTED to staleSnapshotTimeoutLevels so it goes through the same
        // predates-current-attempt check already used for LEVEL_AUTH_FAILED/LEVEL_NONETWORK.
        // Against the pre-fix code this test fails: the stale NOTCONNECTED snapshot reaches
        // syncEngineState and cancels the active timer below.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // Live push channel stalled well beyond aidlFreshWindowMs/liveStatusGraceMs -- exactly
        // the condition (isAidlFresh()=false) that makes the stale cached level get trusted.
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        // The current connection attempt started 2s ago -- AFTER the stale snapshot below was
        // captured (now - 15_000L), so the snapshot predates this attempt.
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 2_000L)
        ServerAutoSwitcher.resetForTest()

        // Prime the auto-switch timeout timer via a FRESH CONNECTING snapshot first, simulating
        // a currently-fresh, in-progress, unrelated connection attempt.
        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the stale NOTCONNECTED " +
                "snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            // OLD/STALE cached NOTCONNECTED snapshot: its timestamp (now - 15_000L) predates
            // currentAttemptStartMs (now - 2_000L), representing a leftover reading from a past,
            // already-replaced connection attempt, arriving while push is stalled.
            val staleNotConnectedSnapshot = StatusSnapshot(
                "NOPROCESS",
                null,
                0,
                ConnectionStatus.LEVEL_NOTCONNECTED,
                now - 15_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, staleNotConnectedSnapshot)
            )

            assertNotNull(
                "A STALE cached LEVEL_NOTCONNECTED snapshot predating the current attempt must " +
                    "be rejected and must NOT cancel the currently-fresh CONNECTING attempt's " +
                    "active switch timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_notConnectedSnapshotFromCurrentAttemptStillCancelsSwitchTimer() {
        // Companion to the stale-rejection test above: a NOTCONNECTED snapshot that genuinely
        // belongs to the CURRENT connection attempt (old in absolute terms, but at/after
        // currentAttemptStartMs) must still be trusted and forwarded to ServerAutoSwitcher, which
        // correctly treats it as the engine going idle and cancels the active switch timer. The
        // round-11 fix (adding LEVEL_NOTCONNECTED to staleSnapshotTimeoutLevels) must only reject
        // snapshots that PREDATE the current attempt, not legitimate current-attempt readings --
        // otherwise a real idle transition would be starved just like round 9's original bug.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        // The current connection attempt began 12s ago -- BEFORE the snapshot's own timestamp
        // below, so the snapshot IS reporting on this still-ongoing attempt, not a past one.
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 12_000L)
        ServerAutoSwitcher.resetForTest()

        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the current-attempt " +
                "NOTCONNECTED snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            // The snapshot itself is 11s old (> staleSnapshotMaxAgeMs=10_000L) -- old enough that
            // an age-only check would reject it -- but its timestamp (now - 11_000L) is AFTER
            // currentAttemptStartMs (now - 12_000L), so it belongs to the current attempt and
            // must be trusted despite its age.
            val currentAttemptNotConnectedSnapshot = StatusSnapshot(
                "NOPROCESS",
                null,
                0,
                ConnectionStatus.LEVEL_NOTCONNECTED,
                now - 11_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, currentAttemptNotConnectedSnapshot)
            )

            assertNull(
                "A LEVEL_NOTCONNECTED snapshot genuinely reporting on the CURRENT, still-ongoing " +
                    "connection attempt must still reach ServerAutoSwitcher and cancel the " +
                    "active switch timer, even though it is old in absolute terms",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_staleConnectedSnapshotDoesNotCancelActiveSwitchTimer() {
        // Regression for round-12 bot review (Codex P2, comment 3734663954): LEVEL_CONNECTED
        // was the fourth level (after LEVEL_AUTH_FAILED, LEVEL_NONETWORK, LEVEL_NOTCONNECTED in
        // rounds 8-11) missing from staleSnapshotTimeoutLevels. An OLD/STALE cached CONNECTED
        // snapshot -- one predating the CURRENT connection attempt -- skipped the predates/age
        // check entirely and flowed straight through to
        // syncEngineState(..., allowAutoSwitch = !isAidlFresh()). Since a stalled live push
        // channel is exactly what makes isAidlFresh() false, the stale reading got trusted
        // enough to reach ServerAutoSwitcher.onEngineLevel, whose else branch (any level outside
        // its timeoutLevels set, LEVEL_CONNECTED included) treats it as "the current attempt
        // just succeeded" and cancels the active switch timer via cancel(resetCycle=...),
        // silently abandoning a currently-fresh, unrelated CONNECTING attempt that may actually
        // still be stuck. Fixed by adding LEVEL_CONNECTED to staleSnapshotTimeoutLevels so it
        // goes through the same predates-current-attempt check already used for
        // LEVEL_AUTH_FAILED/LEVEL_NONETWORK/LEVEL_NOTCONNECTED. Against the pre-fix code this
        // test fails: the stale CONNECTED snapshot reaches syncEngineState and cancels the
        // active timer below.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // Live push channel stalled well beyond aidlFreshWindowMs/liveStatusGraceMs -- exactly
        // the condition (isAidlFresh()=false) that makes the stale cached level get trusted.
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        // The current connection attempt started 2s ago -- AFTER the stale snapshot below was
        // captured (now - 15_000L), so the snapshot predates this attempt.
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 2_000L)
        ServerAutoSwitcher.resetForTest()

        // Prime the auto-switch timeout timer via a FRESH CONNECTING snapshot first, simulating
        // a currently-fresh, in-progress, unrelated connection attempt.
        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the stale CONNECTED " +
                "snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            // OLD/STALE cached CONNECTED snapshot: its timestamp (now - 15_000L) predates
            // currentAttemptStartMs (now - 2_000L), representing a leftover reading from a past,
            // already-replaced connection attempt, arriving while push is stalled.
            val staleConnectedSnapshot = StatusSnapshot(
                "CONNECTED",
                null,
                0,
                ConnectionStatus.LEVEL_CONNECTED,
                now - 15_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, staleConnectedSnapshot)
            )

            assertNotNull(
                "A STALE cached LEVEL_CONNECTED snapshot predating the current attempt must be " +
                    "rejected and must NOT cancel the currently-fresh CONNECTING attempt's " +
                    "active switch timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_connectedSnapshotFromCurrentAttemptStillCancelsSwitchTimer() {
        // Companion to the stale-rejection test above: a CONNECTED snapshot that genuinely
        // belongs to the CURRENT connection attempt (old in absolute terms, but at/after
        // currentAttemptStartMs) must still be trusted and forwarded to ServerAutoSwitcher,
        // which correctly treats it as the attempt having succeeded and cancels the active
        // switch timer. The round-12 fix (adding LEVEL_CONNECTED to staleSnapshotTimeoutLevels)
        // must only reject snapshots that PREDATE the current attempt, not legitimate
        // current-attempt readings -- otherwise a real successful connection would be starved
        // just like round 9's original bug.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        // The current connection attempt began 12s ago -- BEFORE the snapshot's own timestamp
        // below, so the snapshot IS reporting on this still-ongoing attempt, not a past one.
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 12_000L)
        ServerAutoSwitcher.resetForTest()

        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the current-attempt " +
                "CONNECTED snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            // The snapshot itself is 11s old (> staleSnapshotMaxAgeMs=10_000L) -- old enough
            // that an age-only check would reject it -- but its timestamp (now - 11_000L) is
            // AFTER currentAttemptStartMs (now - 12_000L), so it belongs to the current attempt
            // and must be trusted despite its age.
            val currentAttemptConnectedSnapshot = StatusSnapshot(
                "CONNECTED",
                null,
                0,
                ConnectionStatus.LEVEL_CONNECTED,
                now - 11_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, currentAttemptConnectedSnapshot)
            )

            assertNull(
                "A LEVEL_CONNECTED snapshot genuinely reporting on the CURRENT, still-ongoing " +
                    "connection attempt must still reach ServerAutoSwitcher and cancel the " +
                    "active switch timer, even though it is old in absolute terms",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_connectedDetailWithRawConnectingLevelDoesNotStartSwitchTimer() {
        // Regression for round-14 bot review (Codex P1, comment 3735319517): a snapshot's
        // `state` detail string can already report "CONNECTED" while its raw `level` enum is
        // STILL a transitional connecting-family value (the two fields can update on slightly
        // different cadences). ConnectionStateManager.updateFromEngine() normalizes this to
        // LEVEL_CONNECTED internally (see normalizeEngineLevel), but before this fix
        // syncEngineState() forwarded the RAW, un-normalized level to
        // dispatchAutoSwitcherOnEngineLevel() first -- so ServerAutoSwitcher observed a
        // connecting-family level for a snapshot that was, in fact, already connected, and
        // started an unnecessary switch timer on a healthy connection. Fixed by normalizing
        // once, up front, in syncEngineState() and forwarding the SAME normalized level to both
        // ServerAutoSwitcher and ConnectionStateManager. Against the pre-fix code this test
        // fails: the raw LEVEL_CONNECTING_SERVER_REPLIED value reaches ServerAutoSwitcher's
        // timeoutLevels branch and starts a timer, leaving remainingSeconds non-null.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // Live push channel stalled -- exactly the condition (isAidlFresh()=false) that routes
        // this snapshot through allowAutoSwitch=true.
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 10_000L)
        // The current attempt started before the snapshot's own timestamp, so it is not rejected
        // as stale/predating -- this test is purely about level/state normalization, not the
        // predates-current-attempt mechanism covered by the other tests in this file.
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 5_000L)
        ServerAutoSwitcher.resetForTest()

        try {
            // state says CONNECTED, but the raw level is still a connecting-family value -- the
            // exact transitional mismatch round 14 fixes.
            val mismatchedSnapshot = StatusSnapshot(
                "CONNECTED",
                null,
                0,
                ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
                now,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, mismatchedSnapshot)
            )

            assertNull(
                "A snapshot reporting state=CONNECTED must normalize to LEVEL_CONNECTED before " +
                    "reaching ServerAutoSwitcher -- it must NOT start a switch timer via the raw " +
                    "connecting-family level, since the connection is already effectively " +
                    "healthy",
                ServerAutoSwitcher.remainingSeconds.value
            )
            assertEquals(
                "ConnectionStateManager must observe the SAME normalized level ServerAutoSwitcher " +
                    "was given for the same snapshot",
                ConnectionStatus.LEVEL_CONNECTED,
                ConnectionStateManager.engineLevel.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_connectedDetailWithRawNoNetworkLevelDoesNotTriggerImmediateSwitch() {
        // Companion to the test above, using the more severe raw level: LEVEL_NONETWORK drives
        // ServerAutoSwitcher's shouldSwitchImmediately fast path (source == "AIDL" && level ==
        // LEVEL_NONETWORK). Before round 14's fix, a snapshot with state=="CONNECTED" but raw
        // level==LEVEL_NONETWORK reached ServerAutoSwitcher un-normalized and could trigger an
        // IMMEDIATE SWITCH AWAY from a connection the app itself already recognizes as healthy --
        // a false-disconnect bug, not just a wasted timer. `stopper` is the no-alternative-path
        // side effect ServerAutoSwitcher.requestSwitchNow() invokes when no next server is
        // configured (the default state in this test environment, exactly as in the existing
        // freshNoNetworkSnapshotStillTriggersImmediateSwitch test above) -- it fires ONLY when an
        // actual immediate-switch attempt was dispatched, not when the level is merely,
        // correctly, canceling the timer as LEVEL_CONNECTED. Against the pre-fix code this test
        // fails: stopCalls becomes 1 because the raw NONETWORK value fires the immediate-switch
        // path.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 10_000L)
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 5_000L)
        ServerAutoSwitcher.resetForTest()

        val originalStopper = ServerAutoSwitcher.stopper
        var stopCalls = 0
        ServerAutoSwitcher.stopper = { _ -> stopCalls += 1 }

        // Prime the auto-switch timeout timer via a FRESH CONNECTING snapshot first: without an
        // active timer (or state==CONNECTING), ServerAutoSwitcher.onEngineLevel's
        // shouldSwitchImmediately fast path short-circuits on `if (timerActive || isConnecting)`
        // and never calls requestSwitchNow() regardless of the level it was given -- so this
        // precondition is required to exercise the immediate-switch path the fix guards against.
        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now - 4_000L,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the CONNECTED/NONETWORK " +
                "mismatched snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            val mismatchedSnapshot = StatusSnapshot(
                "CONNECTED",
                null,
                0,
                ConnectionStatus.LEVEL_NONETWORK,
                now,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, mismatchedSnapshot)
            )

            assertEquals(
                "A snapshot reporting state=CONNECTED must normalize to LEVEL_CONNECTED before " +
                    "reaching ServerAutoSwitcher -- it must NOT trigger an immediate switch via " +
                    "the raw LEVEL_NONETWORK value",
                0,
                stopCalls
            )
        } finally {
            ServerAutoSwitcher.stopper = originalStopper
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_staleStartSnapshotDoesNotCancelActiveSwitchTimer() {
        // Regression for round-14 bot review (Codex P2, comment 3735319526): LEVEL_START is one
        // of three levels (alongside LEVEL_WAITING_FOR_USER_INPUT and LEVEL_VPNPAUSED below)
        // Codex found missing from the old staleSnapshotTimeoutLevels allowlist, following the
        // exact same pattern as LEVEL_NOTCONNECTED (round 11) and LEVEL_CONNECTED (round 12): it
        // is not in ServerAutoSwitcher's own `timeoutLevels` set, not UNKNOWN_LEVEL, and not the
        // AUTH_FAILED/AIDL+NONETWORK immediate-switch case, so ServerAutoSwitcher.onEngineLevel's
        // else branch treats it as "engine is now idle" and calls cancel(resetCycle=...) on the
        // active switch timer. Round 14 fixed this by removing the allowlist entirely (see
        // staleSnapshotMaxAgeMs's declaration comment) so every level, LEVEL_START included, goes
        // through the same predates-current-attempt check. Against the pre-fix code this test
        // fails: the stale LEVEL_START snapshot reaches syncEngineState and cancels the active
        // timer below.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 2_000L)
        ServerAutoSwitcher.resetForTest()

        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the stale LEVEL_START " +
                "snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            val staleStartSnapshot = StatusSnapshot(
                "WAIT",
                null,
                0,
                ConnectionStatus.LEVEL_START,
                now - 15_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, staleStartSnapshot)
            )

            assertNotNull(
                "A STALE cached LEVEL_START snapshot predating the current attempt must be " +
                    "rejected and must NOT cancel the currently-fresh CONNECTING attempt's " +
                    "active switch timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_staleWaitingForUserInputSnapshotDoesNotCancelActiveSwitchTimer() {
        // See applyStatusSnapshot_staleStartSnapshotDoesNotCancelActiveSwitchTimer above --
        // LEVEL_WAITING_FOR_USER_INPUT is the second of the three levels named in round-14 bot
        // review (Codex P2, comment 3735319526). Against the pre-fix code this test fails: the
        // stale snapshot reaches syncEngineState and cancels the active timer below.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 2_000L)
        ServerAutoSwitcher.resetForTest()

        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the stale " +
                "LEVEL_WAITING_FOR_USER_INPUT snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            val staleWaitingSnapshot = StatusSnapshot(
                "WAITING_FOR_USER_INPUT",
                null,
                0,
                ConnectionStatus.LEVEL_WAITING_FOR_USER_INPUT,
                now - 15_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, staleWaitingSnapshot)
            )

            assertNotNull(
                "A STALE cached LEVEL_WAITING_FOR_USER_INPUT snapshot predating the current " +
                    "attempt must be rejected and must NOT cancel the currently-fresh " +
                    "CONNECTING attempt's active switch timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_staleVpnPausedSnapshotDoesNotCancelActiveSwitchTimer() {
        // See applyStatusSnapshot_staleStartSnapshotDoesNotCancelActiveSwitchTimer above --
        // LEVEL_VPNPAUSED is the third of the three levels named in round-14 bot review (Codex
        // P2, comment 3735319526). Against the pre-fix code this test fails: the stale snapshot
        // reaches syncEngineState and cancels the active timer below.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 2_000L)
        ServerAutoSwitcher.resetForTest()

        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the stale LEVEL_VPNPAUSED " +
                "snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        try {
            val staleVpnPausedSnapshot = StatusSnapshot(
                "PAUSED",
                null,
                0,
                ConnectionStatus.LEVEL_VPNPAUSED,
                now - 15_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, staleVpnPausedSnapshot)
            )

            assertNotNull(
                "A STALE cached LEVEL_VPNPAUSED snapshot predating the current attempt must be " +
                    "rejected and must NOT cancel the currently-fresh CONNECTING attempt's " +
                    "active switch timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_staleGateAppliesUnconditionallyRatherThanViaPerLevelAllowlist() {
        // Proves round 14's generalization (Codex P2, comment 3735319526) is genuine rather than
        // yet another enumerated allowlist entry. Two independent checks:
        //
        // 1. Structural: the old staleSnapshotTimeoutLevels field is gone entirely -- the gate in
        //    applyStatusSnapshot() no longer branches on `level in <some set>` at all, so there is
        //    no allowlist left to fall out of sync with ConnectionStatus's members.
        // 2. Behavioral: the predates-current-attempt gate itself (independent of
        //    ServerAutoSwitcher's per-level routing, which is exercised by the dedicated
        //    LEVEL_START/LEVEL_WAITING_FOR_USER_INPUT/LEVEL_VPNPAUSED tests above) rejects a
        //    stale/predating snapshot carrying UNKNOWN_LEVEL -- a level Codex's round-14 comment
        //    did not name -- logging the same "Skipping stale snapshot" message every other level
        //    produces, proving the gate is level-agnostic.
        val hasAllowlistField = try {
            ReflectionHelpers.getField<Any>(
                Robolectric.buildService(OpenVpnService::class.java).create().get(),
                "staleSnapshotTimeoutLevels"
            )
            true
        } catch (_: Throwable) {
            false
        }
        assertFalse(
            "staleSnapshotTimeoutLevels must no longer exist as a per-level allowlist field -- " +
                "round 14 replaced it with an unconditional check",
            hasAllowlistField
        )

        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        // The current attempt started AFTER the snapshot below was captured, so it predates the
        // current attempt and must be rejected regardless of which level it carries.
        ReflectionHelpers.setField(service, "currentAttemptStartMs", now - 2_000L)

        ShadowLog.clear()
        val predatingUnknownLevelSnapshot = StatusSnapshot(
            null,
            null,
            0,
            ConnectionStatus.UNKNOWN_LEVEL,
            now - 15_000L,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, predatingUnknownLevelSnapshot)
        )

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(
            "A snapshot predating the current attempt must be rejected as stale regardless of " +
                "its level -- including UNKNOWN_LEVEL, which round 14's comment did not name -- " +
                "proving the gate is genuinely unconditional rather than an enumerated allowlist",
            logs.any { it.contains("Skipping stale snapshot") }
        )
    }

    @Test
    fun applyStatusSnapshot_unknownAttemptStartTrustsFirstActiveSnapshotDespiteAge() {
        // Regression for round-15 bot review (Codex P2, comment 3735628745):
        // MainActivityCore.onStart() reattaches to an already-running engine via
        // ACTION_SYNC_STATUS, not ACTION_START, so currentAttemptStartMs is never set for a
        // service instance recreated this way -- it stays at its default 0L (unknown) even
        // though a real, still-ongoing engine attempt genuinely exists. Before this fix, the
        // only available cached snapshot for a long-stuck attempt would inevitably exceed
        // staleSnapshotMaxAgeMs (10s) and be rejected forever, starving ServerAutoSwitcher and
        // resurrecting the original "stuck on Connecting..." bug via this lifecycle path.
        //
        // Against pre-fix (round 14) code this test fails: currentAttemptStartMs stays 0L, the
        // age-only fallback fires (ageMs=20_000 > staleSnapshotMaxAgeMs), the snapshot is
        // rejected, and ServerAutoSwitcher.remainingSeconds stays null.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // Live push channel stalled well beyond aidlFreshWindowMs/liveStatusGraceMs -- exactly
        // the stalled-push condition this whole PR is about.
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        // currentAttemptStartMs intentionally left at its default 0L (unknown) -- this service
        // instance never went through ACTION_START, simulating the ACTION_SYNC_STATUS-only
        // lifecycle path Codex's comment describes.
        ServerAutoSwitcher.resetForTest()

        // The only cached snapshot available, old enough (20s) that the pre-fix age-only
        // fallback (10s) would reject it on every single poll, forever.
        val oldConnectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now - 20_000L,
            0L
        )

        try {
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, oldConnectingSnapshot)
            )

            assertNotNull(
                "An old cached snapshot reporting a genuinely active engine level must still " +
                    "drive ServerAutoSwitcher's timeout timer when currentAttemptStartMs is " +
                    "unknown (ACTION_SYNC_STATUS lifecycle path), not be rejected forever by the " +
                    "age-only fallback",
                ServerAutoSwitcher.remainingSeconds.value
            )
            assertEquals(
                "currentAttemptStartMs must be backfilled to the snapshot's own timestamp -- " +
                    "the earliest evidence this instance has of the current attempt -- so later " +
                    "snapshots compare against a known baseline instead of staying unknown " +
                    "forever",
                now - 20_000L,
                ReflectionHelpers.getField<Long>(service, "currentAttemptStartMs")
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_unknownAttemptStartStillRejectsSnapshotPredatingBackfilledAttempt() {
        // Companion to the test above: once the first active-level snapshot backfills
        // currentAttemptStartMs, a SUBSEQUENT snapshot that predates that backfilled baseline --
        // i.e. genuinely older/leftover data, not a reading of the current attempt -- must still
        // be rejected exactly like the known-attempt path (rounds 8-12). This proves the fix
        // does not degrade into blanket-trusting every snapshot once unknown-start backfill
        // happens; it establishes real tracking, not an unconditional bypass.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - 20_000L)
        // currentAttemptStartMs starts unknown (0L), same ACTION_SYNC_STATUS-only lifecycle.
        ServerAutoSwitcher.resetForTest()

        // First snapshot observed: active level, backfills currentAttemptStartMs to (now - 3_000).
        val firstActiveSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            now - 3_000L,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, firstActiveSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active after the first backfilling " +
                "snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )
        assertEquals(
            now - 3_000L,
            ReflectionHelpers.getField<Long>(service, "currentAttemptStartMs")
        )

        try {
            // A second, genuinely older snapshot -- e.g. a leftover reading from a distinct past
            // session -- whose timestamp PREDATES the just-backfilled currentAttemptStartMs.
            val olderUnrelatedSnapshot = StatusSnapshot(
                "NONETWORK",
                null,
                0,
                ConnectionStatus.LEVEL_NONETWORK,
                now - 20_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, olderUnrelatedSnapshot)
            )

            assertNotNull(
                "A snapshot predating the backfilled currentAttemptStartMs must still be " +
                    "rejected as stale and must NOT cancel the active switch timer, exactly like " +
                    "the known-attempt predates-check from rounds 8-12",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_backwardClockJumpDoesNotStarveCurrentAttempt() {
        // Regression for round-16 bot review (Codex P2, comment 3735937824): currentAttemptStartMs
        // is recorded from a wall clock (watchdogNowMs() / System.currentTimeMillis()). If the
        // device's wall clock is corrected BACKWARD after ACTION_START -- e.g. automatic NTP
        // sync -- every later snapshot's wall-clock timestampMs reads earlier than
        // currentAttemptStartMs even though real (monotonic) time keeps moving forward normally,
        // so the plain predates-check (ts < currentAttemptStartMs) rejects every snapshot from the
        // CURRENT, still-ongoing attempt -- until wall-clock time naturally advances back past the
        // stale currentAttemptStartMs value. That resurrects the "stuck on Connecting..." bug this
        // whole PR fixes, via a clock-jump vector instead of a lifecycle-path/level-enumeration one.
        //
        // Against pre-round-16 code this test fails: currentAttemptStartElapsedRealtimeMs and the
        // backward-jump detection in applyStatusSnapshot do not exist, so the snapshot below --
        // genuinely reporting on the current attempt, delivered right after the jump -- is
        // rejected as predating the attempt and ServerAutoSwitcher's timer never starts.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        // Simulate ACTION_START: wall clock at T0, elapsed-realtime (monotonic) at E0, exactly
        // like the paired currentAttemptStartMs/currentAttemptStartElapsedRealtimeMs writes at
        // ACTION_START.
        var wallClockMs = 1_700_000_000_000L
        var elapsedRealtimeValueMs = 500_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ wallClockMs } as () -> Long))
        ReflectionHelpers.setField(service, "elapsedRealtimeMs", ({ elapsedRealtimeValueMs } as () -> Long))
        ReflectionHelpers.setField(service, "currentAttemptStartMs", wallClockMs)
        ReflectionHelpers.setField(service, "currentAttemptStartElapsedRealtimeMs", elapsedRealtimeValueMs)
        ReflectionHelpers.setField(service, "boundToStatus", true)
        // lastLiveStatusMs deliberately left at its default 0L (no live push has arrived) instead
        // of an "old timestamp" value like other tests use: with the wall clock about to jump
        // backward, an old-wall-clock-timestamp value for lastLiveStatusMs would itself become
        // arithmetically ambiguous against the post-jump `now` read inside isAidlFresh(). Leaving
        // it at 0L is an unambiguous, valid stalled/no-live-push precondition (isAidlFresh()
        // requires lastLiveStatusMs > 0L) that keeps this test focused on the predates-check fix.
        ServerAutoSwitcher.resetForTest()

        // Real (monotonic) time advances normally by 3s, but the wall clock is corrected
        // BACKWARD by 30s at the same moment -- e.g. an NTP sync landing shortly after
        // ACTION_START. Net wall-clock movement: +3_000 (real) - 30_000 (jump) = -27_000.
        elapsedRealtimeValueMs += 3_000L
        wallClockMs -= 27_000L

        // The engine's next status snapshot is timestamped under the corrected (lower) wall
        // clock, so its timestampMs now reads BEFORE currentAttemptStartMs even though it
        // genuinely reports on the still-ongoing current attempt.
        val currentAttemptSnapshotAfterJump = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            wallClockMs,
            0L
        )

        try {
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, currentAttemptSnapshotAfterJump)
            )

            assertNotNull(
                "A snapshot genuinely reporting on the CURRENT, still-ongoing attempt, delivered " +
                    "after a backward wall-clock correction, must still drive ServerAutoSwitcher's " +
                    "timeout timer -- it must not be rejected purely because its wall-clock " +
                    "timestamp now reads earlier than currentAttemptStartMs",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_clockJumpSafetyNetDoesNotMaskGenuinelyStaleSnapshot() {
        // Companion to the test above: proves the backward-clock-jump safety net does not
        // degrade into an unconditional bypass once currentAttemptStartElapsedRealtimeMs is
        // tracked. A small, genuine clock correction is detected (as in the test above), but a
        // SEPARATE, genuinely old/unrelated snapshot -- whose predates-gap is far larger than the
        // detected jump size explains -- must still be rejected exactly like the known-attempt
        // path from rounds 8-14. This exercises that rejection with the new field actively
        // tracked (non-zero), not merely relying on it being unset like the pre-existing
        // rounds 8-14 tests.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        var wallClockMs = 1_700_000_000_000L
        var elapsedRealtimeValueMs = 500_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ wallClockMs } as () -> Long))
        ReflectionHelpers.setField(service, "elapsedRealtimeMs", ({ elapsedRealtimeValueMs } as () -> Long))
        ReflectionHelpers.setField(service, "currentAttemptStartMs", wallClockMs)
        ReflectionHelpers.setField(service, "currentAttemptStartElapsedRealtimeMs", elapsedRealtimeValueMs)
        ReflectionHelpers.setField(service, "boundToStatus", true)
        ServerAutoSwitcher.resetForTest()

        // Establish an active switch timer from a trusted, current-attempt snapshot (its
        // timestamp equals the attempt start, so it does not predate anything) -- same
        // "setup precondition" pattern used by the rounds 8-12 tests above.
        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            wallClockMs,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the genuinely stale " +
                "NONETWORK snapshot from a past, unrelated attempt",
            ServerAutoSwitcher.remainingSeconds.value
        )

        // A SMALL clock correction: real time advances 1s, wall clock jumps back 5s (net -4_000).
        // estimatedJumpMs works out to ~5_000.
        elapsedRealtimeValueMs += 1_000L
        wallClockMs -= 4_000L

        try {
            // Genuinely old/unrelated snapshot: its predates-gap against currentAttemptStartMs
            // (50_000ms) is far larger than the detected jump (~5_000ms) plus slack could ever
            // explain, so it must still be rejected as stale leftover data, not trusted.
            val genuinelyStaleSnapshot = StatusSnapshot(
                "NONETWORK",
                null,
                0,
                ConnectionStatus.LEVEL_NONETWORK,
                wallClockMs - 50_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, genuinelyStaleSnapshot)
            )

            assertNotNull(
                "A genuinely old/unrelated snapshot whose predates-gap is far larger than any " +
                    "detected clock-jump size must still be rejected as stale and must NOT " +
                    "cancel/trigger an immediate switch on the current attempt's active timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun isAidlFresh_usesMonotonicClockDespiteBackwardWallClockJump() {
        // Regression for round-17 bot review (Codex P2, comment 3736234632): isAidlFresh() used
        // to measure freshness purely with wall-clock time (now - lastLiveStatusMs via
        // watchdogNowMs()/System.currentTimeMillis()). If the device wall clock is corrected
        // BACKWARD after the last live AIDL push and that push channel then stalls, the wall-clock
        // delta goes negative/small, so isAidlFresh() keeps falsely reporting "fresh" until
        // wall-clock time naturally catches back up -- which can take minutes or hours. While
        // isAidlFresh() wrongly reports fresh, applyStatusSnapshot()'s
        // allowAutoSwitch = !isAidlFresh() stays false, silently reproducing this PR's "stuck on
        // Connecting..." bug via a clock-jump vector. Fixed by measuring freshness purely via the
        // monotonic elapsedRealtimeMs()/lastLiveStatusElapsedRealtimeMs pairing instead, mirroring
        // the currentAttemptStartMs/currentAttemptStartElapsedRealtimeMs pattern from round 16.
        //
        // Against pre-round-17 code this test fails: the backward wall-clock jump below makes
        // (now - lastLiveStatusMs) go very negative, which is <= aidlFreshWindowMs, so
        // isAidlFresh() wrongly returns true and the switch timer below never starts.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        // A live AIDL push lands: wall clock at T0, elapsed-realtime (monotonic) at E0.
        var wallClockMs = 1_700_000_000_000L
        var elapsedRealtimeValueMs = 500_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ wallClockMs } as () -> Long))
        ReflectionHelpers.setField(service, "elapsedRealtimeMs", ({ elapsedRealtimeValueMs } as () -> Long))
        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", wallClockMs)
        ReflectionHelpers.setField(service, "lastLiveStatusElapsedRealtimeMs", elapsedRealtimeValueMs)
        ServerAutoSwitcher.resetForTest()

        // Real (monotonic) time advances normally by 4s -- past aidlFreshWindowMs (3_000L) -- but
        // the wall clock is corrected BACKWARD by 55s at the same moment (e.g. an NTP sync while
        // the live push channel has stalled). Net wall-clock movement: +4_000 (real) - 55_000
        // (jump) = -51_000. Under the old wall-clock-only check, (now - lastLiveStatusMs) would
        // read -51_000, which is <= aidlFreshWindowMs, so isAidlFresh() would wrongly stay true.
        elapsedRealtimeValueMs += 4_000L
        wallClockMs -= 51_000L

        // The status-poll fallback observes a snapshot at the corrected (lower) wall-clock time,
        // genuinely reporting on the still-ongoing current attempt (currentAttemptStartMs is
        // unknown here, so this first active-level snapshot backfills it to its own timestamp --
        // see round 15's fix -- and therefore does not predate anything).
        val snapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            wallClockMs,
            0L
        )

        try {
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, snapshot)
            )

            assertNotNull(
                "isAidlFresh() must correctly report the live push channel as stale (via the " +
                    "monotonic elapsedRealtimeMs pairing), driving allowAutoSwitch=true and " +
                    "starting ServerAutoSwitcher's timer, despite a backward wall-clock jump " +
                    "that would fool a wall-clock-only freshness check into reporting fresh",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun applyStatusSnapshot_backwardClockJumpDoesNotWaiveGenuinelyStaleSmallGapSnapshot() {
        // Regression for round-17 bot review (Codex P2, comment 3736234637): round 16's
        // predatesExplainedByBackwardClockJump heuristic waives stale-snapshot rejection whenever
        // predatesGapMs <= estimatedJumpMs + clockJumpSlackMs, but that also wrongly waives
        // rejection for a GENUINELY stale, small-gap prior-attempt snapshot whenever ANY large
        // backward clock jump has occurred anywhere during the current attempt's lifetime, even
        // one causally unrelated to that particular stale snapshot. Example (Codex's own): a
        // cached LEVEL_CONNECTED from 2s before the current attempt started is genuinely stale
        // leftover data, but if an unrelated 30s backward clock correction has occurred since the
        // attempt began, its 2_000ms predates-gap trivially satisfies <= a 30_000ms estimated jump
        // plus slack, so it gets wrongly waived and applied to the new attempt -- here, falsely
        // cancelling ServerAutoSwitcher's active timer on an unrelated, still-stuck attempt.
        //
        // Fixed by also requiring the snapshot's own timestamp not be materially ahead of `now`
        // (ts <= now + clockJumpSlackMs): a genuine post-jump current-attempt snapshot is captured
        // on the same corrected (lower) wall-clock scale as `now`, so its ts can never be
        // materially greater than now; a pre-jump stale snapshot's ts (captured on the old, higher
        // clock) ends up materially AHEAD of a post-jump `now`, which is the discriminator this
        // guard uses to reject it.
        //
        // Against pre-round-17 code this test fails: the stale CONNECTED snapshot below is wrongly
        // waived and cancels the switch timer asserted below.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        var wallClockMs = 1_700_000_000_000L
        var elapsedRealtimeValueMs = 500_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ wallClockMs } as () -> Long))
        ReflectionHelpers.setField(service, "elapsedRealtimeMs", ({ elapsedRealtimeValueMs } as () -> Long))
        val attemptStartMs = wallClockMs
        val attemptStartElapsedRealtimeMs = elapsedRealtimeValueMs
        ReflectionHelpers.setField(service, "currentAttemptStartMs", attemptStartMs)
        ReflectionHelpers.setField(service, "currentAttemptStartElapsedRealtimeMs", attemptStartElapsedRealtimeMs)
        ReflectionHelpers.setField(service, "boundToStatus", true)
        // Live push channel stalled -- the condition (isAidlFresh()=false) that makes a
        // would-be-accepted snapshot get trusted enough to reach syncEngineState/
        // ServerAutoSwitcher. lastLiveStatusElapsedRealtimeMs is deliberately left at its default
        // 0L (isAidlFresh() requires it > 0L), an unambiguous stalled/no-live-push precondition.
        ReflectionHelpers.setField(service, "lastLiveStatusMs", attemptStartMs - 20_000L)
        ServerAutoSwitcher.resetForTest()

        // Prime the auto-switch timeout timer via a trusted, current-attempt CONNECTING snapshot
        // (its timestamp equals the attempt start, so it does not predate anything).
        val connectingSnapshot = StatusSnapshot(
            "TCP_CONNECT",
            null,
            0,
            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            attemptStartMs,
            0L
        )
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, connectingSnapshot)
        )
        assertNotNull(
            "Setup precondition: switch timer must be active before the genuinely stale " +
                "CONNECTED snapshot",
            ServerAutoSwitcher.remainingSeconds.value
        )

        // An unrelated, large (~30s) backward clock correction occurs: real (monotonic) time
        // advances by 1s, but the wall clock jumps back by 29s (net -29_000). estimatedJumpMs
        // works out to ~30_000, matching Codex's own example.
        elapsedRealtimeValueMs += 1_000L
        wallClockMs -= 29_000L

        try {
            // Genuinely stale, small-gap prior-attempt snapshot: its predates-gap against
            // currentAttemptStartMs is only 2_000ms -- unrelated leftover data captured on the
            // OLD (pre-jump) wall-clock scale, not evidence of the current attempt continuing
            // after the correction. Its ts (attemptStartMs - 2_000) is now materially AHEAD of the
            // post-jump `now` (wallClockMs), which is what the round-17 guard detects.
            val genuinelyStaleSmallGapSnapshot = StatusSnapshot(
                "CONNECTED",
                null,
                0,
                ConnectionStatus.LEVEL_CONNECTED,
                attemptStartMs - 2_000L,
                0L
            )
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "applyStatusSnapshot",
                ClassParameter.from(StatusSnapshot::class.java, genuinelyStaleSmallGapSnapshot)
            )

            assertNotNull(
                "A genuinely stale, small-gap prior-attempt snapshot must still be rejected even " +
                    "when an unrelated large backward clock jump has occurred during the current " +
                    "attempt -- it must not cancel the active switch timer on an unrelated, " +
                    "still-stuck attempt",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun userStopDispatchFailureRetriesAndMarksExplicitFailure() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        val binder = object : IOpenVPNServiceInternal.Stub() {
            override fun protect(fd: Int) = false
            override fun userPause(b: Boolean) {}
            override fun stopVPN(replaceConnection: Boolean) = false
            override fun addAllowedExternalApp(packagename: String?) {}
            override fun isAllowedExternalApp(packagename: String?) = false
            override fun challengeResponse(repsonse: String?) {}
        }
        ReflectionHelpers.setField(service, "engineBinder", binder)
        ReflectionHelpers.setField(service, "boundToEngine", true)

        val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
        }
        service.onStartCommand(stopIntent, 0, 1)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(ConnectionState.DISCONNECTING, ConnectionStateManager.state.value)
        assertEquals(ConnectionStateManager.VpnError.STOP_FAILED, ConnectionStateManager.error.value)
        assertEquals(3, ReflectionHelpers.getField<Int>(service, "stopAttempt"))
    }

    @Test
    fun stopBindTimeoutCountsTowardRetryLimitAndMarksFailure() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        ReflectionHelpers.setField(service, "userInitiatedStop", true)
        ReflectionHelpers.setField(service, "stopBindPending", true)
        ReflectionHelpers.setField(service, "stopAttempt", 2)
        ReflectionHelpers.setField(service, "stopLastFailureReason", null)
        ReflectionHelpers.setField(service, "stopRequestId", "bind1234")

        val timeoutRunnable = ReflectionHelpers.getField<Runnable>(service, "stopBindTimeoutRunnable")
        timeoutRunnable.run()

        assertEquals(ConnectionStateManager.VpnError.STOP_FAILED, ConnectionStateManager.error.value)
        assertEquals(3, ReflectionHelpers.getField<Int>(service, "stopAttempt"))
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "stopBindPending"))
    }

    @Test
    fun userStopAfterStopFailed_resetsAttemptCounterAndDispatchesAgain() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        val failingBinder = object : IOpenVPNServiceInternal.Stub() {
            override fun protect(fd: Int) = false
            override fun userPause(b: Boolean) {}
            override fun stopVPN(replaceConnection: Boolean) = false
            override fun addAllowedExternalApp(packagename: String?) {}
            override fun isAllowedExternalApp(packagename: String?) = false
            override fun challengeResponse(repsonse: String?) {}
        }
        ReflectionHelpers.setField(service, "engineBinder", failingBinder)
        ReflectionHelpers.setField(service, "boundToEngine", true)

        val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
        }
        service.onStartCommand(stopIntent, 0, 1)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(ConnectionStateManager.VpnError.STOP_FAILED, ConnectionStateManager.error.value)
        assertEquals(3, ReflectionHelpers.getField<Int>(service, "stopAttempt"))

        val succeedingBinder = object : IOpenVPNServiceInternal.Stub() {
            override fun protect(fd: Int) = false
            override fun userPause(b: Boolean) {}
            override fun stopVPN(replaceConnection: Boolean) = true
            override fun addAllowedExternalApp(packagename: String?) {}
            override fun isAllowedExternalApp(packagename: String?) = false
            override fun challengeResponse(repsonse: String?) {}
        }
        ReflectionHelpers.setField(service, "engineBinder", succeedingBinder)
        ReflectionHelpers.setField(service, "boundToEngine", true)

        service.onStartCommand(stopIntent, 0, 2)

        assertEquals(ConnectionStateManager.VpnError.NONE, ConnectionStateManager.error.value)
        assertEquals(1, ReflectionHelpers.getField<Int>(service, "stopAttempt"))
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "stopAwaitingConfirmation"))
    }

    @Test
    fun userStopRequiresEngineConfirmationBeforeDisconnected() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        val binder = object : IOpenVPNServiceInternal.Stub() {
            override fun protect(fd: Int) = false
            override fun userPause(b: Boolean) {}
            override fun stopVPN(replaceConnection: Boolean) = true
            override fun addAllowedExternalApp(packagename: String?) {}
            override fun isAllowedExternalApp(packagename: String?) = false
            override fun challengeResponse(repsonse: String?) {}
        }
        ReflectionHelpers.setField(service, "engineBinder", binder)
        ReflectionHelpers.setField(service, "boundToEngine", true)

        val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
        }
        service.onStartCommand(stopIntent, 0, 1)

        assertEquals(ConnectionState.DISCONNECTING, ConnectionStateManager.state.value)

        service.updateState("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, null)

        assertEquals(ConnectionState.DISCONNECTED, ConnectionStateManager.state.value)
        assertEquals(ConnectionStateManager.VpnError.NONE, ConnectionStateManager.error.value)
    }

    @Test
    fun stalePendingStopIntentReconcilesConnectedSnapshotWithoutShowingConnected() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        val binder = object : IOpenVPNServiceInternal.Stub() {
            override fun protect(fd: Int) = false
            override fun userPause(b: Boolean) {}
            override fun stopVPN(replaceConnection: Boolean) = true
            override fun addAllowedExternalApp(packagename: String?) {}
            override fun isAllowedExternalApp(packagename: String?) = false
            override fun challengeResponse(repsonse: String?) {}
        }
        ReflectionHelpers.setField(service, "engineBinder", binder)
        ReflectionHelpers.setField(service, "boundToEngine", true)

        appContext.getSharedPreferences("vpn_stop_teardown", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("pending_stop_intent", true)
            .apply()

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        callbacks.updateStateString("CONNECTED", null, 0, ConnectionStatus.LEVEL_CONNECTED, null)

        assertEquals(ConnectionState.DISCONNECTING, ConnectionStateManager.state.value)
        assertTrue(ReflectionHelpers.getField(service, "userInitiatedStop"))
    }

    @Test
    fun stalePendingStopIntentClearsOnIdleNotConnectedLevel() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        val prefs = appContext.getSharedPreferences("vpn_stop_teardown", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("pending_stop_intent", true)
            .putInt("stop_failure_count", 1)
            .apply()
        ConnectionStateManager.setStopFailure()

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        callbacks.updateStateString("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, null)

        assertFalse(prefs.getBoolean("pending_stop_intent", false))
        assertEquals(ConnectionStateManager.VpnError.NONE, ConnectionStateManager.error.value)

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(logs.any { it.contains("pending intent cleared on idle engine level") && it.contains("pending_stop_intent=false") })
    }

    @Test
    fun stopFromPausedUsesSameEngineConfirmedTeardown() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_VPNPAUSED, null)

        val binder = object : IOpenVPNServiceInternal.Stub() {
            override fun protect(fd: Int) = false
            override fun userPause(b: Boolean) {}
            override fun stopVPN(replaceConnection: Boolean) = true
            override fun addAllowedExternalApp(packagename: String?) {}
            override fun isAllowedExternalApp(packagename: String?) = false
            override fun challengeResponse(repsonse: String?) {}
        }
        ReflectionHelpers.setField(service, "engineBinder", binder)
        ReflectionHelpers.setField(service, "boundToEngine", true)

        val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
        }
        service.onStartCommand(stopIntent, 0, 1)

        assertEquals(ConnectionState.DISCONNECTING, ConnectionStateManager.state.value)

        service.updateState("DISCONNECTED", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, null)
        assertEquals(ConnectionState.DISCONNECTED, ConnectionStateManager.state.value)
    }

    @Test
    fun failedStopThenFreshStart_doesNotReusePendingStopIntentOnConnectedCallbacks() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        val failingStopBinder = object : IOpenVPNServiceInternal.Stub() {
            override fun protect(fd: Int) = false
            override fun userPause(b: Boolean) {}
            override fun stopVPN(replaceConnection: Boolean) = false
            override fun addAllowedExternalApp(packagename: String?) {}
            override fun isAllowedExternalApp(packagename: String?) = false
            override fun challengeResponse(repsonse: String?) {}
        }
        ReflectionHelpers.setField(service, "engineBinder", failingStopBinder)
        ReflectionHelpers.setField(service, "boundToEngine", true)

        val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
        }
        service.onStartCommand(stopIntent, 0, 1)
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        val prefs = appContext.getSharedPreferences("vpn_stop_teardown", android.content.Context.MODE_PRIVATE)
        assertTrue(prefs.getBoolean("pending_stop_intent", false))
        assertEquals(ConnectionStateManager.VpnError.STOP_FAILED, ConnectionStateManager.error.value)

        val startIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_START)
            putExtra(VpnManager.extraConfigKey(appContext), "client\n")
            putExtra(VpnManager.extraTitleKey(appContext), "RU")
        }
        service.onStartCommand(startIntent, 0, 2)

        val refreshedPrefs = appContext.getSharedPreferences("vpn_stop_teardown", android.content.Context.MODE_PRIVATE)
        assertFalse(refreshedPrefs.getBoolean("pending_stop_intent", false))

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        callbacks.updateStateString("CONNECTED", null, 0, ConnectionStatus.LEVEL_CONNECTED, null)
        callbacks.updateStateString("CONNECTED", null, 0, ConnectionStatus.LEVEL_CONNECTED, null)

        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
        assertFalse(ReflectionHelpers.getField(service, "userInitiatedStop"))
    }

    @Test
    fun forwardsPauseActionToEngineService() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        drainStartedServices(service)

        val pauseIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_PAUSE)
        }
        service.onStartCommand(pauseIntent, 0, 1)

        val startedService = Shadows.shadowOf(service).nextStartedService
        assertNotNull(startedService)
        assertEquals(
            "de.blinkt.openvpn.core.OpenVPNService",
            startedService.component?.className
        )
        assertEquals("de.blinkt.openvpn.PAUSE_VPN", startedService.action)
    }

    @Test
    fun forwardsResumeActionToEngineService() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        drainStartedServices(service)

        val resumeIntent = Intent(appContext, OpenVpnService::class.java).apply {
            putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_RESUME)
        }
        service.onStartCommand(resumeIntent, 0, 1)

        val startedService = Shadows.shadowOf(service).nextStartedService
        assertNotNull(startedService)
        assertEquals(
            "de.blinkt.openvpn.core.OpenVPNService",
            startedService.component?.className
        )
        assertEquals("de.blinkt.openvpn.RESUME_VPN", startedService.action)
    }

    @Test
    fun ignoresStalePausedCallbackAfterUserStopGuard() {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()

        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ReflectionHelpers.setField(service, "ignoreConnectedUntilNotConnected", true)

        service.updateState("VPNPAUSED", null, 0, ConnectionStatus.LEVEL_VPNPAUSED, null)

        assertEquals(ConnectionState.DISCONNECTED, ConnectionStateManager.state.value)
        assertFalse(ReflectionHelpers.getField(service, "ignoreConnectedUntilNotConnected"))
    }

    @Test
    fun stopRequestIdAndStopStartedAtMsAreVolatile() {
        // stopRequestId/stopStartedAtMs are written on the main thread (startUserStopTeardown)
        // and read on the AIDL binder thread (syncEngineState via
        // maybeStartStaleStopReconciliation). Without @Volatile, the binder thread can observe a
        // stale cached value. This locks in the fix alongside the existing
        // userInitiatedStart/userInitiatedStop @Volatile fields.
        val stopRequestIdField = OpenVpnService::class.java.getDeclaredField("stopRequestId")
        val stopStartedAtMsField = OpenVpnService::class.java.getDeclaredField("stopStartedAtMs")

        assertTrue(
            "stopRequestId must be @Volatile for cross-thread visibility",
            java.lang.reflect.Modifier.isVolatile(stopRequestIdField.modifiers)
        )
        assertTrue(
            "stopStartedAtMs must be @Volatile for cross-thread visibility",
            java.lang.reflect.Modifier.isVolatile(stopStartedAtMsField.modifiers)
        )
    }

    @Test
    fun aidlByteCountFieldsAreVolatile() {
        // aidlLastInBytes/aidlLastOutBytes/lastAidlByteUpdateTs are written and read inside
        // updateByteCount(inBytes, outBytes), invoked on the AIDL binder thread. Android's binder
        // thread pool may service successive calls on different worker threads, so @Volatile is
        // required for cross-call memory visibility even without concurrent invocation.
        val aidlLastInBytesField = OpenVpnService::class.java.getDeclaredField("aidlLastInBytes")
        val aidlLastOutBytesField = OpenVpnService::class.java.getDeclaredField("aidlLastOutBytes")
        val lastAidlByteUpdateTsField = OpenVpnService::class.java.getDeclaredField("lastAidlByteUpdateTs")

        assertTrue(
            "aidlLastInBytes must be @Volatile for cross-thread visibility",
            java.lang.reflect.Modifier.isVolatile(aidlLastInBytesField.modifiers)
        )
        assertTrue(
            "aidlLastOutBytes must be @Volatile for cross-thread visibility",
            java.lang.reflect.Modifier.isVolatile(aidlLastOutBytesField.modifiers)
        )
        assertTrue(
            "lastAidlByteUpdateTs must be @Volatile for cross-thread visibility",
            java.lang.reflect.Modifier.isVolatile(lastAidlByteUpdateTsField.modifiers)
        )
    }

    @Test
    fun livePushStatusFieldsAreVolatile() {
        // Regression for BUG-autoswitch-stale-push-stall code review F1: lastLiveStatusMs is
        // written only from updateStateString (a real binder-thread-pool thread, since
        // OpenVPNStatusService runs in a separate :openvpn process) and read from
        // applyStatusSnapshot() on the main looper (onServiceConnected / trafficPollRunnable).
        // Without @Volatile there is no happens-before guarantee, so the main thread could
        // observe a stale cached value and compute livePushStale=false when the live push
        // channel has actually died, silently defeating the stale-push auto-switch fix
        // intermittently. lastStatusSnapshotMs has the same binder-write/main-thread-read
        // pattern (trafficPollRunnable's poll-gating logic also depends on it staying
        // accurate). This locks in the fix alongside the existing aidlLastInBytes/
        // stopRequestId @Volatile fields.
        val lastStatusSnapshotMsField = OpenVpnService::class.java.getDeclaredField("lastStatusSnapshotMs")
        val lastLiveStatusMsField = OpenVpnService::class.java.getDeclaredField("lastLiveStatusMs")

        assertTrue(
            "lastStatusSnapshotMs must be @Volatile for cross-thread visibility",
            java.lang.reflect.Modifier.isVolatile(lastStatusSnapshotMsField.modifiers)
        )
        assertTrue(
            "lastLiveStatusMs must be @Volatile for cross-thread visibility",
            java.lang.reflect.Modifier.isVolatile(lastLiveStatusMsField.modifiers)
        )
    }

    @Test
    fun statusBindingFieldsAreVolatile() {
        // Round-2 bot review (Copilot): statusBinder/boundToStatus are written from
        // statusDeathRecipient's binderDied() callback (a binder-pool thread invoked when the
        // status service dies) and read on the main looper (trafficPollRunnable, isAidlFresh()
        // via applyStatusSnapshot). Same cross-thread visibility pattern as
        // lastStatusSnapshotMs/lastLiveStatusMs above: without @Volatile the main thread could
        // observe a stale cached boundToStatus=true/statusBinder!=null after a binder death,
        // masking a dead status channel.
        val statusBinderField = OpenVpnService::class.java.getDeclaredField("statusBinder")
        val boundToStatusField = OpenVpnService::class.java.getDeclaredField("boundToStatus")

        assertTrue(
            "statusBinder must be @Volatile for cross-thread visibility",
            java.lang.reflect.Modifier.isVolatile(statusBinderField.modifiers)
        )
        assertTrue(
            "boundToStatus must be @Volatile for cross-thread visibility",
            java.lang.reflect.Modifier.isVolatile(boundToStatusField.modifiers)
        )
    }

    @Test
    fun updateStateString_dispatchesAutoSwitchOnEngineLevelThroughMainLooperFromBinderThread() {
        // Regression for the round-2 bot review (Codex): syncEngineState() is reachable both
        // from the AIDL binder-thread callback (updateStateString) and from the main thread
        // (applyStatusSnapshot's snapshot-poll fallback). Before the stale-push auto-switch fix,
        // applyStatusSnapshot() always passed allowAutoSwitch=false, so this call site was
        // binder-thread-only; this fix makes both paths reachable, and ServerAutoSwitcher's
        // internal timer state (runnable/timerActive/seconds) is guarded only by non-atomic
        // check-then-act logic that assumes a single (main-looper) caller. The fix routes the
        // ServerAutoSwitcher.onEngineLevel() dispatch through the existing main-looper
        // statusHandler whenever the caller is not already on the main thread. This test proves
        // the dispatch is deferred -- not executed synchronously -- when invoked from a real
        // background thread (simulating the AIDL binder-pool thread), and only takes effect once
        // the main looper is idled.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ServerAutoSwitcher.resetForTest()
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")

        try {
            val thread = Thread {
                callbacks.updateStateString(
                    "TCP_CONNECT",
                    null,
                    0,
                    ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                    null
                )
            }
            // Copilot review (round 9): a plain non-daemon thread that times out on join() below
            // both fails the assertion AND strands a live thread that can hang the test JVM even
            // after the failure is reported. isDaemon=true lets the JVM exit regardless, and
            // interrupt() on a timeout gives the stuck thread a chance to unwind.
            thread.isDaemon = true
            thread.start()
            thread.join(5_000)
            // Copilot review (round 3): a bare join(5_000) can silently time out without failing
            // the test if the background thread hasn't finished, letting the assertions below run
            // against a possibly-still-executing thread (nondeterministic false positives). Fail
            // fast instead if the thread is still alive.
            if (thread.isAlive) thread.interrupt()
            assertFalse("background thread did not finish within timeout", thread.isAlive)

            assertNull(
                "ServerAutoSwitcher must not be touched synchronously from a non-main-looper " +
                    "thread; the dispatch must be deferred to the main looper queue",
                ServerAutoSwitcher.remainingSeconds.value
            )

            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertNotNull(
                "ServerAutoSwitcher timer must start once the deferred call runs on the main " +
                    "looper",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun updateStateString_authFailedOnFreshConnectionFromBinderThreadStillSwitchesImmediately() {
        // Regression for round-3 bot review (Codex, P1): round 2 fixed a cross-thread
        // ServerAutoSwitcher timer race (see the sibling test above) by deferring
        // dispatchAutoSwitcherOnEngineLevel()'s ServerAutoSwitcher.onEngineLevel() call to the main
        // looper via statusHandler whenever updateStateString() runs on a non-main thread (the real
        // AIDL binder-thread-pool thread). But syncEngineState() still calls
        // ConnectionStateManager.updateFromEngine(level, detail) synchronously and immediately
        // afterward, on the calling (binder) thread -- and for LEVEL_AUTH_FAILED/LEVEL_NONETWORK
        // that flips ConnectionState.CONNECTING -> DISCONNECTED. ServerAutoSwitcher.onEngineLevel()'s
        // shouldSwitchImmediately fast path only requests an immediate switch when
        // timerActive || state==CONNECTING. On a FIRST connection attempt (no auto-switch timer
        // running yet) this depends entirely on state still being CONNECTING at the moment the
        // decision is made. Before the round-2 fix, onEngineLevel() ran synchronously BEFORE
        // updateFromEngine() and correctly observed CONNECTING; after the round-2 fix, when
        // dispatched from a binder thread, onEngineLevel() runs LATER (deferred), by which time
        // updateFromEngine() has already flipped state to DISCONNECTED -- so the deferred call
        // silently skipped the immediate switch it must perform. Fixed by capturing
        // ConnectionStateManager.state synchronously in dispatchAutoSwitcherOnEngineLevel() -- before
        // updateFromEngine() can mutate it -- and threading it through as onEngineLevel's new
        // wasConnectingAtDispatch parameter.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ServerAutoSwitcher.resetForTest()
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)

        val servers = listOf(
            com.yahorzabotsin.openvpnclientgate.core.servers.Server(
                1, "n1", "c1",
                com.yahorzabotsin.openvpnclientgate.core.servers.Country("RU"), 0,
                com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength.STRONG, "ip",
                0, 0, 0, 0, 0, 0, "", "", "", "conf1"
            ),
            com.yahorzabotsin.openvpnclientgate.core.servers.Server(
                2, "n2", "c2",
                com.yahorzabotsin.openvpnclientgate.core.servers.Country("RU"), 0,
                com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength.STRONG, "ip",
                0, 0, 0, 0, 0, 0, "", "", "", "conf2"
            )
        )
        com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore.saveSelection(appContext, "RU", servers)
        com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore.resetIndex(appContext)
        com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)

        val originalStarter = ServerAutoSwitcher.starter
        val originalStopper = ServerAutoSwitcher.stopper
        val startCalls = mutableListOf<String>()
        ServerAutoSwitcher.starter = { _, config, _, _ -> startCalls.add(config) }
        ServerAutoSwitcher.stopper = { _ -> }

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")

        try {
            val thread = Thread {
                callbacks.updateStateString(
                    "AUTH_FAILED",
                    null,
                    0,
                    ConnectionStatus.LEVEL_AUTH_FAILED,
                    null
                )
            }
            // Copilot review (round 9): daemonize so a join() timeout below can't strand a live
            // thread and hang the test JVM; interrupt on timeout as a best-effort unwind signal.
            thread.isDaemon = true
            thread.start()
            thread.join(5_000)
            if (thread.isAlive) thread.interrupt()
            assertFalse("background thread did not finish within timeout", thread.isAlive)

            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            // Simulate the engine reporting teardown complete, which is what actually fires the
            // chained start once ServerAutoSwitcher has requested an immediate switch.
            ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, "AIDL")
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertEquals(
                "AUTH_FAILED on a fresh connection attempt (state=CONNECTING, no auto-switch " +
                    "timer active yet) delivered from a real binder thread must still trigger " +
                    "ServerAutoSwitcher's immediate switch, exactly as it did before the round-2 " +
                    "main-looper dispatch fix introduced this regression",
                listOf("conf2"),
                startCalls
            )
        } finally {
            ServerAutoSwitcher.starter = originalStarter
            ServerAutoSwitcher.stopper = originalStopper
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun userStopCancelsQueuedAutoSwitchDispatchFromBinderThread() {
        // Regression for round-5 bot review (Codex, P2): dispatchAutoSwitcherOnEngineLevel()
        // posts an anonymous Runnable to the main-looper statusHandler when updateStateString()
        // runs on a non-main thread (the real AIDL binder-pool thread). If the user stops the VPN
        // (ACTION_STOP) -- or OpenVpnService.onDestroy() runs -- before the main looper actually
        // executes that queued runnable, it stayed queued forever: teardown only ever called
        // statusHandler.removeCallbacks() on named Runnable fields (stopBindTimeoutRunnable,
        // pauseActionTimeoutRunnable, etc.), never this anonymous one. A stale connecting/failure
        // level could then fire an auto-switch dispatch AFTER the user already stopped the VPN,
        // potentially starting a new connection to another server. Fixed (round 6) by tagging
        // every deferred dispatch with a shared autoSwitchDispatchToken and cancelling the whole
        // family from startUserStopTeardown()/onDestroy() via
        // statusHandler.removeCallbacksAndMessages(token), plus a defensive userInitiatedStop
        // re-check inside the runnable itself as a second layer. This test posts the runnable
        // from a real background thread (as the AIDL binder callback would), then issues
        // ACTION_STOP before idling the main looper, and asserts the queued dispatch never starts
        // the auto-switch timer.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ServerAutoSwitcher.resetForTest()

        val binder = object : IOpenVPNServiceInternal.Stub() {
            override fun protect(fd: Int) = false
            override fun userPause(b: Boolean) {}
            override fun stopVPN(replaceConnection: Boolean) = true
            override fun addAllowedExternalApp(packagename: String?) {}
            override fun isAllowedExternalApp(packagename: String?) = false
            override fun challengeResponse(repsonse: String?) {}
        }
        ReflectionHelpers.setField(service, "engineBinder", binder)
        ReflectionHelpers.setField(service, "boundToEngine", true)

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")

        try {
            val thread = Thread {
                callbacks.updateStateString(
                    "TCP_CONNECT",
                    null,
                    0,
                    ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                    null
                )
            }
            // Copilot review (round 9): daemonize so a join() timeout below can't strand a live
            // thread and hang the test JVM; interrupt on timeout as a best-effort unwind signal.
            thread.isDaemon = true
            thread.start()
            thread.join(5_000)
            if (thread.isAlive) thread.interrupt()
            assertFalse("background thread did not finish within timeout", thread.isAlive)

            // Simulate the user stopping the VPN before the main looper drains the queued
            // dispatch -- exactly the race window the review comment describes.
            val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
                putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
            }
            service.onStartCommand(stopIntent, 0, 1)

            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertNull(
                "a stale auto-switch dispatch queued before the user stopped the VPN must not " +
                    "start the auto-switch timer once teardown has run",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun onDestroyWithoutUserStopCancelsAllQueuedAutoSwitchDispatchesFromBinderThreads() {
        // Regression for round-6 bot review (Codex P2 + Copilot, same root cause reported
        // independently): round 5 tracked only the MOST RECENTLY posted deferred auto-switch
        // dispatch in a single `pendingAutoSwitchRunnable` field. If the AIDL binder thread posts
        // MULTIPLE deferred dispatches before the main looper drains its queue (e.g. rapid
        // engine-level changes), each new post overwrites that field and orphans the previous
        // runnable -- teardown's `pendingAutoSwitchRunnable?.let { removeCallbacks(it) }` could
        // then cancel only the last one, leaving earlier ones queued with no reference left to
        // cancel them. The defensive `if (userInitiatedStop) return@Runnable` check inside each
        // runnable does not close this gap for a system-initiated teardown (onDestroy() without
        // going through startUserStopTeardown(), so userInitiatedStop stays false) -- an orphaned
        // earlier callback can still fire an auto-switch dispatch AFTER the service is destroyed.
        // Fixed by tagging every deferred dispatch with a shared autoSwitchDispatchToken and
        // cancelling the whole family via statusHandler.removeCallbacksAndMessages(token) in
        // onDestroy(), regardless of how many are queued.
        //
        // This test posts TWO deferred dispatches from two separate simulated binder threads
        // before the main looper drains, then calls onDestroy() directly WITHOUT ever setting
        // userInitiatedStop (no ACTION_STOP), and asserts neither queued dispatch reaches
        // ServerAutoSwitcher once the main looper is idled. Against round 5's single-field
        // tracking this test fails: the first-posted runnable is orphaned by the second post,
        // onDestroy() cancels only the second, and the orphaned first runnable still executes
        // (userInitiatedStop is false, so its defensive check does not block it), starting the
        // auto-switch timer.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ServerAutoSwitcher.resetForTest()

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")

        try {
            val thread1 = Thread {
                callbacks.updateStateString(
                    "TCP_CONNECT",
                    null,
                    0,
                    ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                    null
                )
            }
            // Copilot review (round 9): daemonize so a join() timeout below can't strand a live
            // thread and hang the test JVM; interrupt on timeout as a best-effort unwind signal.
            thread1.isDaemon = true
            thread1.start()
            thread1.join(5_000)
            if (thread1.isAlive) thread1.interrupt()
            assertFalse("first background thread did not finish within timeout", thread1.isAlive)

            val thread2 = Thread {
                callbacks.updateStateString(
                    "TCP_CONNECT",
                    null,
                    0,
                    ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                    null
                )
            }
            thread2.isDaemon = true
            thread2.start()
            thread2.join(5_000)
            if (thread2.isAlive) thread2.interrupt()
            assertFalse("second background thread did not finish within timeout", thread2.isAlive)

            // System-initiated teardown (e.g. task removal, low-memory kill) -- never goes
            // through startUserStopTeardown(), so userInitiatedStop stays false and the runnable's
            // own defensive re-check cannot save us here.
            service.onDestroy()

            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertNull(
                "neither of the two queued auto-switch dispatches posted before onDestroy() may " +
                    "start the auto-switch timer -- onDestroy() must cancel the whole family of " +
                    "deferred dispatches, not just the most recently posted one",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun lateBinderDispatchAfterOnDestroyDoesNotEnqueueAutoSwitch() {
        // Regression for round-7 bot review (Codex P2, follow-up to the shared-token fix): the
        // autoSwitchDispatchToken sweep in onDestroy() (statusHandler.removeCallbacksAndMessages)
        // only clears dispatches queued BEFORE the sweep runs. It cannot help against a genuine
        // TOCTOU race: an AIDL binder callback that had already started executing
        // updateStateString()/syncEngineState() on its own thread before teardown began, and just
        // had not yet reached the postAtTime(...) call inside dispatchAutoSwitcherOnEngineLevel(),
        // can still enqueue a BRAND NEW dispatch AFTER the sweep already ran and after
        // unregisterStatusCallback() should have silenced it -- the sweep is a one-time
        // point-in-time cleanup, not an ongoing barrier. During a system-driven onDestroy() (task
        // removal, low-memory kill), userInitiatedStop stays false, so the runnable's existing
        // `if (userInitiatedStop) return@Runnable` defensive re-check does not help either: the
        // newly queued runnable passes its only lifecycle check and can start an auto-switch timer
        // after the service is already destroyed.
        //
        // Fixed by a monotonic `serviceDestroyed` gate set as the very first statement in
        // onDestroy() (before the sweep) and checked at the enqueue point in
        // dispatchAutoSwitcherOnEngineLevel() (and again defensively inside the deferred runnable
        // itself), so ANY dispatch attempt -- whether it started before or after teardown began --
        // is rejected the moment it actually tries to enqueue, not at some earlier point that
        // could go stale.
        //
        // This test calls onDestroy() FIRST (simulating teardown having already fully run), then
        // invokes the AIDL binder callback directly from a real background thread -- exactly like
        // an in-flight callback that reaches syncEngineState() only after the service is destroyed
        // -- and asserts it never starts the auto-switch timer. Against the pre-fix code (no
        // serviceDestroyed flag), this test fails: the callback thread posts a fresh dispatch via
        // postAtTime() after the sweep already ran, userInitiatedStop is false so the runnable's
        // own re-check does not block it, and idling the main looper starts the auto-switch timer.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ServerAutoSwitcher.resetForTest()

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")

        try {
            // System-initiated teardown happens first -- never goes through
            // startUserStopTeardown(), so userInitiatedStop stays false throughout this test.
            service.onDestroy()

            val thread = Thread {
                callbacks.updateStateString(
                    "TCP_CONNECT",
                    null,
                    0,
                    ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                    null
                )
            }
            // Copilot review (round 9): daemonize so a join() timeout below can't strand a live
            // thread and hang the test JVM; interrupt on timeout as a best-effort unwind signal.
            thread.isDaemon = true
            thread.start()
            thread.join(5_000)
            if (thread.isAlive) thread.interrupt()
            assertFalse("background thread did not finish within timeout", thread.isAlive)

            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertNull(
                "a binder callback that reaches dispatchAutoSwitcherOnEngineLevel() only after " +
                    "onDestroy() has already run must not enqueue a new dispatch or start the " +
                    "auto-switch timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun staleBinderDispatchSurvivingUserStopSweepDoesNotAffectRestartedAttempt() {
        // Regression for round-12 bot review (Codex P2, comment 3734663965): "Make user-stop
        // cancellation atomic with enqueue". ACTION_START clears userInitiatedStop back to
        // false whenever the SAME service instance is reused (not destroyed -- round-7's
        // serviceDestroyed flag does not help here since the service isn't being destroyed,
        // just stopped-then-restarted within the same instance). This opens a 5-step race:
        // 1) an old AIDL binder callback (carrying a stale terminal level from the attempt just
        //    stopped) passes the serviceDestroyed check and is about to enqueue its deferred
        //    dispatch;
        // 2) concurrently, the main thread runs a user-stop sweep (startUserStopTeardown),
        //    cancelling queued dispatches via the shared autoSwitchDispatchToken (round 6) --
        //    but the queue is still empty at this exact moment, so the sweep has nothing to
        //    catch;
        // 3) the binder thread's callback then calls postAtTime(...) to enqueue AFTER the sweep
        //    already ran -- this queued runnable is now untouched by the sweep;
        // 4) a fresh ACTION_START arrives (user or auto-switch reconnecting), reusing the SAME
        //    service instance, and clears userInitiatedStop back to false as part of starting
        //    the new attempt;
        // 5) the queued runnable from step 3 executes: its userInitiatedStop re-check (round 5)
        //    now sees false -- cleared by the NEW start in step 4 -- so it does NOT skip, and
        //    proceeds to call ServerAutoSwitcher.onEngineLevel() with the stale level, cancelling
        //    the switch timer of the brand-new, unrelated attempt.
        //
        // Fixed by connectionAttemptGeneration: a monotonic counter bumped on every ACTION_START,
        // captured at dispatch-enqueue time and re-validated inside the runnable, right before it
        // touches ServerAutoSwitcher, regardless of what userInitiatedStop currently reads.
        //
        // Against the pre-fix code this test fails: the stale dispatch (captured generation 1)
        // reaches ServerAutoSwitcher after the restart (generation 2) and cancels the new
        // attempt's active switch timer.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ServerAutoSwitcher.resetForTest()

        val binder = object : IOpenVPNServiceInternal.Stub() {
            override fun protect(fd: Int) = false
            override fun userPause(b: Boolean) {}
            override fun stopVPN(replaceConnection: Boolean) = true
            override fun addAllowedExternalApp(packagename: String?) {}
            override fun isAllowedExternalApp(packagename: String?) = false
            override fun challengeResponse(repsonse: String?) {}
        }
        ReflectionHelpers.setField(service, "engineBinder", binder)
        ReflectionHelpers.setField(service, "boundToEngine", true)

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")

        try {
            // Step 0: the FIRST connection attempt is already under way --
            // connectionAttemptGeneration is 1. Set directly via reflection rather than driving a
            // real ACTION_START intent through onStartCommand: entering the controller foreground
            // (startForeground/notification) is not satisfiable in this Robolectric environment
            // and is infrastructure unrelated to the race under test -- the same reason the
            // existing currentAttemptStartMs-based tests above set that field directly instead of
            // going through onStartCommand.
            ReflectionHelpers.setField(service, "connectionAttemptGeneration", 1)

            // Race step 2: the main thread runs the REAL user-stop sweep via a genuine ACTION_STOP
            // intent (startUserStopTeardown()), which sets userInitiatedStop=true and cancels
            // queued dispatches via removeCallbacksAndMessages(autoSwitchDispatchToken). The
            // dispatch queue is still empty at this exact moment, so the sweep has nothing to
            // cancel -- exactly the ordering the race depends on. Unlike ACTION_START,
            // ACTION_STOP's exitControllerForeground() no-ops safely when the controller was never
            // in the foreground, so this path runs cleanly here.
            val stopIntent = Intent(appContext, OpenVpnService::class.java).apply {
                putExtra(VpnManager.actionKey(appContext), VpnManager.ACTION_STOP)
            }
            service.onStartCommand(stopIntent, 0, 1)
            assertTrue(ReflectionHelpers.getField(service, "userInitiatedStop"))

            // Race steps 1 and 3: an old AIDL binder callback -- carrying a stale terminal level
            // left over from the attempt just stopped -- reaches dispatchAutoSwitcherOnEngineLevel()
            // on a real background thread AFTER the sweep above already ran. It captures
            // connectionAttemptGeneration=1 (still the live value at this point) and, because it
            // is not running on the main looper, defers via postAtTime() instead of running
            // synchronously -- the enqueue-after-sweep gap the race depends on.
            val thread = Thread {
                callbacks.updateStateString(
                    "NOPROCESS",
                    null,
                    0,
                    ConnectionStatus.LEVEL_NOTCONNECTED,
                    null
                )
            }
            thread.isDaemon = true
            thread.start()
            thread.join(5_000)
            if (thread.isAlive) thread.interrupt()
            assertFalse("background thread did not finish within timeout", thread.isAlive)

            // Race step 4: a fresh ACTION_START arrives -- reusing the SAME service instance --
            // and, as part of starting the new attempt, clears userInitiatedStop back to false and
            // bumps connectionAttemptGeneration to 2. Modeled directly on the two fields
            // ACTION_START mutates for this purpose (see onStartCommand's ACTION_START branch),
            // for the same Robolectric-FGS reason as step 0 above.
            ReflectionHelpers.setField(service, "userInitiatedStop", false)
            ReflectionHelpers.setField(service, "connectionAttemptGeneration", 2)

            // Prime the NEW attempt's OWN switch timer with a genuinely fresh CONNECTING callback
            // on the main/test thread (dispatched synchronously, generation 2 matches generation
            // 2), simulating this brand-new, unrelated connection attempt actually being under way.
            callbacks.updateStateString(
                "TCP_CONNECT",
                null,
                0,
                ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
                null
            )
            assertNotNull(
                "Setup precondition: the NEW attempt's own switch timer must be active before " +
                    "the stale queued dispatch executes",
                ServerAutoSwitcher.remainingSeconds.value
            )

            // Race step 5: let the queued runnable from step 3 finally execute.
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertNotNull(
                "A deferred auto-switch dispatch captured for a PREVIOUS attempt generation -- " +
                    "enqueued after a user-stop sweep already ran and only surviving because a " +
                    "fresh ACTION_START cleared userInitiatedStop back to false -- must NOT reach " +
                    "ServerAutoSwitcher and must NOT cancel the brand-new attempt's own active " +
                    "switch timer",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun deferredDispatchQueuedBeforeStopConfirmationDoesNotReachAutoSwitcher() {
        // Regression for round-13 bot review (Codex P2, comment 3734974192): "Invalidate queued
        // dispatches when stop is confirmed". finishStopFlowConfirmed() clears userInitiatedStop
        // BEFORE stopSelf() is called, and BEFORE onDestroy() actually runs (serviceDestroyed only
        // flips there -- see round 7). A full user-initiated stop-to-shutdown (no restart) never
        // fires ACTION_START, so round-12's connectionAttemptGeneration counter was never bumped
        // either. This opens a window: userInitiatedStop=false (cleared by confirmation),
        // serviceDestroyed=false (onDestroy hasn't run yet), and the generation unchanged (no
        // ACTION_START happened). A binder callback's deferred auto-switch dispatch -- queued via
        // postAtTime() BEFORE the stop was confirmed -- that executes during this exact window
        // passes all three existing defensive checks in dispatchAutoSwitcherOnEngineLevel()'s
        // runnable and incorrectly reaches ServerAutoSwitcher, which could start a reconnect after
        // the user explicitly disconnected.
        //
        // Fixed by also bumping connectionAttemptGeneration inside finishStopFlowConfirmed(),
        // reusing the exact mechanism round 12 introduced for ACTION_START rather than inventing a
        // new flag.
        //
        // Against the pre-fix code this test fails: the queued dispatch (captured generation 0,
        // never bumped by the stop confirmation) reaches ServerAutoSwitcher and starts its switch
        // timer.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ServerAutoSwitcher.resetForTest()

        try {
            // A binder callback carrying a level from the attempt being torn down reaches
            // dispatchAutoSwitcherOnEngineLevel() on a real background thread, BEFORE the stop is
            // confirmed. It captures the live generation (0 here; no ACTION_START has run in this
            // test) and, since it is not on the main looper, defers via postAtTime() -- the same
            // enqueue-then-execute-later gap round 12's test exercises.
            val thread = Thread {
                ReflectionHelpers.callInstanceMethod<Any>(
                    service,
                    "dispatchAutoSwitcherOnEngineLevel",
                    ClassParameter.from(
                        ConnectionStatus::class.java,
                        ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET
                    )
                )
            }
            thread.isDaemon = true
            thread.start()
            thread.join(5_000)
            if (thread.isAlive) thread.interrupt()
            assertFalse("background thread did not finish within timeout", thread.isAlive)

            // The stop now confirms -- model a real user-initiated stop already in progress
            // (startUserStopTeardown() would have set this earlier) so finishStopFlowConfirmed()'s
            // own guard (`if (!userInitiatedStop) return`) lets it proceed.
            ReflectionHelpers.setField(service, "userInitiatedStop", true)
            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "finishStopFlowConfirmed",
                ClassParameter.from(ConnectionStatus::class.java, ConnectionStatus.LEVEL_NOTCONNECTED),
                ClassParameter.from(String::class.java, "AIDL")
            )

            // serviceDestroyed must still be false here -- onDestroy() has not run yet, exactly
            // the window under test.
            assertFalse(ReflectionHelpers.getField<Boolean>(service, "serviceDestroyed"))

            // Let the queued runnable from the background thread execute.
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertNull(
                "a deferred auto-switch dispatch queued BEFORE the stop was confirmed must not " +
                    "reach ServerAutoSwitcher once the stop confirms and clears " +
                    "userInitiatedStop, even though onDestroy() has not run yet",
                ServerAutoSwitcher.remainingSeconds.value
            )
        } finally {
            ServerAutoSwitcher.resetForTest()
        }
    }

    @Test
    fun updateStateString_writesLiveStatusTimestampsThroughInjectedClock() {
        // Round-2 bot review (Copilot): isAidlFresh()/applyStatusSnapshot() read time via the
        // injectable watchdogNowMs(), but lastLiveStatusMs/lastStatusSnapshotMs were still
        // written with raw System.currentTimeMillis() in updateStateString(). That is a no-op in
        // production (watchdogNowMs defaults to System.currentTimeMillis), but it is a real
        // test-determinism/consistency gap: a test overriding watchdogNowMs() would get
        // freshness/poll-gating math that does not match the injected clock. This locks in that
        // both timestamps are now sourced from watchdogNowMs() instead of the real clock.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val fixedNow = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ fixedNow } as () -> Long))
        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")

        callbacks.updateStateString("CONNECTED", null, 0, ConnectionStatus.LEVEL_CONNECTED, null)

        assertEquals(
            "lastStatusSnapshotMs must be sourced from the injected watchdogNowMs clock",
            fixedNow,
            ReflectionHelpers.getField<Long>(service, "lastStatusSnapshotMs")
        )
        assertEquals(
            "lastLiveStatusMs must be sourced from the injected watchdogNowMs clock",
            fixedNow,
            ReflectionHelpers.getField<Long>(service, "lastLiveStatusMs")
        )
    }

    @Test
    fun userInitiatedStartIsClearedOnFailedConnectWhenAutoSwitchDisabled() {
        // Regression: when auto-switch is disabled and a user-initiated start fails to
        // LEVEL_NOTCONNECTED, the auto-switch block in updateState() is skipped entirely, so
        // userInitiatedStart must still be cleared in the terminal-level branch below it.
        // Otherwise syncEngineState's reconnectPending guard keeps suppressing
        // exitControllerForeground() forever, leaving the "VPN connecting" notification stuck.
        com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore.save(
            appContext,
            com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore.load(appContext)
                .copy(autoSwitchWithinCountry = false)
        )
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "userInitiatedStart", true)
        ReflectionHelpers.setField(service, "suppressEngineState", false)
        ConnectionStateManager.setReconnectingHint(false)

        service.updateState("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, null)

        assertFalse(
            "userInitiatedStart must be cleared after a failed start when auto-switch is disabled",
            ReflectionHelpers.getField<Boolean>(service, "userInitiatedStart")
        )
    }

    @Test
    fun userInitiatedStartIsClearedOnAidlTerminalFailureLevel() {
        // Regression: when the status service is fresh (isAidlFresh()=true), updateState()
        // (VPN_STATUS) returns early and never reaches the clear above — syncEngineState(),
        // called from the AIDL callback path (updateStateString), is then the only place that
        // can reset userInitiatedStart. Before this fix it only cleared on LEVEL_CONNECTED,
        // leaving a failed user-initiated connect (e.g. LEVEL_NOTCONNECTED) stuck.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "userInitiatedStart", true)
        ConnectionStateManager.setReconnectingHint(false)

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        callbacks.updateStateString("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, null)

        assertFalse(
            "userInitiatedStart must be cleared on an AIDL terminal failure level",
            ReflectionHelpers.getField<Boolean>(service, "userInitiatedStart")
        )
    }

    @Test
    fun keepsForegroundActiveOnSingleAidlTerminalFailureCallback_staleCallbackAmbiguity() {
        // Accepted limitation (round 10): an immediate exitControllerForeground() here was tried
        // in rounds 7-8 and reverted. A stale LEVEL_NOTCONNECTED from a PREVIOUS session can
        // legitimately arrive while a NEW user-initiated start is still in flight
        // (userInitiatedStart=true, reconnectingHint=false) — indistinguishable from a genuine
        // terminal failure of the current attempt without a start-generation token. Exiting
        // foreground in that case would reopen the exact FGS crash window this guard exists to
        // prevent, so foreground correctly stays active here; userInitiatedStart is still cleared
        // (see userInitiatedStartIsClearedOnAidlTerminalFailureLevel) so a later idle callback,
        // if any, will exit it.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "userInitiatedStart", true)
        ReflectionHelpers.setField(service, "controllerForegroundActive", true)
        ConnectionStateManager.setReconnectingHint(false)

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        callbacks.updateStateString("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, null)

        assertTrue(
            "controllerForegroundActive must stay active on a single terminal-failure callback " +
                "(cannot safely distinguish it from a stale callback for an in-flight new start)",
            ReflectionHelpers.getField<Boolean>(service, "controllerForegroundActive")
        )
    }

    @Test
    fun keepsForegroundActiveDuringChainedAutoSwitch() {
        // Guardrail: a terminal-failure callback during an active chained auto-switch
        // (reconnectingHint=true) must NOT exit foreground — the engine is intentionally
        // torn down before the next server start (2026-06-25 FGS crash fix).
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "userInitiatedStart", true)
        ReflectionHelpers.setField(service, "controllerForegroundActive", true)
        ConnectionStateManager.setReconnectingHint(true)

        val callbacks = ReflectionHelpers.getField<IStatusCallbacks>(service, "statusCallbacks")
        callbacks.updateStateString("NOPROCESS", null, 0, ConnectionStatus.LEVEL_NOTCONNECTED, null)

        assertTrue(
            "controllerForegroundActive must stay active during a chained auto-switch",
            ReflectionHelpers.getField<Boolean>(service, "controllerForegroundActive")
        )
    }

    @Test
    fun startUserStopTeardown_cancelsActiveAutoSwitchTimerOnMainThread() {
        // Regression for PR #126 round 18 (Codex P1, comment 3736956722): ServerAutoSwitcher
        // runs its countdown timer on its OWN main-looper Handler, completely separate from
        // this service's statusHandler. startUserStopTeardown()'s
        // statusHandler.removeCallbacksAndMessages(autoSwitchDispatchToken) sweep only cancels
        // queued-but-not-yet-run dispatches TO ServerAutoSwitcher -- it does nothing to a timer
        // ServerAutoSwitcher is already running from before teardown began, because
        // dispatchAutoSwitcherOnEngineLevel's userInitiatedStop/serviceDestroyed guard discards
        // the one AIDL callback (LEVEL_NOTCONNECTED) that would otherwise have reached
        // ServerAutoSwitcher.onEngineLevel() and stopped it there. Before this fix, that timer
        // fires a few seconds after an explicit user disconnect and silently reconnects.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ServerAutoSwitcher.resetForTest()
        ServerAutoSwitcher.setNoReplyThresholdForTest(5)
        val originalStarter = ServerAutoSwitcher.starter
        val originalStopper = ServerAutoSwitcher.stopper
        val startCalls = mutableListOf<String>()
        ServerAutoSwitcher.starter = { _, config, _, _ -> startCalls.add(config) }
        ServerAutoSwitcher.stopper = { _ -> }

        try {
            ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, "AIDL")
            assertNotNull(
                "precondition: the auto-switch timer must be actively running before teardown",
                ServerAutoSwitcher.remainingSeconds.value
            )

            ReflectionHelpers.callInstanceMethod<Any>(
                service,
                "startUserStopTeardown",
                ClassParameter.from(String::class.java, "user_action"),
                ClassParameter.from(Boolean::class.javaPrimitiveType, true)
            )

            assertNull(
                "a user-stop teardown on the main thread must cancel an already-running " +
                    "ServerAutoSwitcher timer immediately",
                ServerAutoSwitcher.remainingSeconds.value
            )

            // Advance well past the original 5s threshold; the cancelled timer must not fire.
            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertTrue(
                "ServerAutoSwitcher must not reconnect after its timer was cancelled by a user stop",
                startCalls.isEmpty()
            )
        } finally {
            ServerAutoSwitcher.starter = originalStarter
            ServerAutoSwitcher.stopper = originalStopper
            ServerAutoSwitcher.resetForTest()
            ServerAutoSwitcher.resetNoReplyThreshold()
        }
    }

    @Test
    fun startUserStopTeardown_cancelsActiveAutoSwitchTimerFromBinderThreadOnceMainLooperDrains() {
        // Follow-up finding during round 18 review (this fix's own thread-safety check,
        // required by comment 3736956722): startUserStopTeardown() is reachable synchronously
        // from the AIDL binder thread via maybeStartStaleStopReconciliation() ->
        // syncEngineState() -> updateStateString() (the "stale_relaunch" path) -- unlike the
        // ACTION_STOP (onStartCommand, always main thread) and watchdog_fail_safe (always
        // dispatched via statusHandler.post in handleConnectedProbeResult) call sites, it is NOT
        // guaranteed to run on the main thread. ServerAutoSwitcher's internal timer state
        // (runnable/seconds/timerActive/timerLevel) is plain, non-volatile state that assumes a
        // single main-looper caller -- the same invariant dispatchAutoSwitcherOnEngineLevel's own
        // Looper.myLooper() check protects a few lines below in this same file. The fix mirrors
        // that exact pattern: it dispatches the ServerAutoSwitcher.cancelForUserStop() call onto
        // the main thread via statusHandler.post {} whenever startUserStopTeardown() itself was
        // NOT already called on the main thread. This test proves the cancellation is deferred
        // -- not executed synchronously -- when startUserStopTeardown() is invoked from a real
        // background thread (simulating the AIDL binder-pool thread), and only takes effect once
        // the main looper is idled.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ServerAutoSwitcher.resetForTest()
        ServerAutoSwitcher.setNoReplyThresholdForTest(5)
        val originalStarter = ServerAutoSwitcher.starter
        val originalStopper = ServerAutoSwitcher.stopper
        val startCalls = mutableListOf<String>()
        ServerAutoSwitcher.starter = { _, config, _, _ -> startCalls.add(config) }
        ServerAutoSwitcher.stopper = { _ -> }

        try {
            ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, "AIDL")
            assertNotNull(
                "precondition: the auto-switch timer must be actively running before teardown",
                ServerAutoSwitcher.remainingSeconds.value
            )

            val thread = Thread {
                ReflectionHelpers.callInstanceMethod<Any>(
                    service,
                    "startUserStopTeardown",
                    ClassParameter.from(String::class.java, "stale_relaunch"),
                    ClassParameter.from(Boolean::class.javaPrimitiveType, false)
                )
            }
            // Copilot review precedent (round 9, sibling binder-thread test above): daemon +
            // bounded join + interrupt-on-timeout so a stuck thread cannot hang the test JVM.
            thread.isDaemon = true
            thread.start()
            thread.join(5_000)
            if (thread.isAlive) thread.interrupt()
            assertFalse("background thread did not finish within timeout", thread.isAlive)

            assertNotNull(
                "ServerAutoSwitcher must not be cancelled synchronously from a non-main-looper " +
                    "thread; the cancellation must be deferred to the main looper queue",
                ServerAutoSwitcher.remainingSeconds.value
            )

            ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

            assertNull(
                "ServerAutoSwitcher timer must be cancelled once the deferred cancellation runs " +
                    "on the main looper",
                ServerAutoSwitcher.remainingSeconds.value
            )
            assertTrue(
                "ServerAutoSwitcher must not reconnect after its timer was cancelled by a " +
                    "binder-thread-originated user stop",
                startCalls.isEmpty()
            )
        } finally {
            ServerAutoSwitcher.starter = originalStarter
            ServerAutoSwitcher.stopper = originalStopper
            ServerAutoSwitcher.resetForTest()
            ServerAutoSwitcher.resetNoReplyThreshold()
        }
    }

    @Test
    fun applyStatusSnapshot_reattachSnapshotWithConnectedDetailAndLaggingRawLevelIsAcceptedDespiteAge() {
        // Regression for round-19 bot review (Codex P2, comment 3737217807): a recreated
        // controller reattaching via ACTION_SYNC_STATUS (e.g. process/service restart while the
        // engine is already connected) can observe its FIRST status snapshot with a lagging raw
        // `level` (LEVEL_NONETWORK) while the accompanying `state` detail already reads
        // "CONNECTED" -- the two fields can update on slightly different cadences, exactly the
        // phenomenon ConnectionStateManager.normalizeEngineLevel's own doc comment describes and
        // the same class of bug round 14 already fixed for ServerAutoSwitcher's consumption of
        // this data. Before this fix, the unknown-attempt backfill pre-filter classified
        // terminal-ness using the RAW level, so this snapshot looked terminal (LEVEL_NONETWORK is
        // in STOP_TERMINAL_LEVELS), skipping the currentAttemptStartMs backfill. With
        // currentAttemptStartMs left at 0L, the snapshot then fell through to the age-only
        // fallback, which rejected it as stale purely because it is older than
        // staleSnapshotMaxAgeMs (10s) -- so a healthy, already-connected VPN got reported as
        // disconnected right after reattachment, and syncEngineState() (where normalization
        // actually happens) never even ran.
        //
        // Against pre-fix code this test fails: the snapshot is rejected as stale, syncEngineState
        // never runs, and ConnectionStateManager.state.value stays DISCONNECTED (the setUp()
        // default) instead of flipping to CONNECTED.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // currentAttemptStartMs intentionally left at its default 0L (unknown) -- this service
        // instance never went through ACTION_START, simulating the ACTION_SYNC_STATUS-only
        // reattachment lifecycle path this comment describes.

        // Raw level lags as LEVEL_NONETWORK, but state already reports "CONNECTED", and the
        // snapshot's own timestamp is older than staleSnapshotMaxAgeMs (10s) relative to `now`.
        val reattachSnapshot = StatusSnapshot(
            "CONNECTED",
            null,
            0,
            ConnectionStatus.LEVEL_NONETWORK,
            now - 15_000L,
            0L
        )

        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, reattachSnapshot)
        )

        assertEquals(
            "A reattachment snapshot whose state says CONNECTED must be accepted (not rejected " +
                "as stale) even though its raw level lags as LEVEL_NONETWORK and its timestamp " +
                "is older than staleSnapshotMaxAgeMs -- syncEngineState() must run and drive " +
                "ConnectionStateManager to CONNECTED",
            ConnectionState.CONNECTED,
            ConnectionStateManager.state.value
        )
        assertEquals(
            "currentAttemptStartMs must be backfilled to the snapshot's own timestamp, proving " +
                "the backfill pre-filter classified this snapshot as active (not terminal) using " +
                "the normalized level",
            now - 15_000L,
            ReflectionHelpers.getField<Long>(service, "currentAttemptStartMs")
        )
    }

    @Test
    fun applyStatusSnapshot_reattachSnapshotGenuinelyTerminalStillRejectedAsStale() {
        // Companion to the test above: proves the round-19 fix does not widen the backfill
        // condition too far. A snapshot whose raw level AND state are BOTH genuinely terminal
        // (LEVEL_NONETWORK with state="NONETWORK", no "CONNECTED" detail) must still be classified
        // as terminal after normalization too (normalizeEngineLevel only overrides the level when
        // detail=="CONNECTED"), so the backfill is correctly skipped and this old, unknown-attempt
        // snapshot is still rejected via the pre-existing age-only fallback -- exactly the
        // pre-round-19 behavior for a genuinely idle engine.
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        val now = 1_700_000_000_000L
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        // currentAttemptStartMs intentionally left at its default 0L (unknown), same
        // ACTION_SYNC_STATUS-only reattachment lifecycle path as the test above.

        val genuinelyTerminalSnapshot = StatusSnapshot(
            "NONETWORK",
            null,
            0,
            ConnectionStatus.LEVEL_NONETWORK,
            now - 15_000L,
            0L
        )

        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "applyStatusSnapshot",
            ClassParameter.from(StatusSnapshot::class.java, genuinelyTerminalSnapshot)
        )

        assertEquals(
            "A genuinely terminal snapshot (raw level AND state both NONETWORK, no CONNECTED " +
                "detail) must still be rejected as stale -- syncEngineState() must NOT run, so " +
                "ConnectionStateManager.state.value must stay at the setUp() default of " +
                "DISCONNECTED",
            ConnectionState.DISCONNECTED,
            ConnectionStateManager.state.value
        )
        assertEquals(
            "currentAttemptStartMs must remain unbackfilled (0L) for a genuinely terminal " +
                "snapshot -- the backfill pre-filter must still classify it as terminal after " +
                "normalization",
            0L,
            ReflectionHelpers.getField<Long>(service, "currentAttemptStartMs")
        )
    }

    private fun drainStartedServices(service: OpenVpnService) {
        val shadow = Shadows.shadowOf(service)
        while (shadow.nextStartedService != null) {
            // Drain service queue so assertions inspect only action under test.
        }
    }
}

