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
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now)
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
        val aidlFreshWindowMs = ReflectionHelpers.getField<Long>(service, "aidlFreshWindowMs")
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))

        ReflectionHelpers.setField(service, "boundToStatus", true)
        ReflectionHelpers.setField(service, "lastLiveStatusMs", now - aidlFreshWindowMs)
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
            thread.start()
            thread.join(5_000)
            // Copilot review (round 3): a bare join(5_000) can silently time out without failing
            // the test if the background thread hasn't finished, letting the assertions below run
            // against a possibly-still-executing thread (nondeterministic false positives). Fail
            // fast instead if the thread is still alive.
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
            thread.start()
            thread.join(5_000)
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
            thread.start()
            thread.join(5_000)
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
            thread1.start()
            thread1.join(5_000)
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
            thread2.start()
            thread2.join(5_000)
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

    private fun drainStartedServices(service: OpenVpnService) {
        val shadow = Shadows.shadowOf(service)
        while (shadow.nextStartedService != null) {
            // Drain service queue so assertions inspect only action under test.
        }
    }
}

