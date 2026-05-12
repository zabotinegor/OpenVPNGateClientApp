package com.yahorzabotsin.openvpnclientgate.vpn

import android.os.Looper
import com.yahorzabotsin.openvpnclientgate.core.servers.Country
import com.yahorzabotsin.openvpnclientgate.core.servers.SelectedCountryStore
import com.yahorzabotsin.openvpnclientgate.core.servers.Server
import com.yahorzabotsin.openvpnclientgate.core.servers.SignalStrength
import com.yahorzabotsin.openvpnclientgate.core.settings.ServerSource
import com.yahorzabotsin.openvpnclientgate.core.settings.UserSettingsStore
import de.blinkt.openvpn.core.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Tests for DEFAULT_V2 auto-switch parity (AC-3, TS-5, TS-6).
 *
 * TS-5: DEFAULT_V2 auto-switch can obtain the next server after startup without the user needing
 *       to reopen the country list (store already populated by initial selection).
 * TS-6: DEFAULT_V2 auto-switch hydrates the selected-country list when the store is empty and
 *       then continues shared circular switching behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ServerAutoSwitcherV2HydrationTest {

    private val appContext = RuntimeEnvironment.getApplication()
    private val source = "VPN_STATUS"
    private var originalStarter: ((android.content.Context, String, String?, Boolean) -> Unit)? = null
    private var originalStopper: ((android.content.Context) -> Unit)? = null
    private var originalHydrationCallback: ((android.content.Context, () -> Unit) -> Unit)? = null
    private data class StartCall(val cfg: String, val reconnect: Boolean)
    private val startCalls = mutableListOf<StartCall>()
    private var stopCalls = 0

    @Before
    fun setUp() {
        // Reset all ServerAutoSwitcher singleton state (timers, pending config, cycle index, etc.)
        // before each test. The virtual Robolectric clock is NOT reset between tests, so runnables
        // scheduled by previous tests can fire during this test's idleFor() calls unless cancelled.
        ConnectionStateManager.setReconnectingHint(false)
        ServerAutoSwitcher.onEngineLevel(appContext, de.blinkt.openvpn.core.ConnectionStatus.LEVEL_CONNECTED, "setUp-reset")
        UserSettingsStore.saveAutoSwitchWithinCountry(appContext, true)
        UserSettingsStore.saveStatusStallTimeoutSeconds(appContext, 2)
        ServerAutoSwitcher.setNoReplyThresholdForTest(2)
        ServerAutoSwitcher.setRepliedThresholdForTest(2)

        originalStarter = ServerAutoSwitcher.starter
        originalStopper = ServerAutoSwitcher.stopper
        originalHydrationCallback = ServerAutoSwitcher.v2HydrationCallback

        ServerAutoSwitcher.starter = { _, cfg, _, reconnect -> startCalls.add(StartCall(cfg, reconnect)) }
        ServerAutoSwitcher.stopper = { _ -> stopCalls++ }
        ServerAutoSwitcher.v2HydrationCallback = null

        startCalls.clear()
        stopCalls = 0
    }

    @After
    fun tearDown() {
        originalStarter?.let { ServerAutoSwitcher.starter = it }
        originalStopper?.let { ServerAutoSwitcher.stopper = it }
        originalHydrationCallback?.let { ServerAutoSwitcher.v2HydrationCallback = it } ?: run {
            ServerAutoSwitcher.v2HydrationCallback = null
        }
        ServerAutoSwitcher.resetNoReplyThreshold()
        ServerAutoSwitcher.resetRepliedThreshold()
        SelectedCountryStore.getSelectedCountry(appContext)?.let {
            appContext.getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE)
                .edit().clear().apply()
        }
    }

    // TS-5: After startup materialises the store (AC-1), DEFAULT_V2 auto-switch reads the
    // next server from the store and rotates circularly — no country-screen visit required.
    @Test
    fun autoSwitch_v2_rotates_within_populated_store() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        val servers = listOf(
            makeServer("conf-v2-1", "jp-1"),
            makeServer("conf-v2-2", "jp-2")
        )
        SelectedCountryStore.saveSelection(appContext, "Japan", servers)
        SelectedCountryStore.resetIndex(appContext)

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        // After timeout: stop requested; wait for NOTCONNECTED then start next
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(1, startCalls.size)
        assertEquals("conf-v2-2", startCalls.first().cfg)
        assertEquals(true, startCalls.first().reconnect)
    }

    // TS-6: When the store is empty for DEFAULT_V2, auto-switch invokes the hydration callback.
    // After hydration populates the store, the switch proceeds with the first server.
    @Test
    fun autoSwitch_v2_hydrates_empty_store_and_then_switches() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        // Store is intentionally empty
        appContext.getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        val hydratedServers = listOf(
            makeServer("conf-hydrated-1", "ip-1"),
            makeServer("conf-hydrated-2", "ip-2")
        )

        // Hydration callback synchronously populates the store then invokes onDone
        ServerAutoSwitcher.v2HydrationCallback = { ctx, onDone ->
            SelectedCountryStore.saveSelection(ctx, "Japan", hydratedServers)
            SelectedCountryStore.resetIndex(ctx)
            onDone()
        }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        // The hydration callback invoked onDone synchronously on the handler thread after stop request
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        // Hydration should have occurred and the switch should target the second server
        // (first circular move from index 0)
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals("Expected exactly one start call after hydration", 1, startCalls.size)
        // Fix 1: after hydration, beginChainedSwitch must start from index 0 (not skip it)
        assertEquals("First started server after hydration must be index 0 (conf-hydrated-1)",
            "conf-hydrated-1", startCalls.first().cfg)
        assertEquals(true, startCalls.first().reconnect)
    }

    // Fix 1 (gemini): single-server list after hydration must start the only server,
    // not immediately report "full cycle completed" and stop the engine.
    @Test
    fun autoSwitch_v2_single_server_after_hydration_starts_it_without_immediate_cycle_end() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        appContext.getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        val singleServer = listOf(makeServer("conf-single-hydrated", "ip-single"))

        ServerAutoSwitcher.v2HydrationCallback = { ctx, onDone ->
            SelectedCountryStore.saveSelection(ctx, "Japan", singleServer)
            SelectedCountryStore.resetIndex(ctx)
            onDone()
        }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        // Simulate VPN stop triggered by beginChainedSwitch; the single server should start
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals("Single hydrated server must be started (no premature full-cycle stop)",
            1, startCalls.size)
        assertEquals("conf-single-hydrated", startCalls.first().cfg)
        assertEquals(true, startCalls.first().reconnect)
        assertEquals("Engine must not be stopped by switcher before the server is tried",
            0, stopCalls)
    }

    // Fix 2 (codex P1): a concurrent LEVEL_AUTH_FAILED arriving while v2HydrationPending=true
    // must be silently ignored — not cause "completed full server cycle" and stop the engine.
    @Test
    fun autoSwitch_v2_auth_failed_during_pending_hydration_does_not_stop_engine() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        appContext.getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        var capturedOnDone: (() -> Unit)? = null
        val hydratedServers = listOf(makeServer("conf-race-1", "ip-race-1"))

        // Hydration callback captures onDone but does NOT invoke it (simulates in-flight async hydration)
        ServerAutoSwitcher.v2HydrationCallback = { _, onDone ->
            capturedOnDone = onDone
        }

        // First event triggers hydration via timer
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))

        assertNotNull("Hydration callback should have been invoked", capturedOnDone)

        // Second failure level arrives while hydration is still in-flight (v2HydrationPending=true)
        // AUTH_FAILED + timerActive=true re-enters requestSwitchNow without Fix 2
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_AUTH_FAILED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals("Engine must not stop while v2HydrationPending=true", 0, stopCalls)
        assertEquals("No switch must be started while hydration is still in-flight", 0, startCalls.size)

        // Complete hydration now
        SelectedCountryStore.saveSelection(appContext, "Japan", hydratedServers)
        SelectedCountryStore.resetIndex(appContext)
        capturedOnDone!!.invoke()
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        // Simulate VPN stop triggered by beginChainedSwitch
        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_NOTCONNECTED, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals("First server must start after hydration completes", 1, startCalls.size)
        assertEquals("conf-race-1", startCalls.first().cfg)
    }

    // TS-6 edge: When hydration also returns no servers, the engine is stopped without a crash.
    @Test
    fun autoSwitch_v2_hydration_returns_empty_store_stops_engine() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        appContext.getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()

        // Hydration callback does NOT populate the store
        ServerAutoSwitcher.v2HydrationCallback = { _, onDone -> onDone() }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        assertEquals(0, startCalls.size)
        assertTrue("Engine stop expected after hydration with no servers", stopCalls > 0)
    }

    // AC-3.1: Same thresholds and timing apply for DEFAULT_V2 as for Legacy — the hydration
    // callback guard must not change the normal full-cycle behavior when store is non-empty.
    @Test
    fun autoSwitch_v2_full_cycle_stops_engine_without_hydration_when_store_non_empty() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        val single = listOf(makeServer("conf-single", "ip-single"))
        SelectedCountryStore.saveSelection(appContext, "Japan", single)
        SelectedCountryStore.resetIndex(appContext)

        // Hydration callback must NOT be called when the full cycle exhausts the list
        var hydrationCalled = false
        ServerAutoSwitcher.v2HydrationCallback = { _, onDone -> hydrationCalled = true; onDone() }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

        assertEquals(false, hydrationCalled)
        assertEquals(0, startCalls.size)
        assertTrue("Engine stop expected after full cycle", stopCalls > 0)
    }

    // Fix #2: hydration callback validates config is non-blank
    @Test
    fun hydration_validates_server_config_before_switch() {
        UserSettingsStore.saveServerSource(appContext, ServerSource.DEFAULT_V2)
        appContext.getSharedPreferences("vpn_selection_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()

        val serverWithBlankConfig = makeServer(config = "", ip = "1.0.0.1")
        ServerAutoSwitcher.v2HydrationCallback = { _, onDone ->
            SelectedCountryStore.saveSelection(appContext, "Japan", listOf(serverWithBlankConfig))
            SelectedCountryStore.resetIndex(appContext)
            onDone()
        }

        ServerAutoSwitcher.onEngineLevel(appContext, ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET, source)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

        // After fix: callback detects blank config and stops engine, does not start next VPN.
        assertEquals(0, startCalls.size)
        assertTrue("Engine stop expected when hydrated server config is blank", stopCalls > 0)
    }

    // --------------- helpers ---------------

    private fun makeServer(config: String, ip: String) = Server(
        lineIndex = 0,
        name = ip,
        city = "",
        country = Country("Japan", "JP"),
        ping = 0,
        signalStrength = SignalStrength.WEAK,
        ip = ip,
        score = 0,
        speed = 0L,
        numVpnSessions = 0,
        uptime = 0L,
        totalUsers = 0L,
        totalTraffic = 0L,
        logType = "",
        operator = "",
        message = "",
        configData = config
    )
}
