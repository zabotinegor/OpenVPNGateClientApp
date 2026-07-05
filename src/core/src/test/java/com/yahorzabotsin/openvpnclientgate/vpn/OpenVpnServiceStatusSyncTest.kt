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

