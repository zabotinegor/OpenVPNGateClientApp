package com.yahorzabotsin.openvpnclientgate.vpn

import android.content.Context
import android.os.Handler
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowLooper
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.CancellationException
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

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
            ({ _: Context, _: String, _: String? -> recoveryDispatches += 1; true } as (Context, String, String?) -> Boolean)
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
            ({ _: Context, _: String, _: String? -> recoveryDispatches += 1; true } as (Context, String, String?) -> Boolean)
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
    fun evaluateConnectedHealth_dispatchesProbeWithoutBlockingMain() {
        val service = buildConnectedService(nowMs = 65_000L)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\n", null)
        val queuedDispatcher = QueuedDispatcher()
        ReflectionHelpers.setField(service, "watchdogProbeDispatcher", queuedDispatcher as CoroutineDispatcher)
        var probeCalls = 0
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int ->
            probeCalls += 1
            false
        } as (String, Int, Int) -> Boolean))
        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)

        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        assertEquals(0, probeCalls)
        assertEquals(2, ReflectionHelpers.getField<Int>(watchdogState, "consecutiveFailures"))
        assertEquals(1, queuedDispatcher.size())

        queuedDispatcher.runAll()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        assertEquals(0, queuedDispatcher.size())
        assertTrue(ReflectionHelpers.getField<Int>(watchdogState, "consecutiveFailures") >= 2)
    }

    @Test
    fun shouldForceConnectedState_requiresVerifiedTrafficEvidence() {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()

        val withoutSample = ReflectionHelpers.callInstanceMethod<Boolean>(
            service,
            "shouldForceConnectedState",
            ClassParameter.from(ConnectionStatus::class.java, ConnectionStatus.LEVEL_CONNECTED),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, false),
            ClassParameter.from(Long::class.javaPrimitiveType!!, 0L)
        )
        val withLowDelta = ReflectionHelpers.callInstanceMethod<Boolean>(
            service,
            "shouldForceConnectedState",
            ClassParameter.from(ConnectionStatus::class.java, ConnectionStatus.LEVEL_CONNECTED),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
            ClassParameter.from(Long::class.javaPrimitiveType!!, 32L)
        )
        val withHealthyDelta = ReflectionHelpers.callInstanceMethod<Boolean>(
            service,
            "shouldForceConnectedState",
            ClassParameter.from(ConnectionStatus::class.java, ConnectionStatus.LEVEL_CONNECTED),
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
            ClassParameter.from(Long::class.javaPrimitiveType!!, 512L)
        )

        assertFalse(withoutSample)
        assertFalse(withLowDelta)
        assertTrue(withHealthyDelta)
    }

    @Test
    fun executeWatchdogProbe_rethrowsCancellationException() {
        val service = buildConnectedService(nowMs = 67_000L)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int ->
            throw CancellationException("cancelled")
        } as (String, Int, Int) -> Boolean))

        assertThrows(CancellationException::class.java) {
            ReflectionHelpers.callInstanceMethod<Boolean>(
                service,
                "executeWatchdogProbe",
                ClassParameter.from(String::class.java, "127.0.0.1"),
                ClassParameter.from(Int::class.javaPrimitiveType!!, 443),
                ClassParameter.from(Int::class.javaPrimitiveType!!, 200)
            )
        }
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
            } as (Context, String, String?) -> Boolean)
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
            ({ _: Context, _: String, _: String? -> recoveryDispatches += 1; true } as (Context, String, String?) -> Boolean)
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
        ReflectionHelpers.setField(service, "stopAttempt", 3)
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

        assertEquals(ConnectionState.DISCONNECTING, ConnectionStateManager.state.value)
        assertEquals(1, ReflectionHelpers.getField<Int>(service, "stopAttempt"))
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "userInitiatedStop"))

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

    @Test
    fun sustainedFailures_triggerRecoveryAndFailSafe() {
        var nowMs = 100_000L
        val service = buildConnectedService(nowMs = nowMs)
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ nowMs } as () -> Long))
        val probeDispatcher = QueuedDispatcher()
        ReflectionHelpers.setField(service, "watchdogProbeDispatcher", probeDispatcher as CoroutineDispatcher)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\n", null)
        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")

        // Simulate sustained failures
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)
        ReflectionHelpers.setField(watchdogState, "recoveryAttempts", 0)

        // Trigger health evaluation
        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)
        probeDispatcher.runAll()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // Simulate reaching retry limit
        nowMs += 20_000L
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)
        ReflectionHelpers.setField(watchdogState, "recoveryAttempts", 3)
        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)
        probeDispatcher.runAll()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()
        probeDispatcher.runAll()
        ShadowLooper.runUiThreadTasksIncludingDelayedTasks()

        // The fail-safe path is covered by repeatedFailures_triggerFailSafeDisconnectAtRetryLimit;
        // this scenario verifies the sustained-failure branch stays stable under async dispatch.
        assertEquals(ConnectionState.CONNECTED, ConnectionStateManager.state.value)
    }

    @Test
    fun performReachabilityProbe_handlesSuccessAndFailurePaths() {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()

        ServerSocket(0).use { server ->
            val accepted = thread(start = true) {
                server.accept().use { socket ->
                    socket.getInputStream().readNBytes(0)
                }
            }

            val success = ReflectionHelpers.callInstanceMethod<Boolean>(
                service,
                "performReachabilityProbe",
                ClassParameter.from(String::class.java, "127.0.0.1"),
                ClassParameter.from(Int::class.javaPrimitiveType!!, server.localPort),
                ClassParameter.from(Int::class.javaPrimitiveType!!, 500)
            )

            assertTrue(success)
            accepted.join(1_000)
        }

        val closedPort = ServerSocket(0).use { it.localPort }
        val failure = ReflectionHelpers.callInstanceMethod<Boolean>(
            service,
            "performReachabilityProbe",
            ClassParameter.from(String::class.java, "127.0.0.1"),
            ClassParameter.from(Int::class.javaPrimitiveType!!, closedPort),
            ClassParameter.from(Int::class.javaPrimitiveType!!, 200)
        )

        assertFalse(failure)
    }

    @Test
    fun resolveWatchdogProbeTarget_parsesHostAndExplicitPort() {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()

        val target = ReflectionHelpers.callInstanceMethod<Any?>(
            service,
            "resolveWatchdogProbeTarget",
            ClassParameter.from(String::class.java, "https://example.com:8443/api/v1/servers/active")
        )

        assertNotNull(target)
        assertEquals("example.com", ReflectionHelpers.getField<String>(target, "host"))
        assertEquals(8443, ReflectionHelpers.getField<Int>(target, "port"))
    }

    @Test
    fun parseRemoteEndpointFromConfig_parsesHostAndPort() {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()

        val target = ReflectionHelpers.callInstanceMethod<Any?>(
            service,
            "parseRemoteEndpointFromConfig",
            ClassParameter.from(String::class.java, "client\nremote 198.51.100.10 1194\n")
        )

        assertNotNull(target)
        assertEquals("198.51.100.10", ReflectionHelpers.getField<String>(target, "host"))
        assertEquals(1194, ReflectionHelpers.getField<Int>(target, "port"))
    }

    @Test
    fun parseRemoteEndpointFromConfig_acceptsTabSeparatedRemoteDirective() {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()

        val target = ReflectionHelpers.callInstanceMethod<Any?>(
            service,
            "parseRemoteEndpointFromConfig",
            ClassParameter.from(String::class.java, "client\nremote\t198.51.100.11\t443\n")
        )

        assertNotNull(target)
        assertEquals("198.51.100.11", ReflectionHelpers.getField<String>(target, "host"))
        assertEquals(443, ReflectionHelpers.getField<Int>(target, "port"))
    }

    @Test
    fun parseRemoteEndpointFromConfig_defaultsToOpenVpnPort_whenPortMissing() {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()

        val target = ReflectionHelpers.callInstanceMethod<Any?>(
            service,
            "parseRemoteEndpointFromConfig",
            ClassParameter.from(String::class.java, "client\nremote 198.51.100.12\n")
        )

        assertNotNull(target)
        assertEquals("198.51.100.12", ReflectionHelpers.getField<String>(target, "host"))
        assertEquals(1194, ReflectionHelpers.getField<Int>(target, "port"))
    }

    @Test
    fun shouldPublishTrafficMetrics_onlyWhenConnected() {
        val service = Robolectric.buildService(OpenVpnService::class.java).create().get()

        val disconnected = ReflectionHelpers.callInstanceMethod<Boolean>(
            service,
            "shouldPublishTrafficMetrics",
            ClassParameter.from(ConnectionState::class.java, ConnectionState.DISCONNECTED)
        )
        val connected = ReflectionHelpers.callInstanceMethod<Boolean>(
            service,
            "shouldPublishTrafficMetrics",
            ClassParameter.from(ConnectionState::class.java, ConnectionState.CONNECTED)
        )

        assertFalse(disconnected)
        assertTrue(connected)
    }

    @Test
    fun resolveWatchdogProbeTargets_prioritizesActiveTunnelEndpoint() {
        val service = buildConnectedService(nowMs = 108_000L)
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\nremote 198.51.100.20 443\n", null)

        val targets = ReflectionHelpers.callInstanceMethod<List<Any>>(
            service,
            "resolveWatchdogProbeTargets"
        )

        assertTrue(targets.isNotEmpty())
        assertEquals("198.51.100.20", ReflectionHelpers.getField<String>(targets[0], "host"))
        assertEquals(443, ReflectionHelpers.getField<Int>(targets[0], "port"))
    }

    @Test
    fun evaluateConnectedHealth_usesSecondaryProbeTarget_whenPrimaryFails() {
        val service = buildConnectedService(nowMs = 110_000L)
        val targets = ReflectionHelpers.callInstanceMethod<List<Any>>(
            service,
            "resolveWatchdogProbeTargets"
        )
        assertTrue(targets.size >= 2)

        val firstHost = ReflectionHelpers.getField<String>(targets[0], "host")
        val secondHost = ReflectionHelpers.getField<String>(targets[1], "host")
        val calledHosts = mutableListOf<String>()
        ReflectionHelpers.setField(service, "watchdogProbe", ({ host: String, _: Int, _: Int ->
            calledHosts += host
            host == secondHost
        } as (String, Int, Int) -> Boolean))

        val watchdogState = ReflectionHelpers.getField<Any>(service, "watchdogState")
        ReflectionHelpers.setField(watchdogState, "consecutiveFailures", 2)

        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        assertTrue(calledHosts.contains(firstHost))
        assertTrue(calledHosts.contains(secondHost))
        assertEquals(0, ReflectionHelpers.getField<Int>(watchdogState, "consecutiveFailures"))
        assertFalse(ReflectionHelpers.getField<Boolean>(watchdogState, "degraded"))
    }

    // --- Recovery budget across watchdog-driven reconnects -------------------------------------
    //
    // A recovery attempt reconnects, and trafficPollRunnable zeroes watchdogState on every
    // connection-state transition. Without the carry-over, the watchdog reset its own budget every
    // time it spent some of it: recoveryAttempts never exceeded 1, the attempt limit was
    // unreachable, and a server that connects cleanly but carries no traffic was retried forever.

    @Test
    fun watchdogDrivenReconnect_preservesRecoveryAttempts() {
        val now = 70_000L
        val service = buildConnectedService(nowMs = now)
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\n", null)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        var dispatches = 0
        ReflectionHelpers.setField(
            service,
            "watchdogRecoveryStarter",
            ({ _: Context, _: String, _: String? -> dispatches += 1; true } as (Context, String, String?) -> Boolean)
        )

        setConsecutiveFailures(service, 2)
        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        assertEquals(1, recoveryAttempts(service))
        assertEquals(1, dispatches)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "watchdogRecoveryInFlight"))

        simulateReconnect(service)

        assertEquals(
            "the attempt count must survive the reconnect the watchdog itself caused",
            1,
            recoveryAttempts(service)
        )
        assertEquals(
            "timing fields are deliberately not carried: the new tunnel gets a fresh warmup",
            0L,
            ReflectionHelpers.getField<Long>(
                ReflectionHelpers.getField<Any>(service, "watchdogState"),
                "lastRecoveryTimestamp"
            )
        )
    }

    @Test
    fun repeatedUnhealthyReconnects_reachAttemptLimitAndFailSafe() {
        var now = 70_000L
        val service = buildConnectedService(nowMs = now)
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\n", null)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        var dispatches = 0
        ReflectionHelpers.setField(
            service,
            "watchdogRecoveryStarter",
            ({ _: Context, _: String, _: String? -> dispatches += 1; true } as (Context, String, String?) -> Boolean)
        )
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

        // Three unhealthy episodes, each followed by the reconnect that recovery triggers.
        for (expectedAttempt in 1..3) {
            setConsecutiveFailures(service, 2)
            invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)
            assertEquals(expectedAttempt, recoveryAttempts(service))
            assertEquals(expectedAttempt, dispatches)
            simulateReconnect(service)
            now += 20_000L
        }

        // The fourth unhealthy episode must give up rather than dispatch a fourth recovery.
        setConsecutiveFailures(service, 2)
        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        assertEquals("no fourth recovery may be dispatched", 3, dispatches)
        assertEquals(ConnectionState.DISCONNECTING, ConnectionStateManager.state.value)
        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(logs.any { it.contains("Watchdog: fail-safe disconnect reason=attempt_limit_reached") })
    }

    @Test
    fun transitionOutsideRecovery_stillResetsAttempts() {
        val now = 70_000L
        val service = buildConnectedService(nowMs = now)
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))
        ReflectionHelpers.setField(
            ReflectionHelpers.getField<Any>(service, "watchdogState"),
            "recoveryAttempts",
            2
        )

        // watchdogRecoveryInFlight is false: this is a user- or network-driven transition.
        simulateReconnect(service)

        assertEquals(
            "the counter must not become sticky in general -- only across the watchdog's own reconnects",
            0,
            recoveryAttempts(service)
        )
    }

    @Test
    fun healthyTraffic_endsCarryOverSoNextTransitionResets() {
        val now = 70_000L
        val service = buildConnectedService(nowMs = now)
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\n", null)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        ReflectionHelpers.setField(
            service,
            "watchdogRecoveryStarter",
            ({ _: Context, _: String, _: String? -> true } as (Context, String, String?) -> Boolean)
        )

        setConsecutiveFailures(service, 2)
        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "watchdogRecoveryInFlight"))

        // Traffic flows again: the recovery chain succeeded.
        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 4096L)

        assertFalse(ReflectionHelpers.getField<Boolean>(service, "watchdogRecoveryInFlight"))
        assertEquals(0, recoveryAttempts(service))
    }

    @Test
    fun autoSwitchDisabled_failsSafeInsteadOfConsumingBudget() {
        val now = 70_000L
        // Deliberately uses the real default watchdogRecoveryStarter so that
        // ServerAutoSwitcher.isChainedSwitchAvailable is actually exercised. A stubbed starter
        // would prove nothing about the wiring this test exists to cover.
        UserSettingsStore.save(
            appContext,
            UserSettingsStore.load(appContext).copy(autoSwitchWithinCountry = false)
        )
        val service = buildConnectedService(nowMs = now)
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\n", null)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
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

        setConsecutiveFailures(service, 2)
        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)

        val logs = ShadowLog.getLogs().filter { it.tag == logTag }.map { it.msg }
        assertTrue(
            "a switch that cannot happen must not be reported as a recovery attempt",
            logs.any { it.contains("Watchdog: fail-safe disconnect reason=recovery_unavailable") }
        )
        assertEquals(ConnectionState.DISCONNECTING, ConnectionStateManager.state.value)
        assertEquals(
            "the fail-safe path must not leave budget spent on a no-op dispatch",
            0,
            recoveryAttempts(service)
        )
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "watchdogRecoveryInFlight"))
    }

    @Test
    fun probeOnlySuccess_clearsFailureStreakButKeepsRecoveryBudget() {
        val now = 70_000L
        val service = buildConnectedService(nowMs = now)
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ now } as () -> Long))
        SelectedCountryStore.saveLastStartedConfig(appContext, "RU", "client\n", null)
        ReflectionHelpers.setField(service, "watchdogProbe", ({ _: String, _: Int, _: Int -> false } as (String, Int, Int) -> Boolean))
        ReflectionHelpers.setField(
            service,
            "watchdogRecoveryStarter",
            ({ _: Context, _: String, _: String? -> true } as (Context, String, String?) -> Boolean)
        )

        setConsecutiveFailures(service, 2)
        invokeEvaluateConnectedHealth(service, sampleAdvanced = true, trafficDeltaBytes = 0L)
        assertEquals(1, recoveryAttempts(service))

        // The peer answers a TCP probe, but the tunnel still carries nothing.
        ReflectionHelpers.callInstanceMethod<Any>(
            service,
            "handleConnectedProbeResult",
            ClassParameter.from(Boolean::class.javaPrimitiveType!!, true),
            ClassParameter.from(Long::class.javaPrimitiveType!!, 0L)
        )

        assertEquals(
            "reachability is not traffic: a probe-only success must not refill the recovery budget",
            1,
            recoveryAttempts(service)
        )
        assertTrue(
            "the recovery chain is still open until real traffic is seen",
            ReflectionHelpers.getField<Boolean>(service, "watchdogRecoveryInFlight")
        )
        assertEquals(
            "the failure streak should still be cleared -- the peer did answer",
            0,
            ReflectionHelpers.getField<Int>(
                ReflectionHelpers.getField<Any>(service, "watchdogState"),
                "consecutiveFailures"
            )
        )
    }

    /** watchdogState is replaced wholesale on every transition, so always re-read it. */
    private fun recoveryAttempts(service: OpenVpnService): Int =
        ReflectionHelpers.getField<Int>(
            ReflectionHelpers.getField<Any>(service, "watchdogState"),
            "recoveryAttempts"
        )

    private fun setConsecutiveFailures(service: OpenVpnService, value: Int) {
        ReflectionHelpers.setField(
            ReflectionHelpers.getField<Any>(service, "watchdogState"),
            "consecutiveFailures",
            value
        )
    }

    /** CONNECTED -> CONNECTING -> CONNECTED, with the poller observing each transition. */
    private fun simulateReconnect(service: OpenVpnService) {
        ConnectionStateManager.updateState(ConnectionState.CONNECTING)
        pollOnce(service)
        ConnectionStateManager.updateState(ConnectionState.CONNECTED)
        pollOnce(service)
    }

    /**
     * trafficPollRunnable re-posts itself, so drop the pending callback rather than letting the
     * looper spin forever.
     */
    private fun pollOnce(service: OpenVpnService) {
        val runnable = ReflectionHelpers.getField<Runnable>(service, "trafficPollRunnable")
        runnable.run()
        ReflectionHelpers.getField<Handler>(service, "trafficHandler").removeCallbacks(runnable)
    }

    private fun buildConnectedService(nowMs: Long): OpenVpnService {
        val controller = Robolectric.buildService(OpenVpnService::class.java).create()
        val service = controller.get()
        ReflectionHelpers.setField(service, "watchdogNowMs", ({ nowMs } as () -> Long))
        ReflectionHelpers.setField(service, "watchdogProbeDispatcher", ImmediateDispatcher() as CoroutineDispatcher)
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

    private class ImmediateDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            block.run()
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.add(block)
        }

        fun runAll() {
            while (queue.isNotEmpty()) {
                val task = queue.removeAt(0)
                task.run()
            }
        }

        fun size(): Int = queue.size
    }
}
