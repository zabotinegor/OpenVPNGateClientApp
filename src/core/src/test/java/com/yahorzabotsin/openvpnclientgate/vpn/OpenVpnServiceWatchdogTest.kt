package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OpenVpnServiceWatchdogTest {
    private val appContext = RuntimeEnvironment.getApplication()
    private val logTag = com.yahorzabotsin.openvpnclientgate.core.logging.LogTags.APP + ":" + "OpenVpnService"

    @Before
    fun setUp() {
        ShadowLog.clear()
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    @After
    fun tearDown() {
        ShadowLog.clear()
        ConnectionStateManager.setReconnectingHint(false)
        ConnectionStateManager.updateFromEngine(ConnectionStatus.LEVEL_NOTCONNECTED, null)
        ConnectionStateManager.updateState(ConnectionState.DISCONNECTED)
    }

    @Test
    fun healthyTraffic_resetsWatchdogStateWithoutRecovery() {
        val service = buildConnectedService(nowMs = 50_000L)
        var recoveryDispatches = 0
        ReflectionHelpers.setField(
            service,
            "watchdogRecoveryStarter",
            ({ _: Context, _: String, _: String? -> recoveryDispatches += 1 } as (Context, String, String?) -> Unit)
        )
        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)
        ReflectionHelpers.setField(watchdogState, "recoveryAttempts", 1)
        ReflectionHelpers.setField(watchdogState, "degraded", true)

        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 1024L)

        assertEquals(0, ReflectionHelpers.getField<Int>(watchdogState, "consecutiveFailures"))
        assertEquals(0, ReflectionHelpers.getField<Int>(watchdogState, "recoveryAttempts"))
        assertFalse(ReflectionHelpers.getField<Boolean>(watchdogState, "degraded"))
        assertEquals(0, recoveryDispatches)
    }

    @Test
    fun transientFailure_belowThresholdDoesNotTriggerRecovery() {
        val service = buildConnectedService(nowMs = 60_000L)
        var recoveryDispatches = 0
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        ReflectionHelpers.setField(
            service,
            "watchdogRecoveryStarter",
            ({ _: Context, _: String, _: String? -> recoveryDispatches += 1 } as (Context, String, String?) -> Unit)
        )
        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 1)

        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        assertEquals(2, ReflectionHelpers.getField<Int>(watchdogState, "consecutiveFailures"))
        assertFalse(ReflectionHelpers.getField<Boolean>(watchdogState, "degraded"))
        assertEquals(0, recoveryDispatches)
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
    }

    @Test
    fun sustainedFailure_triggersSingleControlledRecovery() {
        val service = buildConnectedService(nowMs = 70_000L)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\n", null)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        var recoveryDispatches = 0
        var recoveryConfig: String? = null
        ReflectionHelpers.setField(
            service,
            "watchdogRecoveryStarter",
            ({ _: Context, config: String, _: String? ->
                recoveryDispatches += 1
                recoveryConfig = config
            } as (Context, String, String?) -> Unit)
        )
        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)

        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        assertTrue(ReflectionHelpers.getField<Boolean>(watchdogState, "degraded"))
        assertEquals(1, ReflectionHelpers.getField<Int>(watchdogState, "recoveryAttempts"))
        assertEquals(1, recoveryDispatches)
        assertEquals("client\n", recoveryConfig)
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(logs.any { it.contains("Watchdog: unhealthy trafficDelta=0 probe=false") && it.contains("thresholdCount=3/3") })
        assertTrue(logs.any { it.contains("Watchdog: threshold reached") && it.contains("recoveryAttempt=1/3") })
    }

    @Test
    fun cooldownWindow_preventsReconnectStorm() {
        val nowMs = 80_000L
        val service = buildConnectedService(nowMs = nowMs)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        var recoveryDispatches = 0
        ReflectionHelpers.setField(
            service,
            "watchdogRecoveryStarter",
            ({ _: Context, _: String, _: String? -> recoveryDispatches += 1 } as (Context, String, String?) -> Unit)
        )
        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)
        ReflectionHelpers.setField(watchdogState, "recoveryAttempts", 1)
        ReflectionHelpers.setField(watchdogState, "degraded", true)
        ReflectionHelpers.setField(watchdogState, "lastRecoveryTimestamp", nowMs - 5_000L)

        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        assertEquals(0, recoveryDispatches)
        assertEquals(2, ReflectionHelpers.getField<Int>(watchdogState, "consecutiveFailures"))
        assertEquals(1, ReflectionHelpers.getField<Int>(watchdogState, "recoveryAttempts"))
    }

    @Test
    fun repeatedFailures_triggerFailSafeDisconnectAtRetryLimit() {
        val service = buildConnectedService(nowMs = 90_000L)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)
        ReflectionHelpers.setField(watchdogState, "recoveryAttempts", 3)
        ReflectionHelpers.setField(
            service,
            "engineBinder",
            object : IOpenVPNServiceInternal.Stub() {
                override fun protect(fd: Int) = false
                override fun userPause(b: Boolean) {}
                override fun stopVPN(replaceConnection: Boolean) = true
                override fun addAllowedExternalApp(packagename: String?) {}
                override fun isAllowedExternalApp(packagename: String?) = false
                override fun challengeResponse(repsonse: String?) {}
            }
        )
        ReflectionHelpers.setField(service, "boundToEngine", true)

        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        assertEquals(ConnectionState.DISCONNECTED, ConnectionStateManager.state.value)

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(logs.any { it.contains("bounded recovery exhausted; entering fail-safe disconnect") })
        assertTrue(logs.any { it.contains("Watchdog: fail-safe disconnect reason=attempt_limit_reached") })
    }

    @Test
    fun inducedDegradation_emitsConsistentMarkers() {
        val service = buildConnectedService(nowMs = 100_000L)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)

        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(logs.any { it.contains("Watchdog: unhealthy trafficDelta=0 probe=false") })
        assertTrue(logs.any { it.contains("thresholdCount=3/3") })

        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 3)
        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        val updatedLogs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(updatedLogs.any { it.contains("Watchdog: threshold reached") })
        assertTrue(updatedLogs.any { it.contains("recoveryAttempt=1/3") })
    }

    private fun buildConnectedService(nowMs: Long): OpenVpnService {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ nowMs } as () -> Long))
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
    }
}
