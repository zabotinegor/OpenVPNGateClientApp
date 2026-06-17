package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import android.os.Looper
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.servers.probe.ProbeRequestQueue
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration

/**
 * Unit tests for SUB-04: VPN inactivity → hardprobe trigger integration.
 *
 * Covers the 5 AC-6 scenarios:
 * 1. autoswitch probe enqueue (timeout triggers probe for current server id)
 * 2. watchdog probe enqueue (watchdog recovery enqueues probe for current server id)
 * 3. user-stop no-enqueue (user disconnects → no probe)
 * 4. zero-id guard (server with id=0 → no probe enqueued)
 * 5. NONETWORK device-loss no-enqueue (LEVEL_NONETWORK source="AIDL" → no probe)
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class VpnInactivityHardprobeTriggerTest {

    private val appContext = RuntimeEnvironment.getApplication()
    private var originalStarter: ((android.content.Context, String, String?, Boolean) -> Unit)? = null
    private var originalStopper: ((android.content.Context) -> Unit)? = null

    private class FakeProbeRequestQueue : ProbeRequestQueue {
        val enqueuedIds = mutableListOf<Int>()
        override fun enqueue(serverId: Int) {
            enqueuedIds.add(serverId)
        }
    }

    private lateinit var fakeQueue: FakeProbeRequestQueue

    @Before
    fun setUp() {
        ShadowLog.clear()
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        ServerAutoSwitcher.setNoReplyThresholdForTest(2)
        ServerAutoSwitcher.setRepliedThresholdForTest(2)
        UserSettingsStore.saveStatusStallTimeoutSeconds(appContext, 2)

        originalStarter = ServerAutoSwitcher.starter
        ServerAutoSwitcher.starter = { _, _, _, _ -> }
        originalStopper = ServerAutoSwitcher.stopper
        ServerAutoSwitcher.stopper = { _ -> }

        fakeQueue = FakeProbeRequestQueue()
        ServerAutoSwitcher.setProbeRequestQueueForTest(fakeQueue)

        // Reset ServerAutoSwitcher internal timer/wait state by driving it to CONNECTED then NOTCONNECTED.
        // This clears waitingStopForRetry, timerActive, cycleStartIndex, etc. left from any previous test.
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, "RESET")
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @After
    fun tearDown() {
        // Reset state before restoring callbacks
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTED, "RESET")
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        originalStarter?.let { ServerAutoSwitcher.starter = it }
        originalStopper?.let { ServerAutoSwitcher.stopper = it }
        ServerAutoSwitcher.resetNoReplyThreshold()
        ServerAutoSwitcher.resetRepliedThreshold()
        ServerAutoSwitcher.setProbeRequestQueueForTest(null)
        ShadowLog.clear()
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    // AC-1 / AC-6 scenario 1: timeout autoswitch enqueues probe for failing server id
    @Test
    fun autoswitchTimeout_enqueuesToProbeQueueWithCorrectServerId() {
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1",
                0, 0, 0, 0, 0, 0, "", "", "", "conf1", id = 42),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2",
                0, 0, 0, 0, 0, 0, "", "", "", "conf2", id = 99)
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "conf1", "ip1")

        // Trigger timeout auto-switch
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, "AIDL")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertEquals(1, fakeQueue.enqueuedIds.size)
        assertEquals(42, fakeQueue.enqueuedIds[0])
    }

    // AC-1 / AC-6 scenario 1b: auth-failed immediate switch also enqueues probe
    @Test
    fun authFailedImmediateSwitch_enqueuesToProbeQueueWithCorrectServerId() {
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1",
                0, 0, 0, 0, 0, 0, "", "", "", "conf1", id = 77),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2",
                0, 0, 0, 0, 0, 0, "", "", "", "conf2", id = 88)
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "conf1", "ip1")

        // Start the timer then trigger immediate auth-failed switch
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, "AIDL")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_AUTH_FAILED, "AIDL")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

        assertEquals(1, fakeQueue.enqueuedIds.size)
        assertEquals(77, fakeQueue.enqueuedIds[0])
    }

    // AC-2 / AC-6 scenario 2: watchdog recovery enqueues probe for current server id
    @Test
    fun watchdogRecovery_enqueuesToProbeQueueWithCurrentServerId() {
        // Set up a server with id=55 in the store
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1",
                0, 0, 0, 0, 0, 0, "", "", "", "client\n", id = 55)
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\n", "ip1")

        val service = buildConnectedService(nowMs = 50_000L)

        // Mock network probe to return false (tunnel stall)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))

        // Wire the probe queue into the service field
        ReflectionHelpers.setField(service, "probeQueue", fakeQueue as ProbeRequestQueue)

        var recoveryDispatches = 0
        ReflectionHelpers.setField(
            service,
            "watchdogRecoveryStarter",
            ({ _: Context, _: String, _: String? -> recoveryDispatches += 1 } as (Context, String, String?) -> Unit)
        )

        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)

        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        assertEquals(1, recoveryDispatches)
        assertEquals(1, fakeQueue.enqueuedIds.size)
        assertEquals(55, fakeQueue.enqueuedIds[0])
    }

    // AC-2b: watchdog does NOT probe when user changed selection (config mismatch)
    @Test
    fun watchdogRecovery_doesNotEnqueueProbeWhenSelectionChanged() {
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1",
                0, 0, 0, 0, 0, 0, "", "", "", "client\n", id = 55),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2",
                0, 0, 0, 0, 0, 0, "", "", "", "other-conf\n", id = 66)
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        // Save last started config for server 1 (id=55)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\n", "ip1")
        // Advance index to server 2 (simulates user changing selection)
        SelectedCountryStore.setCurrentIndex(appContext, 1)

        val service = buildConnectedService(nowMs = 50_000L)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        ReflectionHelpers.setField(service, "probeQueue", fakeQueue as ProbeRequestQueue)

        var recoveryDispatches = 0
        ReflectionHelpers.setField(
            service,
            "watchdogRecoveryStarter",
            ({ _: Context, _: String, _: String? -> recoveryDispatches += 1 } as (Context, String, String?) -> Unit)
        )

        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)

        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        assertEquals(1, recoveryDispatches)
        assertTrue("No probe should be enqueued when selection changed (config mismatch)", fakeQueue.enqueuedIds.isEmpty())
    }

    // AC-3 / AC-6 scenario 3: user-stop does NOT trigger probe
    @Test
    fun userStop_doesNotEnqueueProbe() {
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1",
                0, 0, 0, 0, 0, 0, "", "", "", "conf1", id = 42),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2",
                0, 0, 0, 0, 0, 0, "", "", "", "conf2", id = 99)
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "conf1", "ip1")

        // User disconnects: LEVEL_NOTCONNECTED without a timeout timer running → cancel without probing
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, "AIDL")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

        assertTrue("No probe should be enqueued for user-stop", fakeQueue.enqueuedIds.isEmpty())
    }

    // AC-5 / AC-6 scenario 4: server with id=0 does NOT get a probe enqueued
    @Test
    fun zeroIdServer_doesNotEnqueueProbe() {
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1",
                0, 0, 0, 0, 0, 0, "", "", "", "conf1", id = 0),  // id=0
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2",
                0, 0, 0, 0, 0, 0, "", "", "", "conf2", id = 0)   // id=0
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "conf1", "ip1")

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, "AIDL")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        assertTrue("No probe should be enqueued for id=0 server", fakeQueue.enqueuedIds.isEmpty())
    }

    // AC-4 / AC-6 scenario 5: LEVEL_NONETWORK from AIDL (device-loss) does NOT trigger probe
    @Test
    fun levelNoNetworkFromAidl_doesNotEnqueueProbe() {
        val servers = listOf(
            Server(1, "n1", "c1", Country("RU"), 0, SignalStrength.STRONG, "ip1",
                0, 0, 0, 0, 0, 0, "", "", "", "conf1", id = 42),
            Server(2, "n2", "c2", Country("RU"), 0, SignalStrength.STRONG, "ip2",
                0, 0, 0, 0, 0, 0, "", "", "", "conf2", id = 99)
        )
        SelectedCountryStore.saveSelection(appContext, "RU", servers)
        SelectedCountryStore.resetIndex(appContext)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "conf1", "ip1")

        // Simulate device network loss: AIDL reports LEVEL_NONETWORK while a timer is active
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, "AIDL")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NONETWORK, "AIDL")
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

        assertTrue("No probe should be enqueued for LEVEL_NONETWORK (device network loss)", fakeQueue.enqueuedIds.isEmpty())
    }

    // Helper: build a connected OpenVpnService with watchdog past warm-up period
    private fun buildConnectedService(nowMs: Long): OpenVpnService {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ nowMs } as () -> Long))
        ReflectionHelpers.setField(service, "watchdogProbeDispatcher", ImmediateDispatcher() as kotlinx.coroutines.CoroutineDispatcher)
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "connectedSinceMs", nowMs - 20_000L)
        ReflectionHelpers.setField(watchdogState, "lastHealthyTimestamp", nowMs - 20_000L)
        return service
    }

    private fun invokeEvaluateConnectedHealth(service: OpenVpnService, sampleAdvanced: Boolean, trafficDeltaBytes: Long) {
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "evaluateConnectedHealth",
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, sampleAdvanced),
            ClassParameter.from(Long::class.javaPrimitiveType!!, trafficDeltaBytes)
        )
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
    }

    private class ImmediateDispatcher : kotlinx.coroutines.CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            block.run()
        }
    }
}
