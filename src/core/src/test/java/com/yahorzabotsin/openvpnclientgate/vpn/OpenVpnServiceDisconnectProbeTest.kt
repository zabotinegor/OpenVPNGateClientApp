package com.yahorzabotsin.openvpnclientgate.vpn

import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.servers.probe.ProbeRequestQueue
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * Unit tests for US-12 AC-1: hardprobe is enqueued on user-initiated VPN disconnect.
 *
 * Covers:
 * 1. finishStopFlowConfirmed enqueues probe for the last-started server when id != 0.
 * 2. finishStopFlowConfirmed does NOT enqueue probe when server id is 0.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenVpnServiceDisconnectProbeTest {

    private val appContext = RuntimeEnvironment.getApplication()

    private class FakeProbeRequestQueue : ProbeRequestQueue {
        val enqueuedIds = mutableListOf<Int>()
        override fun enqueue(serverId: Int) {
            enqueuedIds.add(serverId)
        }
    }

    private lateinit var fakeQueue: FakeProbeRequestQueue
    private lateinit var service: OpenVpnService

    @Before
    fun setUp() {
        ShadowLog.clear()
        fakeQueue = FakeProbeRequestQueue()
        service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        // Inject probe queue via the internal field
        ReflectionHelpers.setField(service, "probeQueue", fakeQueue as ProbeRequestQueue)
        // Set userInitiatedStop = true so finishStopFlowConfirmed does not early-return
        ReflectionHelpers.setField(service, "userInitiatedStop", true)
        // Set initial connection state
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    /**
     * AC-1: When the user disconnects and the current server id matches the last-started config,
     * finishStopFlowConfirmed must enqueue a hardprobe for that server id.
     */
    @Test
    fun finishStopFlowConfirmed_enqueuesToProbeQueueWithCorrectServerId() {
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1",
                0, 0, 0, 0, 0, 0, "", "", "", "conf1", id = 42),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2",
                0, 0, 0, 0, 0, 0, "", "", "", "conf2", id = 99)
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        // Save last started config matching current server at index 0 (id=42)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "conf1", "ip1")

        invokeFinishStopFlowConfirmed(service, ConnectionStatus.LEVEL_NOTCONNECTED, "test")

        assertEquals("Expected one enqueue call", 1, fakeQueue.enqueuedIds.size)
        assertEquals("Expected serverId=42", 42, fakeQueue.enqueuedIds[0])
    }

    /**
     * AC-1 guard: When the server id resolves to 0 (unknown/mismatched server),
     * finishStopFlowConfirmed must NOT enqueue any probe.
     */
    @Test
    fun finishStopFlowConfirmed_doesNotEnqueueProbeWhenServerIdIsZero() {
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1",
                0, 0, 0, 0, 0, 0, "", "", "", "conf1", id = 0),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2",
                0, 0, 0, 0, 0, 0, "", "", "", "conf2", id = 0)
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "conf1", "ip1")

        invokeFinishStopFlowConfirmed(service, ConnectionStatus.LEVEL_NOTCONNECTED, "test")

        assertTrue("No probe should be enqueued when serverId=0", fakeQueue.enqueuedIds.isEmpty())
    }

    /**
     * AC-1 guard: When the current server config does not match the last-started config
     * (user changed selection between connect and disconnect),
     * finishStopFlowConfirmed must NOT enqueue a probe.
     *
     * Note: saveLastStartedConfig internally calls ensureIndexForConfig which realigns the
     * current index to match the last-started config string. To keep current != lastStarted,
     * we use a lastStarted config string that does NOT exist in the servers list so the index
     * stays at 0 (conf1/id=42) while lastStarted="old-conf", producing a mismatch → id=0.
     */
    @Test
    fun finishStopFlowConfirmed_doesNotEnqueueProbeWhenConfigMismatch() {
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1",
                0, 0, 0, 0, 0, 0, "", "", "", "conf1", id = 42),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2",
                0, 0, 0, 0, 0, 0, "", "", "", "conf2", id = 99)
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        // "old-conf" is not in the servers list: ensureIndexForConfig will not realign the
        // index, leaving currentServer=conf1 (index 0) while lastStarted="old-conf" (mismatch).
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "old-conf", "old-ip")

        invokeFinishStopFlowConfirmed(service, ConnectionStatus.LEVEL_NOTCONNECTED, "test")

        assertTrue("No probe when current server config mismatches last-started", fakeQueue.enqueuedIds.isEmpty())
    }

    private fun invokeFinishStopFlowConfirmed(
        service: OpenVpnService,
        level: ConnectionStatus,
        source: String
    ) {
        ReflectionHelpers.callInstanceMethod<Unit>(
            service,
            "finishStopFlowConfirmed",
            ClassParameter.from(ConnectionStatus::class.java, level),
            ClassParameter.from(String::class.java, source)
        )
    }
}
