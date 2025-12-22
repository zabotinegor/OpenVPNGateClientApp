package com.yahorzabotsin.openvpnclient.vpn

import android.content.Intent
import com.yahorzabotsin.openvpnclient.core.logging.LogTags
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.IStatusCallbacks
import de.blinkt.openvpn.core.StatusSnapshot
import org.junit.After
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
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
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
    fun userStopFallbackStartsDisconnectActivityWhenStopFails() {
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

        val nextActivity = Shadows.shadowOf(service).nextStartedActivity
        assertNotNull(nextActivity)
        assertEquals("de.blinkt.openvpn.activities.DisconnectVPN", nextActivity.component?.className)
    }
}
