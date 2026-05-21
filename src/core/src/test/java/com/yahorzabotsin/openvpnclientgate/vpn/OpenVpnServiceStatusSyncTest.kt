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

    private fun drainStartedServices(service: OpenVpnService) {
        val shadow = Shadows.shadowOf(service)
        while (shadow.nextStartedService != null) {
            // Drain service queue so assertions inspect only action under test.
        }
    }
}

